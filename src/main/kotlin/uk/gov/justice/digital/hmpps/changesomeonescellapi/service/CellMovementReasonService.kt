package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNotesApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.WhereaboutsApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellMovementReasonNotFoundException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementReason
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import java.time.Clock
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

/**
 * Serves "what happened" for a cell move, across the movements this service records, the ones
 * already migrated from whereabouts-api, and - the transitional case - the ones still only in
 * whereabouts.
 *
 * Callers get one endpoint and do not have to know which side a movement came from. That is the
 * point: hmpps-prisoner-profile currently makes two calls per row of the location history page -
 * whereabouts for a case note id, then case-notes for its text - and the migration would otherwise
 * mean it needed a third path for old data.
 *
 * The transitional case is what makes the cutover need no outage and no big-bang. A movement not
 * found on our side is fetched from whereabouts on first read, persisted, enriched from its case
 * note, and served - so from the moment prisoner-profile switches to this endpoint, every movement
 * it asks about migrates itself. The one-off backfill then sweeps the rows nobody asked about, and
 * once the counts reconcile, whereabouts' table can be dropped. Migrate-on-read alone would never
 * converge (unread rows would sit in whereabouts forever); backfill alone would need the sweep
 * finished before the switchover. Together, neither has to wait for the other.
 *
 * Not `@Transactional` at class level: reads that learn something persist it as they go, and each
 * persistence is its own small write - the same each-step-commits reasoning as CellMovementService.
 */
@Service
class CellMovementReasonService(
  private val cellMovementRepository: CellMovementRepository,
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val caseNotesApiClient: CaseNotesApiClient,
  private val whereaboutsApiClient: WhereaboutsApiClient,
  private val clock: Clock,
) {

  fun findByBedAssignment(bookingId: Long, bedAssignmentSequence: Int): CellMovementReason {
    cellMovementRepository
      .findFirstByBookingIdAndBedAssignmentSequenceOrderByOccurredAtDesc(bookingId, bedAssignmentSequence)
      ?.let { return it.toReason() }

    cellMovementNomisRepository
      .findById(CellMovementNomisId(bookingId, bedAssignmentSequence))
      .getOrNull()
      ?.let { return enrichIfNeeded(it).toReason() }

    return readThroughFromWhereabouts(bookingId, bedAssignmentSequence)?.toReason()
      ?: throw CellMovementReasonNotFoundException(bookingId, bedAssignmentSequence)
  }

  /**
   * The transitional path: the movement exists only in whereabouts, so fetch the link, keep it,
   * enrich it, and serve it. The row persists even when enrichment cannot complete, so the fact
   * that the movement exists is never lost - the backfill or a later read finishes the job.
   *
   * A whereabouts failure other than 404 propagates as an error rather than being softened into
   * "not found": until the backfill has run, whereabouts being down means we genuinely do not know
   * whether the movement exists, and a 404 here would assert that it does not.
   */
  private fun readThroughFromWhereabouts(bookingId: Long, bedAssignmentSequence: Int): CellMovementNomisEntity? {
    val link = whereaboutsApiClient.getCellMoveReason(bookingId, bedAssignmentSequence) ?: return null

    log.info("Migrating cell move reason for booking {} sequence {} on first read", bookingId, bedAssignmentSequence)
    val row = cellMovementNomisRepository.saveAndFlush(
      CellMovementNomisEntity(
        bookingId = link.bookingId,
        bedAssignmentSequence = link.bedAssignmentsSequence,
        caseNoteLegacyId = link.caseNoteId,
      ),
    )
    return enrichIfNeeded(row)
  }

  /**
   * Resolves what the case note holds - prisoner number, reason code, explanation, timestamp -
   * onto the row, once. [CellMovementNomisEntity.enrichedAt] set means done (even with null note
   * fields, which records that the case note is definitively gone); null means try again next read.
   *
   * Two outcomes deliberately do not set it:
   *  - the prisoner number could not be resolved - prisoner-search only answers for a prisoner's
   *    current booking, so an old booking needs the backfill's one-off prison-api lookup instead;
   *  - the case-notes call failed transiently, where retrying on a later read costs nothing.
   */
  private fun enrichIfNeeded(row: CellMovementNomisEntity): CellMovementNomisEntity {
    if (row.enrichedAt != null) return row

    val prisonerNumber = row.prisonerNumber
      ?: prisonerSearchClient.getPrisonerByBookingId(row.bookingId)?.prisonerNumber
      ?: run {
        log.info("No current prisoner for booking {} - leaving row for the backfill to enrich", row.bookingId)
        return row
      }
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
      // Transient: keep the prisoner number we did learn, leave enrichedAt null so the next read
      // retries the note, and still serve what we have.
      log.warn("Could not read case note {} for {}: {}", row.caseNoteLegacyId, prisonerNumber, e.message)
    }

    return cellMovementNomisRepository.saveAndFlush(row)
  }

  /** Everything is on the row. No downstream call, whatever the status of the movement. */
  private fun CellMovementEntity.toReason() = CellMovementReason(
    bookingId = bookingId,
    // Not null: this row was found by matching on it.
    bedAssignmentSequence = bedAssignmentSequence!!,
    source = CellMovementSource.CELL_MOVEMENTS,
    prisonerNumber = prisonerNumber,
    reasonCode = reasonCode,
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    caseNoteLegacyId = caseNoteLegacyId,
    occurredAt = occurredAt,
    recordedBy = recordedBy,
    movementType = movementType,
  )

  private fun CellMovementNomisEntity.toReason() = CellMovementReason(
    bookingId = bookingId,
    bedAssignmentSequence = bedAssignmentSequence,
    source = CellMovementSource.MIGRATED_FROM_WHEREABOUTS,
    prisonerNumber = prisonerNumber,
    reasonCode = reasonCode,
    commentText = commentText,
    caseNoteUuid = caseNoteUuid,
    caseNoteLegacyId = caseNoteLegacyId,
    occurredAt = occurredAt,
    // Whereabouts never recorded who performed the move; that fact lives only in NOMIS.
    recordedBy = null,
    // Unknowable. Whereabouts never performed a cell swap, so in practice every migrated row is a
    // cell move - but "in practice" is not something to assert on a prisoner's record.
    movementType = null,
  )

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
