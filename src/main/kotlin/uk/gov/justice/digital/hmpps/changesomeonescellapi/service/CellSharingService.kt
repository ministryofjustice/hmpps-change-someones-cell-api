package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.BedAssignment
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.LocationsInsidePrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellShare
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellSharingHistory
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.Cellmate
import java.time.Clock
import java.time.LocalDateTime

/**
 * Who a prisoner shared a cell with over a period (MAPA-477). Built for the CSRA, which must show
 * who someone rated high risk - specific (provisional) shared with while that rating stood.
 *
 * For now this is answered from NOMIS bed assignment history through prison-api: the prisoner's
 * own stays across all bookings, then, for each stay, everyone else in that cell during the part of
 * it inside the period. Our own movement records are not used - they only hold moves made through
 * DPS, so they would miss cellmates. The response carries no NOMIS identifiers beyond the transitional
 * location id the prisoner profile link still needs, so the source can change without callers noticing.
 *
 * The per-stay calls are made one at a time, not fanned out: a provisional period is normally a
 * few days and a handful of stays, and a long period must not turn into a burst on prison-api
 * (MAPA-443).
 */
@Service
class CellSharingService(
  private val prisonApiClient: PrisonApiClient,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val locationsInsidePrisonApiClient: LocationsInsidePrisonApiClient,
  private val clock: Clock,
) {

  fun getCellSharing(prisonerNumber: String, from: LocalDateTime, to: LocalDateTime?): CellSharingHistory {
    val until = to ?: LocalDateTime.now(clock)
    if (from.isAfter(until)) throw ValidationException("from must not be after to")

    val stays = prisonApiClient.getCellHistory(prisonerNumber, from, until).filter { it.isCell() }
    val ownBookings = stays.map { it.bookingId }.toSet()

    val shares = stays.mapNotNull { stay ->
      val sharedWith = cellmates(stay, ownBookings, prisonerNumber, from, to, until)
      if (sharedWith.isEmpty()) null else stay to sharedWith
    }

    val names = prisonerSearchClient
      .findPrisoners(shares.flatMap { (_, sharedWith) -> sharedWith.map { it.prisonerNumber } })
      .associateBy { it.prisonerNumber }
    val locations = locationsInsidePrisonApiClient.getLocationsByKeys(shares.map { (stay, _) -> stay.description!! })

    return CellSharingHistory(
      prisonerNumber = prisonerNumber,
      from = from,
      to = until,
      cells = shares
        .map { (stay, sharedWith) ->
          val key = stay.description!!
          val location = locations[key]
          CellShare(
            prisonId = stay.agencyId!!,
            locationKey = key,
            pathHierarchy = location?.pathHierarchy ?: key.removePrefix("${stay.agencyId}-"),
            dpsLocationId = location?.id,
            nomisLocationId = stay.livingUnitId,
            movedIn = stay.assignmentDateTime,
            movedOut = stay.assignmentEndDateTime,
            sharedWith = sharedWith.map {
              val prisoner = names[it.prisonerNumber]
              it.copy(firstName = prisoner?.firstName, lastName = prisoner?.lastName)
            },
          )
        }
        .sortedByDescending { it.movedIn },
    )
  }

  /**
   * Everyone else in the cell during the part of [stay] inside the period, one entry per person.
   *
   * Overlap is strict: someone who moved in at the same minute the prisoner moved out did not share.
   * The prisoner's own records are excluded by booking as well as by number, so a stay on an earlier
   * booking, or under a number since merged, is never reported as a cellmate.
   */
  private fun cellmates(
    stay: BedAssignment,
    ownBookings: Set<Long>,
    prisonerNumber: String,
    from: LocalDateTime,
    to: LocalDateTime?,
    until: LocalDateTime,
  ): List<Cellmate> {
    val windowStart = maxOf(stay.assignmentDateTime, from)
    val windowEnd = minOf(stay.assignmentEndDateTime ?: until, until)
    if (!windowStart.isBefore(windowEnd)) return emptyList()

    return prisonApiClient.getCellOccupancy(stay.livingUnitId, windowStart, windowEnd)
      .filterNot { it.bookingId in ownBookings || it.offenderNo == prisonerNumber || it.offenderNo == null }
      .mapNotNull { other ->
        val sharedFrom = maxOf(windowStart, other.assignmentDateTime)
        // Null while both are still in the cell and the caller gave no end to the period.
        val sharedTo = listOfNotNull(stay.assignmentEndDateTime, other.assignmentEndDateTime, to).minOrNull()
        if (sharedFrom.isBefore(sharedTo ?: until)) Cellmate(other.offenderNo!!, null, null, sharedFrom, sharedTo) else null
      }
      .groupBy { it.prisonerNumber }
      .map { (_, periods) ->
        periods.first().copy(
          sharedFrom = periods.minOf { it.sharedFrom },
          sharedTo = if (periods.any { it.sharedTo == null }) null else periods.maxOf { it.sharedTo!! },
        )
      }
      .sortedBy { it.sharedFrom }
  }

  /**
   * Reception, court, TAP and cell swap are bed assignments in NOMIS but not cells: many prisoners
   * pass through them together and none of that is sharing. They are top-level locations, so the
   * key is the prison followed by the code. A stay whose location NOMIS cannot describe is dropped
   * with a warning, as there is nothing to show for it.
   */
  private fun BedAssignment.isCell(): Boolean {
    if (description == null || agencyId == null) {
      log.warn("Ignoring bed assignment on booking {} in living unit {} with no location key", bookingId, livingUnitId)
      return false
    }
    return description.removePrefix("$agencyId-") !in NOT_CELLS
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val NOT_CELLS = setOf("RECP", "COURT", "TAP", "CSWAP")
  }
}
