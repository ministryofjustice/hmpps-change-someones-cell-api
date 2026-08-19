package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNotesApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * Resolves what a migrated row's case note holds - prisoner number, reason code, explanation,
 * timestamp - onto the row, once. Shared between the read path (CellMovementReasonService) and
 * the one-off backfill (CellMoveReasonMigrationService), so the two cannot drift on what
 * "enriched" means.
 *
 * [CellMovementNomisEntity.enrichedAt] set means done (even with null note fields, which records
 * that the case note is definitively gone); null means try again later.
 *
 * The split between the two entry points is deliberate. [enrichIfNeeded] is the read path and
 * resolves the prisoner via prisoner-search only - a historic booking it cannot resolve is left
 * for the backfill, keeping NOMIS reads out of the serving path. The backfill resolves the
 * prisoner itself (batched search plus the one-off prison-api fallback) and enters at
 * [enrichWithPrisonerNumber].
 */
@Component
class CellMovementNomisEnricher(
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val caseNotesApiClient: CaseNotesApiClient,
  private val clock: Clock,
) {

  /**
   * The read path's entry point. Two outcomes deliberately do not set [CellMovementNomisEntity.enrichedAt]:
   *  - the prisoner number could not be resolved - prisoner-search only answers for a prisoner's
   *    current booking, so an old booking needs the backfill's one-off prison-api lookup instead;
   *  - the case-notes call failed transiently, where retrying on a later read costs nothing.
   */
  fun enrichIfNeeded(row: CellMovementNomisEntity): CellMovementNomisEntity {
    if (row.enrichedAt != null) return row

    val prisonerNumber = row.prisonerNumber
      ?: prisonerSearchClient.getPrisonerByBookingId(row.bookingId)?.prisonerNumber
      ?: run {
        log.info("No current prisoner for booking {} - leaving row for the backfill to enrich", row.bookingId)
        return row
      }
    return enrichWithPrisonerNumber(row, prisonerNumber)
  }

  /**
   * The shared tail, entered directly by the backfill once it has resolved the prisoner number.
   * Fetches the case note and persists whatever was learned - even a transient failure keeps the
   * prisoner number, with enrichedAt left null so a later attempt retries the note.
   */
  fun enrichWithPrisonerNumber(row: CellMovementNomisEntity, prisonerNumber: String): CellMovementNomisEntity {
    row.prisonerNumber = prisonerNumber

    try {
      caseNotesApiClient.getCaseNote(prisonerNumber, row.caseNoteLegacyId.toString())?.let { note ->
        row.reasonCode = note.subType
        row.commentText = note.text
        row.caseNoteUuid = note.caseNoteId
        row.occurredAt = note.occurredAt
      } ?: log.info(
        "Case note {} for {} no longer exists - recording the movement without its explanation",
        row.caseNoteLegacyId,
        prisonerNumber,
      )
      row.enrichedAt = LocalDateTime.now(clock)
    } catch (e: Exception) {
      // Transient: keep the prisoner number we did learn, leave enrichedAt null so the next
      // attempt retries the note, and still serve what we have.
      log.warn("Could not read case note {} for {}: {}", row.caseNoteLegacyId, prisonerNumber, e.message)
    }

    return cellMovementNomisRepository.saveAndFlush(row)
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
