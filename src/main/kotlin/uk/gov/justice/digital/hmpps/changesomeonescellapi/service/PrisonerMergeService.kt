package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository

/**
 * Keeps cell movements with the right prisoner when NOMIS changes which prisoner number a booking
 * belongs to (MAPA-438).
 *
 * Both tables carry the prisoner number - `cell_movement` for the moves this service made, and
 * `cell_movement_nomis` for the reasons migrated from whereabouts. Neither has a unique constraint on
 * it and nothing is derived from it, so both changes are a straight update in place.
 *
 * NOMIS keeps the booking id when it moves a booking between prisoner numbers, so the
 * booking-and-bed-assignment read never notices either change. Anything that asks by prisoner number
 * does - a subject access request, Datahub, a per-prisoner history - which is why the number has to
 * follow the booking.
 *
 * Both are safe to receive twice: the second run finds nothing left to move and records a no-op.
 */
@Service
@Transactional
class PrisonerMergeService(
  private val cellMovementRepository: CellMovementRepository,
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val telemetryClient: TelemetryClient,
) {

  /**
   * NOMIS has merged two prisoner numbers for the same person. The retired number is deleted from
   * NOMIS and never exists again, so everything held under it moves to the retained number.
   */
  fun prisonerMerged(retained: String, removed: String) {
    val movements = cellMovementRepository.reassignPrisonerNumber(removed = removed, retained = retained)
    val migrated = cellMovementNomisRepository.reassignPrisonerNumber(removed = removed, retained = retained)
    val properties = mapOf("NOMS-MERGE-FROM" to removed, "NOMS-MERGE-TO" to retained)
    track(TELEMETRY_MERGE, properties, movements, migrated)
    log.info("Prisoner merge {} -> {} moved {} cell movement(s) and {} migrated reason(s)", removed, retained, movements, migrated)
  }

  /**
   * NOMIS has moved one booking from one prisoner number to another, without merging the two. Only
   * the rows for that booking move; the original prisoner keeps the rest of their history.
   *
   * Matching on the booking alone, rather than on the booking and the number it moved from, means a
   * row already moved by an earlier merge or move still ends up on the booking's current owner.
   */
  fun bookingMoved(bookingId: Long, movedFrom: String, movedTo: String) {
    val movements = cellMovementRepository.reassignBooking(bookingId = bookingId, movedTo = movedTo)
    val migrated = cellMovementNomisRepository.reassignBooking(bookingId = bookingId, movedTo = movedTo)
    val properties = mapOf(
      "bookingId" to bookingId.toString(),
      "movedFromNomsNumber" to movedFrom,
      "movedToNomsNumber" to movedTo,
    )
    track(TELEMETRY_BOOKING_MOVED, properties, movements, migrated)
    log.info("Booking {} moved {} -> {}: moved {} cell movement(s) and {} migrated reason(s)", bookingId, movedFrom, movedTo, movements, migrated)
  }

  /**
   * A no-op is recorded under its own name rather than left silent - most merges and booking moves
   * are of prisoners never moved through DPS, and every redelivery is one too. Without it a no-op is
   * indistinguishable from the handler never running.
   */
  private fun track(name: String, properties: Map<String, String>, movements: Int, migrated: Int) {
    val eventName = if (movements + migrated == 0) "$name-no-op" else name
    telemetryClient.trackEvent(
      eventName,
      properties + mapOf(
        "cellMovementsMoved" to movements.toString(),
        "migratedReasonsMoved" to migrated.toString(),
      ),
      null,
    )
  }

  private companion object {
    private const val TELEMETRY_MERGE = "cell-movement-prisoner-merge"
    private const val TELEMETRY_BOOKING_MOVED = "cell-movement-booking-moved"
    private val log = LoggerFactory.getLogger(PrisonerMergeService::class.java)
  }
}
