package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import com.microsoft.applicationinsights.TelemetryClient
import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentNomisMapping
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentSyncResponse
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.NomisBedAssignment
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import java.time.Clock
import java.time.LocalDateTime

/**
 * Receives a prisoner's bed assignment history from NOMIS, via hmpps-prisoner-from-nomis-migration, and
 * makes DPS hold exactly that (MAPA-481).
 *
 * The migration and the sync both send every row NOMIS holds for the prisoner, not a change. NOMIS
 * events say that something happened to a prisoner, not what (MAPA-291 paper section 7.1), so the sync
 * re-reads the prisoner and sends the lot. That makes a repeated or out-of-order call harmless: each
 * one leaves DPS matching NOMIS as it was when that call read it.
 *
 * Rows are matched on the NOMIS key in bed_assignment_nomis and updated in place, so an assignment keeps
 * its DPS id, and the NOMIS mapping stays valid, however often the prisoner is synchronised.
 */
@Service
@Transactional
class BedAssignmentSyncService(
  private val bedAssignmentRepository: BedAssignmentRepository,
  private val bedAssignmentNomisRepository: BedAssignmentNomisRepository,
  private val cellMovementRepository: CellMovementRepository,
  private val telemetryClient: TelemetryClient,
  private val clock: Clock,
) {

  fun migrate(prisonerNumber: String, assignments: List<NomisBedAssignment>): List<BedAssignmentNomisMapping> = replace(prisonerNumber, assignments)
    .also { track("bed-assignments-migrated", prisonerNumber, it) }
    .let { it.created + it.updated }

  fun sync(prisonerNumber: String, assignments: List<NomisBedAssignment>): BedAssignmentSyncResponse = replace(prisonerNumber, assignments)
    .also { track("bed-assignments-synchronised", prisonerNumber, it) }

  private fun replace(prisonerNumber: String, assignments: List<NomisBedAssignment>): BedAssignmentSyncResponse {
    assignments.groupBy { it.bookingId to it.sequence }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { (bookingId, sequence) ->
      throw ValidationException("Bed assignment for booking $bookingId and sequence $sequence was sent more than once")
    }

    val bookingIds = assignments.map { it.bookingId }.distinct()
    val held = (
      bedAssignmentNomisRepository.findAllByBedAssignmentPrisonerNumber(prisonerNumber) +
        if (bookingIds.isEmpty()) emptyList() else bedAssignmentNomisRepository.findAllByNomisBookingIdIn(bookingIds)
      ).associateBy { it.nomisBookingId to it.nomisSequence }

    val created = mutableListOf<BedAssignmentNomisMapping>()
    val updated = mutableListOf<BedAssignmentNomisMapping>()
    assignments.forEach { assignment ->
      val existing = held[assignment.bookingId to assignment.sequence]
      if (existing == null) {
        created += create(prisonerNumber, assignment)
      } else {
        update(existing, prisonerNumber, assignment)
        updated += existing.toMapping()
      }
    }

    // Anything else held against the prisoner, or for one of the bookings sent, is no longer in NOMIS
    // there: NOMIS has deleted it, or moved the booking to another prisoner, whose own sync recreates it.
    val sent = assignments.map { it.bookingId to it.sequence }.toSet()
    val deleted = held.filterKeys { it !in sent }.values.map { stale ->
      stale.toMapping().also {
        bedAssignmentNomisRepository.delete(stale)
        bedAssignmentRepository.deleteById(it.id)
      }
    }

    return BedAssignmentSyncResponse(created = created, updated = updated, deleted = deleted)
  }

  private fun create(prisonerNumber: String, assignment: NomisBedAssignment): BedAssignmentNomisMapping {
    val saved = bedAssignmentRepository.save(
      BedAssignmentEntity(
        prisonerNumber = prisonerNumber,
        locationId = assignment.locationId,
        locationKey = assignment.locationKey(),
        startAt = assignment.assignmentDateTime,
        startReason = assignment.assignmentReasonCode,
        endAt = assignment.assignmentEndDateTime,
        source = BedAssignmentSource.NOMIS,
        recordedBy = assignment.createdBy,
        cellMovementId = assignment.cellMovementId(),
      ),
    )
    bedAssignmentNomisRepository.save(
      BedAssignmentNomisEntity(
        bedAssignment = saved,
        nomisBookingId = assignment.bookingId,
        nomisSequence = assignment.sequence,
        livingUnitId = assignment.livingUnitId,
        livingUnitDescription = assignment.livingUnitDescription,
        migratedAt = LocalDateTime.now(clock),
      ),
    )
    return BedAssignmentNomisMapping(id = saved.id!!, bookingId = assignment.bookingId, sequence = assignment.sequence)
  }

  private fun update(existing: BedAssignmentNomisEntity, prisonerNumber: String, assignment: NomisBedAssignment) {
    with(existing.bedAssignment) {
      this.prisonerNumber = prisonerNumber
      locationId = assignment.locationId
      locationKey = assignment.locationKey()
      startAt = assignment.assignmentDateTime
      startReason = assignment.assignmentReasonCode
      endAt = assignment.assignmentEndDateTime
      recordedBy = assignment.createdBy
      cellMovementId = cellMovementId ?: assignment.cellMovementId()
    }
    existing.livingUnitId = assignment.livingUnitId
    existing.livingUnitDescription = assignment.livingUnitDescription
  }

  // The NOMIS description is the {prisonId}-{pathHierarchy} key. NOMIS keeps the row even where it no longer
  // holds the location, and then only the living unit id is left to identify it.
  private fun NomisBedAssignment.locationKey() = livingUnitDescription ?: livingUnitId.toString()

  // A move this service made through prison-api comes back from NOMIS as an ordinary row. Linking it
  // here records which assignments DPS opened, ahead of DPS writing them directly.
  private fun NomisBedAssignment.cellMovementId() = cellMovementRepository.findFirstByBookingIdAndBedAssignmentSequenceOrderByOccurredAtDesc(bookingId, sequence)?.id

  private fun BedAssignmentNomisEntity.toMapping() = BedAssignmentNomisMapping(id = bedAssignmentId!!, bookingId = nomisBookingId, sequence = nomisSequence)

  private fun track(event: String, prisonerNumber: String, result: BedAssignmentSyncResponse) {
    log.info(
      "{} for {}: {} created, {} updated, {} deleted",
      event,
      prisonerNumber,
      result.created.size,
      result.updated.size,
      result.deleted.size,
    )
    telemetryClient.trackEvent(
      event,
      mapOf(
        "prisonerNumber" to prisonerNumber,
        "created" to result.created.size.toString(),
        "updated" to result.updated.size.toString(),
        "deleted" to result.deleted.size.toString(),
      ),
      null,
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
