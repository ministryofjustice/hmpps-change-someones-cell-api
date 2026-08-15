package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

import jakarta.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.CONFLICT
import org.springframework.http.HttpStatus.FORBIDDEN
import org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR
import org.springframework.http.HttpStatus.LOCKED
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestControllerAdvice
class ChangeSomeonesCellApiExceptionHandler {
  @ExceptionHandler(ValidationException::class)
  fun handleValidationException(e: ValidationException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Validation exception: {}", e.message) }

  @ExceptionHandler(HttpMessageNotReadableException::class)
  fun handleHttpMessageNotReadableException(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: Couldn't read request body",
        developerMessage = e.message,
      ),
    ).also { log.info("Could not read request body: {}", e.message) }

  @ExceptionHandler(MethodArgumentNotValidException::class)
  fun handleMethodArgumentNotValidException(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Method argument not valid exception: {}", e.message) }

  @ExceptionHandler(PrisonerNotFoundException::class)
  fun handlePrisonerNotFoundException(e: PrisonerNotFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        errorCode = ErrorCode.PrisonerNotFound.name,
        userMessage = "Not found: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Prisoner not found: {}", e.message) }

  @ExceptionHandler(PrisonerNotInPrisonException::class)
  fun handlePrisonerNotInPrisonException(e: PrisonerNotInPrisonException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        errorCode = ErrorCode.PrisonerNotInPrison.name,
        userMessage = "Not found: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Prisoner not in prison: {}", e.message) }

  @ExceptionHandler(DuplicateCellMovementException::class)
  fun handleDuplicateCellMovementException(e: DuplicateCellMovementException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(CONFLICT)
    .body(
      ErrorResponse(
        status = CONFLICT,
        errorCode = ErrorCode.DuplicateCellMovement.name,
        userMessage = "Conflict: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Duplicate cell movement: {}", e.message) }

  // The two that whereabouts-api threw away by mapping every prison-api error to a 500. The UI's
  // cell-not-available page and its "open in P-NOMIS" message both depend on getting these back.
  @ExceptionHandler(CellNotAvailableException::class)
  fun handleCellNotAvailableException(e: CellNotAvailableException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(BAD_REQUEST)
    .body(
      ErrorResponse(
        status = BAD_REQUEST,
        errorCode = ErrorCode.CellNotAvailable.name,
        userMessage = "Validation failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Cell not available: {}", e.message) }

  @ExceptionHandler(PrisonerRecordLockedException::class)
  fun handlePrisonerRecordLockedException(e: PrisonerRecordLockedException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(LOCKED)
    .body(
      ErrorResponse(
        status = LOCKED,
        errorCode = ErrorCode.PrisonerRecordLocked.name,
        userMessage = "Locked: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("Prisoner record locked: {}", e.message) }

  @ExceptionHandler(NoResourceFoundException::class)
  fun handleNoResourceFoundException(e: NoResourceFoundException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(NOT_FOUND)
    .body(
      ErrorResponse(
        status = NOT_FOUND,
        userMessage = "No resource found failure: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.info("No resource found exception: {}", e.message) }

  @ExceptionHandler(AccessDeniedException::class)
  fun handleAccessDeniedException(e: AccessDeniedException): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(FORBIDDEN)
    .body(
      ErrorResponse(
        status = FORBIDDEN,
        userMessage = "Forbidden: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.debug("Forbidden (403) returned: {}", e.message) }

  @ExceptionHandler(Exception::class)
  fun handleException(e: Exception): ResponseEntity<ErrorResponse> = ResponseEntity
    .status(INTERNAL_SERVER_ERROR)
    .body(
      ErrorResponse(
        status = INTERNAL_SERVER_ERROR,
        userMessage = "Unexpected error: ${e.message}",
        developerMessage = e.message,
      ),
    ).also { log.error("Unexpected exception", e) }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

class PrisonerNotFoundException(prisonerNumber: String) : Exception("No prisoner found for prisoner number $prisonerNumber")

class PrisonerNotInPrisonException(prisonerNumber: String) : Exception("Prisoner $prisonerNumber is not currently in a prison and cannot be moved")

class DuplicateCellMovementException(prisonerNumber: String, locationKey: String) : Exception("Prisoner $prisonerNumber was already moved to $locationKey moments ago")

/**
 * prison-api rejected the move: the cell is full, not a cell or reception, inactive, in a
 * different prison, or the reason code is unknown. It returns all of these as a 400 with one
 * combined message, so we pass its message through rather than guessing which it was.
 */
class CellNotAvailableException(message: String?) : Exception(message ?: "The cell is not available")

/** prison-api could not lock the booking row - someone has the prisoner open in P-NOMIS. */
class PrisonerRecordLockedException : Exception("Resource locked, possibly in use in P-Nomis.")
