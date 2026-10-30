/*
 * Bed assignment history: one row per period a prisoner was assigned to a location (MAPA-481, phase
 * 3a of MAPA-291).
 *
 * cell_movement stays what it is - the command record of a move this service performed, with its
 * PENDING / COMPLETED / CASE_NOTE_FAILED lifecycle for orchestrating prison-api and case notes.
 * bed_assignment is the state: where a prisoner was, from when to when, whoever made the change. An
 * open row (end_at null) is a current assignment. Extending cell_movement was rejected because its
 * lifecycle columns mean nothing for history that NOMIS recorded, and because the occupancy query
 * wants open periods, not commands.
 *
 * During coexistence the NOMIS to DPS sync is the only writer (paper section 7.1), including the echo
 * of moves this service made through prison-api. Nothing reads these tables yet; the current cell,
 * history and occupancy reads arrive in phase 3b.
 *
 * Never drop a historic row. NOMIS history references living units that have since been deleted or
 * renamed, and bed-level children that locations-inside-prison does not hold. Those rows keep the raw
 * NOMIS description in location_key with location_id null, and the original values in
 * bed_assignment_nomis.
 *
 * Temporary locations (RECP, COURT, TAP, ECL, CSWAP) are rows like any other, assigned to the pseudo
 * location NOMIS used. locations-inside-prison holds RECP, COURT and TAP as non-cell locations, so
 * they can carry a location_id; a cell's occupancy, counted by its own location_id, never includes
 * them. This keeps full parity with NOMIS history and gives reception occupancy for free.
 *
 * No booking id. An assignment belongs to a prisoner over time, not to a NOMIS booking: a prisoner's
 * cell history runs across bookings, and any narrower view is a time range. DPS is designing the
 * booking out (ADR-NX-04), with periods of custody held by the Person Location API, so a booking
 * column here would be one more thing to unpick. The NOMIS booking id survives only in
 * bed_assignment_nomis, as part of the NOMIS key a row came from.
 *
 * No constraint on one open row per prisoner. NOMIS ends a prisoner's previous row when they move
 * within a prison, but not reliably on release or transfer out, so historic bookings can hold an open
 * row. Ending those is the sync's job (MAPA-483), from movement events, not something the schema can
 * enforce on day one of a migration.
 */
CREATE TABLE bed_assignment
(
    id               UUID        NOT NULL CONSTRAINT bed_assignment_pk PRIMARY KEY,
    prisoner_number  VARCHAR(7)  NOT NULL,
    location_id      UUID,
    -- Unbounded: for a row that cannot be mapped this holds the NOMIS description verbatim, and
    -- bounding it here would invent a limit the source does not have and could reject a historic row.
    location_key     TEXT        NOT NULL,
    start_at         TIMESTAMP   NOT NULL,
    start_reason     VARCHAR(12),
    end_at           TIMESTAMP,
    end_reason       VARCHAR(12),
    source           VARCHAR(10) NOT NULL,
    recorded_by      VARCHAR(64) NOT NULL,
    cell_movement_id UUID CONSTRAINT bed_assignment_cell_movement_fk REFERENCES cell_movement (id)
);

/*
 * Occupancy is derived by counting open rows in a location, not maintained as a counter (paper D2).
 * Partial, so the count only ever touches current assignments however long the history grows.
 */
CREATE INDEX bed_assignment_open_location_idx
    ON bed_assignment (location_id)
    WHERE end_at IS NULL;

/*
 * The sync replaces a prisoner's rows as a set, and the history read in phase 3b is per prisoner, so
 * both start here.
 */
CREATE INDEX bed_assignment_prisoner_start_idx
    ON bed_assignment (prisoner_number, start_at DESC);

/*
 * The NOMIS values a migrated or synced row came from, unchanged. The same precedent as
 * cell_movement_nomis and CSRA: the main table holds what DPS means by the row, this holds what NOMIS
 * said, so a mapping decision can be revisited without going back to NOMIS.
 *
 * (nomis_booking_id, nomis_sequence) is the NOMIS key and is unique, which is what makes the migration
 * and the sync re-runnable: a row already brought across is found and updated, not duplicated. A row
 * DPS creates after the flip has no entry here.
 *
 * This is the only place the NOMIS booking id is held, and only as provenance (ADR-NX-03 keeps legacy
 * values). It is never part of this service's API, and is used for nothing but finding the NOMIS row
 * again: the sync's lookup, and following a booking NOMIS moves to another prisoner. The unique key
 * leads with it, so that lookup needs no index of its own.
 */
CREATE TABLE bed_assignment_nomis
(
    bed_assignment_id       UUID      NOT NULL CONSTRAINT bed_assignment_nomis_pk PRIMARY KEY
        CONSTRAINT bed_assignment_nomis_bed_assignment_fk REFERENCES bed_assignment (id) ON DELETE CASCADE,
    nomis_booking_id        BIGINT    NOT NULL,
    nomis_sequence          INTEGER   NOT NULL,
    living_unit_id          BIGINT    NOT NULL,
    living_unit_description TEXT,
    migrated_at             TIMESTAMP NOT NULL,
    CONSTRAINT bed_assignment_nomis_key_unique UNIQUE (nomis_booking_id, nomis_sequence)
);

-- Data dictionary. See V6 for the sensitivity classifications.

COMMENT ON TABLE bed_assignment IS 'One row per period a prisoner was assigned to a location: where they were, from when to when. An open row (end_at null) is a current assignment. Populated by the NOMIS to DPS migration and sync during coexistence; cell_movement remains the record of moves this service performed.';
COMMENT ON COLUMN bed_assignment.id IS 'Primary key. A UUIDv7 generated by the application. [Sensitivity: NONE]';
COMMENT ON COLUMN bed_assignment.prisoner_number IS 'NOMIS offender number of the prisoner. Kept current: it follows the booking when NOMIS merges prisoner numbers or moves a booking to another prisoner. The identifier that makes every row in this table personal data about that prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment.location_id IS 'locations-inside-prison UUID of the location, the durable identity across renames. Null when the NOMIS living unit has no locations-inside-prison equivalent, for example one deleted before the locations migration. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment.location_key IS 'The location as {prisonId}-{pathHierarchy} at the time, or the NOMIS living unit description verbatim when it could not be mapped. Always present, so history can be shown even when location_id is null. Locates a named prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment.start_at IS 'When the assignment began. With prisoner_number and the location this places a named prisoner in a specific place at a specific time. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment.start_reason IS 'Why the assignment began, as a CHG_HOUS_RSN code such as HOSP or VP. Null where NOMIS recorded none. Special category because of its values: the vocabulary includes Healthcare, Vulnerable Prisoner and Someone''s safety. See V6 and reference-data.csv. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN bed_assignment.end_at IS 'When the assignment ended. Null for a current assignment. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment.end_reason IS 'Why the assignment ended, as a CHG_HOUS_RSN code. NOMIS records no end reason, so this is null on every row from NOMIS; it exists for assignments DPS ends after the switch-over. Special category for the same reason as start_reason. [Sensitivity: SPECIAL-CATEGORY]';
COMMENT ON COLUMN bed_assignment.source IS 'Which system recorded the assignment: NOMIS (migrated or synced) or DPS. [Sensitivity: NONE]';
COMMENT ON COLUMN bed_assignment.recorded_by IS 'The username that recorded the assignment. For rows from NOMIS, the NOMIS creating user. [Sensitivity: STAFF]';
COMMENT ON COLUMN bed_assignment.cell_movement_id IS 'The cell_movement that opened this assignment, when this service made the move. Null for changes made in NOMIS and for history before this service existed. [Sensitivity: NONE]';

COMMENT ON TABLE bed_assignment_nomis IS 'The NOMIS BED_ASSIGNMENT_HISTORIES values a bed_assignment row was migrated or synced from, unchanged, so mapping decisions can be revisited without going back to NOMIS.';
COMMENT ON COLUMN bed_assignment_nomis.bed_assignment_id IS 'The bed_assignment row this came from NOMIS as. Primary key and foreign key. [Sensitivity: NONE]';
COMMENT ON COLUMN bed_assignment_nomis.nomis_booking_id IS 'NOMIS OFFENDER_BOOK_ID. With nomis_sequence, the NOMIS key, unique here. Provenance only: bed_assignment deliberately has no booking id, and this is never exposed by the API. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment_nomis.nomis_sequence IS 'NOMIS BED_ASSIGN_SEQ, the position of the row in the booking''s bed assignment history. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment_nomis.living_unit_id IS 'NOMIS LIVING_UNIT_ID (AGENCY_INTERNAL_LOCATIONS id), kept even when the location no longer exists in NOMIS. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment_nomis.living_unit_description IS 'NOMIS description of the living unit when the row was copied. Null when NOMIS no longer holds the location. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN bed_assignment_nomis.migrated_at IS 'When the row was first copied from NOMIS. [Sensitivity: NONE]';
