package uk.gov.justice.digital.hmpps.changesomeonescellapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovement
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMovementRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellSwapRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.CellMovementService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

@RestController
@Validated
@RequestMapping("/cell-movements", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Cell movements", description = "Records prisoners being moved between cells")
@PreAuthorize("hasRole('ROLE_CELL_MOVEMENTS__RW')")
class CellMovementResource(
  private val cellMovementService: CellMovementService,
) {

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Move a prisoner to a different cell",
    description = "Performs the move in NOMIS, records a MOVED_CELL case note explaining it, and stores our own " +
      "record of the movement. The prisoner's current booking and cell are resolved from prisoner-search, so no " +
      "booking id is needed from the caller. " +
      "If the case note cannot be created the move still succeeds and the movement is returned with status " +
      "CASE_NOTE_FAILED - the explanation is stored, so the case note can be recreated later. " +
      "Requires role ROLE_CELL_MOVEMENTS__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The prisoner was moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CellMovement::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, or the cell cannot be used - it is full, inactive, not a cell or " +
          "reception, in a different prison, or the reason code is not recognised",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such prisoner, or they are not currently in a prison and cannot be moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "This prisoner was moved to this same cell moments ago - a probable double submission",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "423",
        description = "The prisoner's record is locked, usually because someone has them open in P-NOMIS",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun moveToCell(
    @RequestBody @Valid
    request: CellMovementRequest,
  ): CellMovement = cellMovementService.move(request)

  @PostMapping("/cell-swap")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Move a prisoner out of their cell to free it",
    description = "Performs a cell swap in NOMIS, moving the prisoner to the prison's C-SWAP location so their " +
      "cell can be used by someone else, and records the movement. The destination is the prisoner's own " +
      "prison's cell swap location, so no location is supplied. " +
      "Unlike a cell move this creates **no case note**: the journey does not ask the user why, so there is no " +
      "explanation to record. " +
      "Requires role ROLE_CELL_MOVEMENTS__RW",
    responses = [
      ApiResponse(
        responseCode = "201",
        description = "The prisoner was moved out of their cell",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = CellMovement::class))],
      ),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, or this prison has no cell swap location configured",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "404",
        description = "No such prisoner, or they are not currently in a prison and cannot be moved",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "409",
        description = "This prisoner was moved out of their cell moments ago - a probable double submission",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "423",
        description = "The prisoner's record is locked, usually because someone has them open in P-NOMIS. " +
          "Documented for completeness, but NOMIS does not currently return it on the cell swap path.",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun moveToCellSwap(
    @RequestBody @Valid
    request: CellSwapRequest,
  ): CellMovement = cellMovementService.swap(request)
}
