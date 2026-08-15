/*
 * The record of a prisoner being moved to a different cell (MAPA-278), replacing whereabouts-api's
 * CELL_MOVE_REASON table.
 *
 * The important difference from whereabouts is comment_text. Whereabouts stored only a link from
 * (booking, bed assignment sequence) to a case note id, so the explanation of the move lived solely
 * in the case note - and if that call failed, it was lost with nothing to retry from. Holding the
 * text here makes the case note a derived artefact we can recreate, and lets us serve "what
 * happened" in one hop instead of two.
 *
 * Columns are typed to their real formats rather than bare text. comment_text is the exception and
 * is deliberately unbounded: the case notes service is the source of truth for case note text and
 * imposes no limit of its own, so bounding it here would invent a constraint the system of record
 * does not have.
 *
 * Migrated CELL_MOVE_REASON rows cannot satisfy the not null constraints below - they carry only a
 * booking id, a sequence and a numeric case note id. That is deliberate: they land in a separate
 * cell_movement_nomis side table in MAPA-279, holding the legacy values verbatim, rather than being
 * forced into a shape they do not have.
 */
CREATE TABLE cell_movement
(
    id                      UUID         NOT NULL CONSTRAINT cell_movement_pk PRIMARY KEY,
    prisoner_number         VARCHAR(7)   NOT NULL,
    -- Resolved from prisoner-search, never accepted from the caller. Stored because prison-api
    -- still identifies a booking this way; bookingId is not part of our API contract.
    booking_id              BIGINT       NOT NULL,
    -- Returned by prison-api once the move lands. Null while PENDING, and also null when the
    -- prisoner was already in the destination cell - prison-api treats that as a successful no-op.
    bed_assignment_sequence INTEGER,
    -- Where they were immediately before the move, as best we knew. prisoner-search is a near
    -- real-time projection rather than a live read, so this can lag; null if it could not be
    -- resolved. Never treat it as authoritative - NOMIS validates the real state during the move.
    from_location_key       VARCHAR(64),
    to_location_key         VARCHAR(64)  NOT NULL,
    -- A CHG_HOUS_RSN code, e.g. ADM. Also used as the case note subType, which caps at 12.
    reason_code             VARCHAR(12)  NOT NULL,
    comment_text            TEXT         NOT NULL,
    -- The case notes service is UUID canonical. case_note_legacy_id exists for migrated rows and
    -- is not populated by this service.
    case_note_uuid          UUID,
    case_note_legacy_id     BIGINT,
    occurred_at             TIMESTAMP    NOT NULL,
    -- The signed in user, which is also who NOMIS records as having made the move.
    recorded_by             VARCHAR(64)  NOT NULL,
    -- PENDING | COMPLETED | CASE_NOTE_FAILED. Stored as text with no check constraint so adding a
    -- state later is a code change, not a migration.
    status                  VARCHAR(20)  NOT NULL
);

COMMENT ON TABLE cell_movement IS 'A prisoner being moved to a different cell, with the reason and explanation';

-- Serves the duplicate guard: "has this prisoner just been moved to this same cell?". Leading
-- prisoner_number and to_location_key are both equality matches, with occurred_at ordered so the
-- most recent is found first.
CREATE INDEX cell_movement_prisoner_destination_idx
    ON cell_movement (prisoner_number, to_location_key, occurred_at DESC);

-- Serves the per-prisoner history read that MAPA-279 adds. Kept separate from the index above
-- rather than relying on its prefix, because that one leads with a two-column equality match and
-- would not order a whole prisoner's history correctly.
CREATE INDEX cell_movement_prisoner_occurred_idx
    ON cell_movement (prisoner_number, occurred_at DESC);
