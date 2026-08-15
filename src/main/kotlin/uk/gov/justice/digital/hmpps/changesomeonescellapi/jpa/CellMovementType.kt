package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Which kind of cell movement this was")
enum class CellMovementType {
  /** A move into a named cell, with a reason and an explanation, which also creates a case note. */
  CELL_MOVE,

  /**
   * A move out to the prison's virtual CSWAP location to free the cell. Carries no explanation and
   * creates no case note, because the journey does not ask for one.
   */
  CELL_SWAP,
}
