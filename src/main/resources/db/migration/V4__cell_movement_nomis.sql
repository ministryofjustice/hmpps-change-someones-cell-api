/*
 * The migrated whereabouts-api CELL_MOVE_REASON rows (MAPA-279), plus what this service has since
 * resolved about them.
 *
 * CELL_MOVE_REASON is the only DPS-owned cell move data in existence. It exists because NOMIS
 * BED_ASSIGNMENT_HISTORIES has nowhere to put a case note reference or a free-text explanation -
 * only a 3-4 character reason code - so whereabouts kept the link from (booking, bed assignment
 * sequence) to a case note id on the side. The table dies with whereabouts, and
 * hmpps-prisoner-profile reads it to render "What happened" on the location history page, so both
 * the data and a read path have to survive.
 *
 * A separate table rather than rows in cell_movement, because these rows are a different thing.
 * The source carries three columns where cell_movement has fourteen, and forcing them into
 * cell_movement would mean dropping most of its NOT NULL constraints, so that the shape of a
 * movement this service records would be dictated by data it did not record. Keeping them apart
 * lets cell_movement stay strict, and makes "is this ours or inherited?" a fact about which table
 * a row is in rather than a guess from which columns are null.
 *
 * The row splits into two halves. The first three columns are the link, copied verbatim from
 * whereabouts - by the one-off backfill, or one row at a time by the read-through that fetches a
 * movement from whereabouts the first time someone asks for it. The rest is the enrichment: what
 * the case note the link points at told us, resolved once and kept, so that serving a migrated
 * movement stops costing a prisoner-search call and a case-notes call on every read. The case note
 * is the only place the reason code, the explanation and the move's timestamp survive - the source
 * table held none of them.
 *
 * The source DDL (whereabouts V12__create_cell_move_reason.sql) is:
 *
 *   CREATE TABLE CELL_MOVE_REASON (
 *     BOOKING_ID BIGINT NOT NULL,
 *     BED_ASSIGNMENT_SEQUENCE BIGINT NOT NULL,
 *     CASE_NOTE_ID INT NOT NULL,
 *     PRIMARY KEY (BOOKING_ID, BED_ASSIGNMENT_SEQUENCE)
 *   );
 *
 * Two deliberate departures from copying that verbatim:
 *
 * The case note column is BIGINT, not INT. The source DDL and the source JPA entity disagree -
 * the column is INT while CellMoveReason.kt maps it to Long - so one of the two is wrong about the
 * range. BIGINT is a superset of both, costs 4 bytes a row on a table of this size, and removes
 * the question. It also matches cell_movement.case_note_legacy_id, which holds the same fact.
 *
 * It is named case_note_legacy_id rather than case_note_id for that same reason. The case notes
 * service is UUID-canonical now and calls this identifier the legacy id; naming it case_note_id
 * here would imply it is the current identifier, and would read as a different fact from the
 * identically-typed column next door in cell_movement. The value is copied unchanged either way.
 *
 * bed_assignment_sequence is INTEGER, matching cell_movement and what prison-api returns as
 * bedAssignmentHistorySequence, rather than the source's BIGINT. A sequence counts cells within
 * one booking; it does not approach 2^31.
 *
 * No source column, no surrogate key. (booking_id, bed_assignment_sequence) is already the
 * natural key and is already unique in the source, so it is the primary key here too - which also
 * makes both the one-off backfill and the read-through idempotent and re-runnable.
 */
CREATE TABLE cell_movement_nomis
(
    booking_id              BIGINT  NOT NULL,
    bed_assignment_sequence INTEGER NOT NULL,
    -- The numeric case note id, as whereabouts recorded it. The explanation of the move lives in
    -- that case note and nowhere else: whereabouts never stored the text, which is exactly the
    -- weakness cell_movement.comment_text fixes for movements recorded from MAPA-278 onwards.
    case_note_legacy_id     BIGINT  NOT NULL,

    -- The enrichment, resolved from the case note. All nullable: a freshly copied link has none of
    -- it yet, and a booking that is no longer the prisoner's current one cannot be resolved through
    -- prisoner-search at read time - the backfill closes those with a one-off prison-api lookup.
    prisoner_number         VARCHAR(7),
    -- The CHG_HOUS_RSN code, surviving only as the case note's subType.
    reason_code             VARCHAR(12),
    comment_text            TEXT,
    -- The case note's canonical UUID, learned when the note is first read. The legacy id above is
    -- what whereabouts held; this is what the case notes service prefers to be asked for.
    case_note_uuid          UUID,
    -- The case note's occurrenceDateTime, which whereabouts set to the moment of the move.
    occurred_at             TIMESTAMP,
    -- When the enrichment was resolved. Null means not yet attempted, or the last attempt hit a
    -- transient failure and should be retried; set with null note fields means the case note is
    -- definitively gone and there is nothing more to fetch.
    enriched_at             TIMESTAMP,

    CONSTRAINT cell_movement_nomis_pk PRIMARY KEY (booking_id, bed_assignment_sequence)
);

COMMENT ON TABLE cell_movement_nomis IS 'Cell move reasons inherited from whereabouts-api CELL_MOVE_REASON: the link as whereabouts held it, plus what this service has resolved from the case note it points at.';

-- Serves the per-prisoner history read once migrated rows are enriched, matching
-- cell_movement_prisoner_occurred_idx on the native table. Partial: unenriched rows have no
-- prisoner number to index.
CREATE INDEX cell_movement_nomis_prisoner_idx
    ON cell_movement_nomis (prisoner_number, occurred_at DESC)
    WHERE prisoner_number IS NOT NULL;

/*
 * The read that MAPA-279 adds looks a movement up by (booking id, bed assignment sequence). In
 * cell_movement that pair is not the primary key - id is - and neither existing index leads with
 * booking_id, so it needs one of its own.
 *
 * Not unique. cell_movement holds a row per attempt, and a PENDING row that later succeeded on a
 * retry would give two rows the same pair. bed_assignment_sequence is also null while PENDING and
 * when NOMIS treated the move as a no-op, and those nulls must not collide.
 */
CREATE INDEX cell_movement_booking_bed_assignment_idx
    ON cell_movement (booking_id, bed_assignment_sequence);
