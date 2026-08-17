package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNote
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNotesApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellMovementReasonNotFoundException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementReason
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import kotlin.jvm.optionals.getOrNull

/**
 * Serves "what happened" for a cell move, across both the movements this service records and the
 * ones inherited from whereabouts-api.
 *
 * Callers get one endpoint and do not have to know which side a movement came from. That is the
 * point: hmpps-prisoner-profile currently makes two calls per row of the location history page -
 * whereabouts for a case note id, then case-notes for its text - and the migration would otherwise
 * mean it needed a third path for old data.
 *
 * The two sources are checked in that order, and the order matters. A movement this service
 * recorded holds the explanation itself, so it answers in one hop with nothing downstream. Only a
 * migrated movement needs the case note read, because whereabouts stored the link and not the text.
 */
@Service
@Transactional(readOnly = true)
class CellMovementReasonService(
  private val cellMovementRepository: CellMovementRepository,
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val caseNotesApiClient: CaseNotesApiClient,
) {

  fun findByBedAssignment(bookingId: Long, bedAssignmentSequence: Int): CellMovementReason {
    cellMovementRepository
      .findFirstByBookingIdAndBedAssignmentSequenceOrderByOccurredAtDesc(bookingId, bedAssignmentSequence)
      ?.let { return it.toReason() }

    cellMovementNomisRepository
      .findById(CellMovementNomisId(bookingId, bedAssignmentSequence))
      .getOrNull()
      ?.let { return it.toReason() }

    throw CellMovementReasonNotFoundException(bookingId, bedAssignmentSequence)
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

  /**
   * A migrated row is three numbers. The explanation, the reason code and the case note's UUID all
   * have to come from the case note itself, which needs a prisoner number the source table never
   * held - so this is a two-step resolve, and either step can legitimately come back empty.
   *
   * When it does, the movement is still returned, with [CellMovementReason.caseNoteLegacyId] set
   * and the resolved fields null. That is the honest answer and it matches what consumers do today:
   * hmpps-prisoner-profile already tolerates a missing case note and renders no "what happened"
   * text, so a null here is a case it handles rather than a regression. Failing the whole read
   * instead would lose the one fact we are certain of.
   */
  private fun CellMovementNomisEntity.toReason(): CellMovementReason {
    val prisonerNumber = prisonerSearchClient.getPrisonerByBookingId(bookingId)?.prisonerNumber
    val caseNote = prisonerNumber?.let { resolveCaseNote(it, caseNoteLegacyId) }

    if (prisonerNumber == null) {
      log.info(
        "No current prisoner for booking {} - serving migrated movement {} without its explanation",
        bookingId,
        caseNoteLegacyId,
      )
    }

    return CellMovementReason(
      bookingId = bookingId,
      bedAssignmentSequence = bedAssignmentSequence,
      source = CellMovementSource.MIGRATED_FROM_WHEREABOUTS,
      prisonerNumber = prisonerNumber,
      // MOVED_CELL case notes carry the CHG_HOUS_RSN code as their subType, which is the only place
      // the reason code survives - CELL_MOVE_REASON had no column for it.
      reasonCode = caseNote?.subType,
      commentText = caseNote?.text,
      caseNoteUuid = caseNote?.caseNoteId,
      caseNoteLegacyId = caseNoteLegacyId,
      // Not held. NOMIS has the timestamp on the bed assignment itself, which is where the caller
      // got the sequence from, so inventing one here would add nothing and could disagree with it.
      occurredAt = null,
      recordedBy = null,
      // Unknowable. Whereabouts never performed a cell swap, so in practice every migrated row is a
      // cell move - but "in practice" is not something to assert on a prisoner's record.
      movementType = null,
    )
  }

  /**
   * Best effort by design. A migrated link can point at a case note that has since been deleted,
   * and case-notes is a service that can be down; neither should turn a read of data we hold into
   * a 500 when we can still answer with the identifier.
   */
  private fun resolveCaseNote(prisonerNumber: String, caseNoteLegacyId: Long): CaseNote? = try {
    caseNotesApiClient.getCaseNote(prisonerNumber, caseNoteLegacyId.toString())
  } catch (e: Exception) {
    log.warn("Could not read case note {} for {}: {}", caseNoteLegacyId, prisonerNumber, e.message)
    null
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
