package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

/**
 * Codes that api clients can use to discriminate between error types, instead of matching on
 * non-constant text descriptions of HTTP status codes.
 *
 * The numeric value is a stable registry. What goes on the wire is the enum's `name`.
 *
 * NB: Once defined, the values must not be changed.
 */
enum class ErrorCode(val errorCode: Int) {
  PrisonerNotFound(101),
  PrisonerNotInPrison(102),
  DuplicateCellMovement(103),
  CellNotAvailable(104),
  PrisonerRecordLocked(105),
  CellSwapUnavailable(106),
  CellMovementReasonNotFound(107),
}
