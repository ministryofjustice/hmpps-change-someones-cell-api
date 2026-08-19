package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId

@Repository
interface CellMovementNomisRepository : JpaRepository<CellMovementNomisEntity, CellMovementNomisId> {

  /**
   * The backfill's insert. DO NOTHING rather than DO UPDATE, always: a row already present may
   * carry enrichment the read path persisted, which the sweep must never clobber - and the link
   * fields it would "update" are identical anyway, being the same immutable source row.
   * Returns 1 when the link was inserted, 0 when it was already there.
   *
   * `@Transactional` on the method so each link commits alone: a sweep killed mid-page loses
   * nothing already reported, and a re-run from the same cursor skips what landed.
   */
  @Modifying
  @Transactional
  @Query(
    value = """
      INSERT INTO cell_movement_nomis (booking_id, bed_assignment_sequence, case_note_legacy_id)
      VALUES (:bookingId, :bedAssignmentSequence, :caseNoteLegacyId)
      ON CONFLICT (booking_id, bed_assignment_sequence) DO NOTHING
    """,
    nativeQuery = true,
  )
  fun insertLinkIfAbsent(bookingId: Long, bedAssignmentSequence: Int, caseNoteLegacyId: Long): Int

  /**
   * One keyset batch of rows still awaiting enrichment, strictly after the cursor in primary key
   * order. Native because JPQL has no row-value comparison. A full scan behind the LIMIT is
   * acceptable for a one-off migration; no index is added for it.
   */
  @Query(
    value = """
      SELECT * FROM cell_movement_nomis
      WHERE enriched_at IS NULL
        AND (booking_id, bed_assignment_sequence) > (:lastBookingId, :lastBedAssignmentSequence)
      ORDER BY booking_id, bed_assignment_sequence
      LIMIT :batchSize
    """,
    nativeQuery = true,
  )
  fun findUnenrichedAfter(lastBookingId: Long, lastBedAssignmentSequence: Int, batchSize: Int): List<CellMovementNomisEntity>

  // Reconciliation counts. Together with count() they bucket every row exactly once:
  // enriched-with-note + enriched-note-gone + unenriched == total.
  fun countByEnrichedAtIsNotNullAndCaseNoteUuidIsNotNull(): Long

  fun countByEnrichedAtIsNotNullAndCaseNoteUuidIsNull(): Long

  fun countByEnrichedAtIsNull(): Long

  /** The rows no source could put a prisoner number to yet - the backfill's unresolved list. */
  fun countByEnrichedAtIsNullAndPrisonerNumberIsNull(): Long

  fun findTop50ByEnrichedAtIsNullAndPrisonerNumberIsNullOrderByBookingIdAscBedAssignmentSequenceAsc(): List<CellMovementNomisEntity>
}
