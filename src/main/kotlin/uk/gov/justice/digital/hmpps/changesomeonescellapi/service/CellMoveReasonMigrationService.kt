package uk.gov.justice.digital.hmpps.changesomeonescellapi.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.PrisonerSearchClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.client.WhereaboutsApiClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.EnrichResult
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.LinkSweepResult
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationCursor
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.MigrationStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository

/**
 * The one-off backfill (MAPA-304): sweeps whereabouts-api's CELL_MOVE_REASON table into
 * [cell_movement_nomis][CellMovementNomisEntity] and enriches every row, so that once the counts
 * reconcile no read ever touches whereabouts again and its table can be dropped.
 *
 * Two separately-resumable passes, each driven chunk by chunk from MigrationResource by an
 * operator - the curl loop is the scheduler and the rate limiter, which is also why everything
 * here runs synchronously on the request thread (the downstream WebClients are request-scoped and
 * cannot be used from a background job).
 *
 * Not `@Transactional`: each row's write commits alone (the insert via the repository's own
 * per-row transaction, the enrichment via saveAndFlush), so a call cut off by a timeout loses
 * nothing and a re-run from the same cursor is harmless.
 *
 * Two races are accepted by design:
 *  - a row the read path enriches between our select and our save is written twice with the same
 *    values - both writers derive the same facts from the same case note, so last-writer-wins is
 *    benign;
 *  - a row whereabouts gains *below* the sweep cursor mid-run would be missed by the sweep, but
 *    live moves migrate themselves through the read path, and since MAPA-280 nothing writes to
 *    whereabouts at all - the sweep runs against a frozen table.
 */
@Service
class CellMoveReasonMigrationService(
  private val cellMovementNomisRepository: CellMovementNomisRepository,
  private val whereaboutsApiClient: WhereaboutsApiClient,
  private val prisonerSearchClient: PrisonerSearchClient,
  private val prisonApiClient: PrisonApiClient,
  private val enricher: CellMovementNomisEnricher,
) {

  /**
   * Pass 1: copy the links. Walks up to [maxPages] pages of whereabouts' keyset export and
   * inserts each row's link, skipping - never overwriting - rows already migrated. An empty page
   * is the export's only terminator; a short page is treated as complete too, which saves the
   * final call without changing correctness (a re-run confirms with the empty page).
   */
  fun sweepLinks(cursor: MigrationCursor, pageSize: Int, maxPages: Int): LinkSweepResult {
    var lastBookingId = cursor.lastBookingId
    var lastBedAssignmentSequence = cursor.lastBedAssignmentSequence
    var pagesFetched = 0
    var rowsSeen = 0
    var rowsInserted = 0
    var complete = false

    while (pagesFetched < maxPages) {
      val page = whereaboutsApiClient.getCellMoveReasons(lastBookingId, lastBedAssignmentSequence, pageSize)
      pagesFetched++
      if (page.isEmpty()) {
        complete = true
        break
      }

      rowsSeen += page.size
      rowsInserted += page.sumOf {
        cellMovementNomisRepository.insertLinkIfAbsent(it.bookingId, it.bedAssignmentsSequence, it.caseNoteId)
      }
      page.last().let {
        lastBookingId = it.bookingId
        lastBedAssignmentSequence = it.bedAssignmentsSequence
      }
      log.info(
        "Link sweep page {}: {} rows, {} new, cursor now ({}, {})",
        pagesFetched,
        page.size,
        rowsInserted,
        lastBookingId,
        lastBedAssignmentSequence,
      )

      if (page.size < pageSize) {
        complete = true
        break
      }
    }

    return LinkSweepResult(
      pagesFetched = pagesFetched,
      rowsSeen = rowsSeen,
      rowsInserted = rowsInserted,
      nextCursor = MigrationCursor(lastBookingId, lastBedAssignmentSequence),
      complete = complete,
    )
  }

  /**
   * Pass 2: enrich a batch of rows still awaiting enrichment, in primary key order after the
   * cursor. Prisoner numbers are resolved with one batched prisoner-search call, then - only for
   * bookings the index no longer knows - prison-api's booking lookup, the one-off concession the
   * read path deliberately never makes. A booking unknown to both sources is reported in
   * [EnrichResult.unresolvedBookingIds] and its row left untouched: listed, not silent, and
   * retried by any later pass.
   */
  fun enrich(cursor: MigrationCursor, batchSize: Int): EnrichResult {
    val rows = cellMovementNomisRepository.findUnenrichedAfter(
      cursor.lastBookingId,
      cursor.lastBedAssignmentSequence,
      batchSize,
    )
    if (rows.isEmpty()) {
      return EnrichResult(0, 0, 0, 0, emptyList(), cursor, complete = true)
    }

    val searched: Map<Long, String> = prisonerSearchClient
      .getPrisonersByBookingIds(rows.mapTo(mutableSetOf()) { it.bookingId })
      .mapNotNull { p -> p.bookingId?.toLongOrNull()?.let { it to p.prisonerNumber } }
      .toMap()
    // Several sequences can share a booking; ask prison-api about each unknown booking once.
    val fallback = mutableMapOf<Long, String?>()
    fun resolve(bookingId: Long): String? = searched[bookingId]
      ?: fallback.getOrPut(bookingId) { prisonApiClient.getBooking(bookingId)?.offenderNo }

    var enriched = 0
    var noteGone = 0
    var failed = 0
    val unresolved = mutableListOf<Long>()

    rows.forEach { row ->
      val prisonerNumber = row.prisonerNumber ?: resolve(row.bookingId)
      if (prisonerNumber == null) {
        unresolved += row.bookingId
        return@forEach
      }
      try {
        val saved = enricher.enrichWithPrisonerNumber(row, prisonerNumber)
        when {
          saved.enrichedAt == null -> failed++ // transient case-notes failure, already logged
          saved.caseNoteUuid != null -> enriched++
          else -> noteGone++
        }
      } catch (e: Exception) {
        // A pathological row must not abort the batch - count it, log its key, move on.
        failed++
        log.error("Enrichment failed for booking {} sequence {}: {}", row.bookingId, row.bedAssignmentSequence, e.message)
      }
    }

    val last = rows.last()
    log.info(
      "Enrich batch: {} attempted, {} enriched, {} note gone, {} failed, {} unresolved, cursor now ({}, {})",
      rows.size,
      enriched,
      noteGone,
      failed,
      unresolved.distinct().size,
      last.bookingId,
      last.bedAssignmentSequence,
    )

    return EnrichResult(
      attempted = rows.size,
      enriched = enriched,
      noteGone = noteGone,
      failed = failed,
      unresolvedBookingIds = unresolved.distinct(),
      nextCursor = MigrationCursor(last.bookingId, last.bedAssignmentSequence),
      complete = rows.size < batchSize,
    )
  }

  fun status(): MigrationStatus {
    val enrichedWithNote = cellMovementNomisRepository.countByEnrichedAtIsNotNullAndCaseNoteUuidIsNotNull()
    val enrichedNoteGone = cellMovementNomisRepository.countByEnrichedAtIsNotNullAndCaseNoteUuidIsNull()
    return MigrationStatus(
      totalRows = cellMovementNomisRepository.count(),
      enriched = enrichedWithNote + enrichedNoteGone,
      enrichedWithNote = enrichedWithNote,
      enrichedNoteGone = enrichedNoteGone,
      unenriched = cellMovementNomisRepository.countByEnrichedAtIsNull(),
      awaitingPrisonerNumber = cellMovementNomisRepository.countByEnrichedAtIsNullAndPrisonerNumberIsNull(),
      sampleUnresolvedBookingIds = cellMovementNomisRepository
        .findTop50ByEnrichedAtIsNullAndPrisonerNumberIsNullOrderByBookingIdAscBedAssignmentSequenceAsc()
        .map { it.bookingId }
        .distinct(),
    )
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}
