/*
 * The migrated whereabouts-api CELL_MOVE_REASON rows (MAPA-279).
 *
 * CELL_MOVE_REASON is the only DPS-owned cell move data in existence. It exists because NOMIS
 * BED_ASSIGNMENT_HISTORIES has nowhere to put a case note reference or a free-text explanation -
 * only a 3-4 character reason code - so whereabouts kept the link from (booking, bed assignment
 * sequence) to a case note id on the side. The table dies with whereabouts, and
 * hmpps-prisoner-profile reads it to render "What happened" on the location history page, so both
 * the data and a read path have to survive.
 *
 * A separate table rather than rows in cell_movement, because these rows are a different thing.
 * They carry three columns where cell_movement has fourteen, and every one of the interesting ones
 * - prisoner number, reason code, comment text, who did it, when - is simply absent. Forcing them
 * into cell_movement would mean dropping most of its NOT NULL constraints, so that the shape of a
 * movement this service records would be dictated by data it did not record. Keeping them apart
 * lets cell_movement stay strict, and makes "is this ours or inherited?" a fact about which table
 * a row is in rather than a guess from which columns are null.
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
 * No migrated_at, no source column, no surrogate key. (booking_id, bed_assignment_sequence) is
 * already the natural key and is already unique in the source, so it is the primary key here too -
 * which also makes the one-off copy idempotent and re-runnable.
 */
CREATE TABLE cell_movement_nomis
(
    booking_id              BIGINT  NOT NULL,
    bed_assignment_sequence INTEGER NOT NULL,
    -- The numeric case note id, as whereabouts recorded it. The explanation of the move lives in
    -- that case note and nowhere else: whereabouts never stored the text, which is exactly the
    -- weakness cell_movement.comment_text fixes for movements recorded from MAPA-278 onwards.
    case_note_legacy_id     BIGINT  NOT NULL,
    CONSTRAINT cell_movement_nomis_pk PRIMARY KEY (booking_id, bed_assignment_sequence)
);

COMMENT ON TABLE cell_movement_nomis IS 'Cell move reasons migrated verbatim from whereabouts-api CELL_MOVE_REASON. Read only - this service never writes here except during the one-off migration.';

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
