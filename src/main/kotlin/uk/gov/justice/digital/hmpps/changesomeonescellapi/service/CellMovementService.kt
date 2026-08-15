package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.CaseNotesApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.DuplicateCellMovementException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerNotFoundException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerNotInPrisonException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovement
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import uk.gov.justice.hmpps.kotlin.auth.HmppsAuthenticationHolder
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Orchestrates a cell move: the NOMIS move, the MOVED_CELL case note, and our own record of why it
 * happened. Replaces whereabouts-api's CellMoveService.
 *
 * Deliberately **not** `@Transactional`. Whereabouts wrapped the whole thing in a transaction, but
 * two of the three steps are remote HTTP calls that a rollback cannot undo - so all the annotation
 * ever achieved was discarding the local record of a move that had already happened in NOMIS.
 * Instead each step commits as it completes, so whatever did happen is on record.
 *
 * The ordering is what makes the comment survivable. It is written before the move is attempted,
 * so a failure anywhere downstream still leaves the text we need to recreate the case note.
 */
@Service
class CellMovementService(
  private val cellMovementRepository: CellMovementRepository,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonApiClient: PrisonApiClient,
  private val caseNotesApiClient: CaseNotesApiClient,
  private val authenticationHolder: HmppsAuthenticationHolder,
  private val clock: Clock,
) {
  private val username: String get() = authenticationHolder.username ?: SYSTEM_USERNAME

  fun move(request: CellMovementRequest): CellMovement {
    val occurredAt = LocalDateTime.now(clock)

    val prisoner = prisonerSearchClient.getPrisoner(request.prisonerNumber)
      ?: throw PrisonerNotFoundException(request.prisonerNumber)

    // A prisoner cannot be inside without a booking, so this only fires for someone released or in
    // transit - who cannot be moved anyway. Rejecting here beats letting prison-api fail on a
    // booking id we invented.
    if (!prisoner.isInPrison() || prisoner.bookingId == null) {
      throw PrisonerNotInPrisonException(request.prisonerNumber)
    }

    rejectDuplicate(request, occurredAt)

    val movement = cellMovementRepository.saveAndFlush(
      CellMovementEntity(
        prisonerNumber = prisoner.prisonerNumber,
        bookingId = prisoner.bookingId.toLong(),
        fromLocationKey = prisoner.locationKey(),
        toLocationKey = request.toLocationKey,
        reasonCode = request.reasonCode,
        commentText = request.commentText,
        occurredAt = occurredAt,
        recordedBy = username,
        status = CellMovementStatus.PENDING,
      ),
    )

    val result = try {
      prisonApiClient.moveToCell(
        bookingId = movement.bookingId,
        locationKey = request.toLocationKey,
        reasonCode = request.reasonCode,
      )
    } catch (e: Exception) {
      // Leave the row PENDING: it is the record that we tried and NOMIS did not accept it.
      log.warn("Cell move failed in NOMIS for {}: {}", request.prisonerNumber, e.message)
      throw e
    }

    // Null when the prisoner was already in that cell, which prison-api treats as a successful
    // no-op rather than an error. The move is still COMPLETED - there is simply no new assignment.
    movement.bedAssignmentSequence = result.bedAssignmentHistorySequence
    movement.status = CellMovementStatus.COMPLETED
    cellMovementRepository.saveAndFlush(movement)

    createCaseNote(movement)

    return movement.toDto()
  }

  private fun rejectDuplicate(request: CellMovementRequest, occurredAt: LocalDateTime) {
    val duplicate = cellMovementRepository.existsByPrisonerNumberAndToLocationKeyAndOccurredAtAfter(
      request.prisonerNumber,
      request.toLocationKey,
      occurredAt.minus(DUPLICATE_WINDOW),
    )
    if (duplicate) {
      throw DuplicateCellMovementException(request.prisonerNumber, request.toLocationKey)
    }
  }

  /**
   * The move has already happened by this point, so a failure here must not fail the request. The
   * comment text is safely stored, so the case note can be recreated later from the row.
   */
  private fun createCaseNote(movement: CellMovementEntity) {
    try {
      val caseNote = caseNotesApiClient.createCellMoveCaseNote(
        prisonerNumber = movement.prisonerNumber,
        reasonCode = movement.reasonCode,
        text = movement.commentText,
        occurredAt = movement.occurredAt,
        username = movement.recordedBy,
      )
      movement.caseNoteUuid = caseNote.caseNoteId
    } catch (e: Exception) {
      log.error("Cell move for {} succeeded but the case note failed", movement.prisonerNumber, e)
      movement.status = CellMovementStatus.CASE_NOTE_FAILED
    }
    cellMovementRepository.saveAndFlush(movement)
  }

  private fun CellMovementEntity.toDto() = CellMovement(
    id = id!!,
    prisonerNumber = prisonerNumber,
    fromLocationKey = fromLocationKey,
    toLocationKey = toLocationKey,
    reasonCode = reasonCode,
    occurredAt = occurredAt,
    recordedBy = recordedBy,
    caseNoteUuid = caseNoteUuid,
    status = status,
  )

  private companion object {
    // Long enough to catch a double submit, short enough not to block a prisoner legitimately
    // being moved back to a cell later in the day.
    private val DUPLICATE_WINDOW: Duration = Duration.ofSeconds(60)
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
