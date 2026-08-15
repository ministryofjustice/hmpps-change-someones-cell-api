package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "How far a cell movement got")
enum class CellMovementStatus {
  /** Recorded, but the NOMIS move has not been confirmed yet. A row left here means the move failed. */
  PENDING,

  /** The prisoner was moved in NOMIS. The case note may or may not exist - check case_note_uuid. */
  COMPLETED,

  /**
   * The prisoner was moved but the case note could not be created. The move is not undone; the
   * comment text is held here so the case note can be recreated later.
   */
  CASE_NOTE_FAILED,
}
