package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellNotAvailableException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellSwapUnavailableException
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.PrisonerRecordLockedException

/**
 * Performs the actual cell move in NOMIS.
 *
 * The endpoint is `@ProxyUser`, so the username carried on our token is what NOMIS records in its
 * audit columns - see WebClientConfiguration for how that gets there.
 */
@Component
class PrisonApiClient(
  @param:Qualifier("prisonApiWebClient") private val webClient: WebClient,
) {
  /**
   * Moves the booking to [locationKey], which is the full location key such as MDI-1-1-015 -
   * prison-api matches it against the NOMIS internal location description, which is the same
   * string LIP calls a key.
   *
   * There is deliberately **no retry**. whereabouts-api applied `.retry(3)` to this call, which
   * retries a non-idempotent write on any error including 4xx, and could move a prisoner more
   * than once.
   *
   * `lockTimeout=true` asks NOMIS to take a row lock and give up after 10 seconds rather than
   * blocking, which is what turns "someone has this prisoner open in P-NOMIS" into a 423 we can
   * show the user.
   */
  fun moveToCell(bookingId: Long, locationKey: String, reasonCode: String): CellMoveResult = translatingErrors(
    // The cell is full, inactive, not a cell or reception, or in another prison. prison-api reports
    // all of these as one 400, so its message is passed through rather than guessed at.
    rejectedBy = { CellNotAvailableException(it) },
  ) {
    webClient
      .put()
      .uri(
        "/api/bookings/{bookingId}/living-unit/{locationKey}?reasonCode={reasonCode}&lockTimeout=true",
        mapOf("bookingId" to bookingId, "locationKey" to locationKey, "reasonCode" to reasonCode),
      )
      .retrieve()
      .bodyToMono<CellMoveResult>()
      .block()!!
  }

  /**
   * Moves the booking out to the prison's cell swap location, freeing the cell.
   *
   * Takes no location on purpose: prison-api resolves CSWAP from the booking's own agency. That is
   * a fact about this endpoint, not about our service, so it stays in here.
   *
   * **This calls an endpoint prison-api has marked `@Deprecated`**, documented "this endpoint will
   * be removed in future releases". Containing it in this method is the point — when it goes, the
   * replacement is to call [moveToCell] with the prison's `{prisonId}-CSWAP` key, which the service
   * already derives and stores, and nothing above this class changes.
   *
   * Syscon cannot remove it yet: `MovementUpdateService.moveToCellOrReception` gates on
   * `isActiveCellWithSpace() || isActiveReceptionWithSpace()` and CSWAP is a WING, so the ordinary
   * endpoint rejects it with a 400 before reaching `BookingService.validateUpdateLivingUnit` —
   * which already has an `isCellSwap()` exemption waiting for it. That outer gate has to move first.
   *
   * [reasonCode] is sent explicitly rather than relying on the endpoint's `ADM` default, so that
   * what NOMIS records and what we record cannot drift apart, and so the eventual switch to
   * [moveToCell] — where the reason is required — is a no-op.
   *
   * No retry, for the same non-idempotency reason as [moveToCell]. No `dateTime` either: NOMIS
   * clocks it, and sending ours would invite skew into the bed assignment history.
   */
  fun moveToCellSwap(bookingId: Long, reasonCode: String): CellMoveResult = translatingErrors(
    // Not CellNotAvailable: there is no destination cell here and no capacity check — CSWAP is
    // deliberately uncapped. A 400 or 404 means the prison has no CSWAP location configured, or
    // more than one. That is an estate configuration fault, not something the user can fix by
    // picking a different cell.
    rejectedBy = { CellSwapUnavailableException(it) },
  ) {
    webClient
      .put()
      .uri("/api/bookings/{bookingId}/move-to-cell-swap", mapOf("bookingId" to bookingId))
      .bodyValue(mapOf("reasonCode" to reasonCode))
      .retrieve()
      .bodyToMono<CellMoveResult>()
      .block()!!
  }

  /**
   * Resolves a booking - including a historic one prisoner-search no longer indexes - to its
   * prisoner. **Backfill only**: the read path deliberately never calls this, so a NOMIS read
   * stays out of the serving path (see CellMovementNomisEnricher). Anyone released and recalled
   * since their move has a new current booking, and only NOMIS still knows who the old one
   * belonged to.
   *
   * Requires ROLE_VIEW_PRISONER_DATA (prison-api's `@VerifyBookingAccess` also accepts
   * ROLE_GLOBAL_SEARCH). 404 means NOMIS itself has no such booking - the genuine not-found.
   */
  fun getBooking(bookingId: Long): OffenderBooking? = try {
    webClient
      .get()
      .uri("/api/bookings/{bookingId}?basicInfo=true", mapOf("bookingId" to bookingId))
      .retrieve()
      .bodyToMono<OffenderBooking>()
      .block()
  } catch (e: WebClientResponseException) {
    if (e.statusCode == HttpStatus.NOT_FOUND) null else throw e
  }

  /**
   * Shared so the two calls cannot drift. 423 is mapped for both even though prison-api hardcodes
   * `lockTimeout=false` on the swap endpoint and so cannot currently return it there — it costs
   * nothing and starts working the day the swap moves onto [moveToCell]. Note the flip side: with
   * no lock timeout, a record open in P-NOMIS blocks rather than returning 423, so the failure mode
   * on a swap today is latency, not a clean error.
   */
  private fun <T> translatingErrors(rejectedBy: (String?) -> Exception, block: () -> T): T = try {
    block()
  } catch (e: WebClientResponseException) {
    when (e.statusCode) {
      HttpStatus.LOCKED -> throw PrisonerRecordLockedException()
      HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND -> throw rejectedBy(e.responseBodyAsString)
      else -> throw e
    }
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class OffenderBooking(
  val bookingId: Long,
  val offenderNo: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CellMoveResult(
  val bookingId: Long,
  val agencyId: String? = null,
  val assignedLivingUnitId: Long? = null,
  val assignedLivingUnitDesc: String? = null,
  /**
   * Null when the prisoner was already in the destination cell - prison-api treats that as a
   * successful no-op rather than an error.
   */
  val bedAssignmentHistorySequence: Int? = null,
)
