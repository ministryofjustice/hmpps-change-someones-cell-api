/*
 * The prisoner number on both tables now follows the booking when NOMIS merges two prisoner numbers or
 * moves a booking to another prisoner (MAPA-438). No change to the columns themselves; this updates the
 * data dictionary descriptions from V6 so that anyone reading by prisoner number knows the value is
 * kept current rather than fixed at the time of the move.
 */
COMMENT ON COLUMN cell_movement.prisoner_number IS 'NOMIS offender number of the prisoner who was moved. Kept current: it follows the booking when NOMIS merges prisoner numbers or moves a booking to another prisoner. The identifier that makes every row in this table personal data about that prisoner. [Sensitivity: PERSONAL]';
COMMENT ON COLUMN cell_movement_nomis.prisoner_number IS 'NOMIS offender number, resolved from the case note rather than copied - the source table held only a booking id. Null until the row has been enriched. Kept current: it follows the booking when NOMIS merges prisoner numbers or moves a booking to another prisoner. [Sensitivity: PERSONAL]';
