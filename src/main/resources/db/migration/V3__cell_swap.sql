/*
 * Cell swap support (MAPA-285).
 *
 * A cell swap moves a prisoner out of their cell to the prison's virtual CSWAP location, freeing
 * the cell for someone else. It is recorded in the same table as a normal cell move - it is a cell
 * movement, and MAPA-279 serves one history - but it differs in two ways that this migration makes
 * room for.
 *
 * comment_text becomes nullable. The UI never asks for an explanation on a cell swap: the journey
 * is a single confirm button, with the reason radios and the comment box both suppressed and
 * validation skipped entirely. So a swap has no comment, and no case note is created for one -
 * there would be nothing legitimate to put in it. Writing an empty string or fabricated text would
 * assert something untrue, so the column records the absence honestly instead.
 *
 * The invariant that still holds - a normal cell move always has a comment - is enforced where it
 * always was, by @NotBlank on the request, not by the column. There is deliberately no cross-column
 * CHECK constraint: V2 took the position that status gets no check constraint so that adding a
 * state stays a code change, and hardcoding 'CELL_SWAP' in a constraint would reopen that.
 *
 * movement_type records which journey the user took. It is worth a column rather than inferring
 * from to_location_key ending '-CSWAP', because those two facts are intent and observation and
 * they decouple: prison-api's dedicated swap endpoint is deprecated, and once it goes we will
 * perform a swap by calling the ordinary living-unit endpoint with the CSWAP key - at which point
 * a swap becomes mechanically indistinguishable from a move to a CSWAP location, and this column
 * is the only surviving record of which it was. A leading-wildcard LIKE could not use either index
 * either. Inferring from comment_text IS NULL is weaker still, conflating "is a swap" with "has no
 * comment".
 *
 * It also disambiguates the terminal states without overloading status. On a COMPLETED row,
 * case_note_uuid IS NULL if and only if movement_type = 'CELL_SWAP'; a CELL_SWAP row is never
 * CASE_NOTE_FAILED, because no case note is ever attempted.
 *
 * The default backfills correctly: every row written before this migration was a normal move.
 */
ALTER TABLE cell_movement
    ALTER COLUMN comment_text DROP NOT NULL;

ALTER TABLE cell_movement
    ADD COLUMN movement_type VARCHAR(20) NOT NULL DEFAULT 'CELL_MOVE';

COMMENT ON COLUMN cell_movement.movement_type IS 'CELL_MOVE | CELL_SWAP - which journey the user took';
COMMENT ON COLUMN cell_movement.comment_text IS 'The mover''s explanation. Always present for a cell move; never present for a cell swap.';
