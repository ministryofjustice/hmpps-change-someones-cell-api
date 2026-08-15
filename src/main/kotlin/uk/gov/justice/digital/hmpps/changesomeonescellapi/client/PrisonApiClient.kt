package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.CellNotAvailableException
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
  fun moveToCell(bookingId: Long, locationKey: String, reasonCode: String): CellMoveResult = try {
    webClient
      .put()
      .uri(
        "/api/bookings/{bookingId}/living-unit/{locationKey}?reasonCode={reasonCode}&lockTimeout=true",
        mapOf("bookingId" to bookingId, "locationKey" to locationKey, "reasonCode" to reasonCode),
      )
      .retrieve()
      .bodyToMono<CellMoveResult>()
      .block()!!
  } catch (e: WebClientResponseException) {
    // The two statuses whereabouts-api destroyed by mapping everything to a 500. Translated into
    // our own exceptions so the handler can surface them without leaking prison-api's shape.
    when (e.statusCode) {
      HttpStatus.LOCKED -> throw PrisonerRecordLockedException()
      HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND -> throw CellNotAvailableException(e.responseBodyAsString)
      else -> throw e
    }
  }
}

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
