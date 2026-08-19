/*
 * The locations-inside-prison UUIDs for a movement's two locations (MAPA-305).
 *
 * The keys already stored are {prisonId}-{pathHierarchy}, and the path hierarchy is mutable -
 * locations-inside-prison allows codes and hierarchy to be renamed, so a key written today may not
 * identify the same cell across years of history. The UUID is the fixed identity of a location,
 * stable across renames. The rule this establishes: the key is for talking to NOMIS-era APIs and
 * to humans; the UUID is for identity over time, and is what the history read model should join on.
 *
 * Both nullable, deliberately. Resolution is best effort and never blocks a move - the NOMIS move
 * is the business-critical operation, and locations-inside-prison being unreachable must not stop
 * it, the same stance as a failed case note. Rows written before this migration have no UUIDs
 * either. Nulls are backfillable.
 *
 * No index: no read is keyed by location yet. Add one with the read that needs it.
 */
ALTER TABLE cell_movement
    ADD COLUMN from_location_id UUID,
    ADD COLUMN to_location_id   UUID;

COMMENT ON COLUMN cell_movement.from_location_id IS 'locations-inside-prison UUID for from_location_key at the time of the move. The durable identity; the key is display and NOMIS vocabulary.';
COMMENT ON COLUMN cell_movement.to_location_id IS 'locations-inside-prison UUID for to_location_key at the time of the move. The durable identity; the key is display and NOMIS vocabulary.';
