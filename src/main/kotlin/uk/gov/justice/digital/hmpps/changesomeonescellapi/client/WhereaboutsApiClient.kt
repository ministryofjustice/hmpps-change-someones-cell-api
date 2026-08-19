package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Reads a cell move reason link out of whereabouts-api, for a movement that has not been migrated
 * into this service yet.
 *
 * **Transitional by design - this whole class dies with whereabouts.** It exists so that the
 * migration needs no outage: until the one-off backfill has swept CELL_MOVE_REASON across, any
 * movement someone asks about is fetched from whereabouts on first read, persisted here, and never
 * asked for again. Once the backfill has run and the counts reconcile, every read hits our own
 * table, this client has no callers, and it and the whereabouts URL configuration are deleted
 * alongside the whereabouts decommission (MAPA-282).
 *
 * Deliberately **no health ping** for whereabouts: wiring a dying service into /health would fail
 * our deployments the day it is switched off, for a dependency that by then no read even uses.
 */
@Component
class WhereaboutsApiClient(
  @param:Qualifier("whereaboutsApiWebClient") private val webClient: WebClient,
) {
  /** Returns null when whereabouts has no record of this bed assignment - the genuine not-found. */
  fun getCellMoveReason(bookingId: Long, bedAssignmentSequence: Int): WhereaboutsCellMoveReason? = try {
    webClient
      .get()
      .uri(
        "/cell/cell-move-reason/booking/{bookingId}/bed-assignment-sequence/{bedAssignmentSequence}",
        mapOf("bookingId" to bookingId, "bedAssignmentSequence" to bedAssignmentSequence),
      )
      .retrieve()
      .bodyToMono<WhereaboutsCellMoveReasonResponse>()
      .block()
      ?.cellMoveReason
  } catch (e: WebClientResponseException) {
    if (e.statusCode == HttpStatus.NOT_FOUND) null else throw e
  }

  /**
   * One keyset page of the full CELL_MOVE_REASON export, strictly after the cursor, in
   * `(bookingId, bedAssignmentsSequence)` order. The one-off backfill's read - the second and
   * final consumer of this class.
   *
   * An **empty list is the only terminator**: whereabouts sends no hasMore or total, so a page
   * that happens to equal the page size proves nothing. The next cursor is the last element's key.
   * Note the spelling asymmetry, faithfully whereabouts' own: the request parameter is
   * `lastBedAssignmentSequence`, the response field `bedAssignmentsSequence`.
   *
   * No 404 handling - the endpoint returns an empty array rather than 404. A 401 here means the
   * client is missing ROLE_CELL_MOVEMENTS__SYNC__RW (whereabouts maps AccessDenied to 401, not
   * 403) and should propagate loudly rather than read as an empty table.
   */
  fun getCellMoveReasons(
    lastBookingId: Long,
    lastBedAssignmentSequence: Int,
    pageSize: Int,
  ): List<WhereaboutsCellMoveReason> = webClient
    .get()
    .uri {
      it.path("/cell/cell-move-reasons")
        .queryParam("lastBookingId", lastBookingId)
        .queryParam("lastBedAssignmentSequence", lastBedAssignmentSequence)
        .queryParam("pageSize", pageSize)
        .build()
    }
    .retrieve()
    .bodyToMono<WhereaboutsCellMoveReasonsResponse>()
    .block()
    ?.cellMoveReasons
    .orEmpty()
}

/** whereabouts wraps the payload: `{"cellMoveReason": {...}}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WhereaboutsCellMoveReasonResponse(
  val cellMoveReason: WhereaboutsCellMoveReason,
)

/** The export wraps its page the same way: `{"cellMoveReasons": [...]}`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class WhereaboutsCellMoveReasonsResponse(
  val cellMoveReasons: List<WhereaboutsCellMoveReason>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WhereaboutsCellMoveReason(
  val bookingId: Long,
  /** Note the extra `s` - the field is misspelled in whereabouts' own DTO and matched here. */
  val bedAssignmentsSequence: Int,
  val caseNoteId: Long,
)
