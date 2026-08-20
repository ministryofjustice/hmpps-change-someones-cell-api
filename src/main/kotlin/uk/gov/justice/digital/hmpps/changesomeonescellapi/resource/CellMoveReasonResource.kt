package uk.gov.justice.digital.hmpps.changesomeonescellapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.Roles
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMoveReasonCode
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMoveReasonType
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

/**
 * The cell move reasons, served from this service rather than prison-api's `CHG_HOUS_RSN`
 * reference domain (MAPA-289). The list is a constant - see [CellMoveReasonCode] for why it is
 * owned here - so there is no service layer and nothing to cache.
 *
 * Not to be confused with `GET /cell-movements/{bookingId}/bed-assignment/{sequence}` on
 * [CellMovementResource], which answers "why was *this* prisoner moved into *this* cell". This one
 * is reference data: the reasons that exist at all.
 */
@RestController
@RequestMapping("/cell-movements/reasons", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Cell movements", description = "Records prisoners being moved between cells")
class CellMoveReasonResource {

  @GetMapping
  @PreAuthorize("hasAnyRole('${Roles.CELL_MOVEMENTS_RO}', '${Roles.CELL_MOVEMENTS_RW}')")
  @Operation(
    summary = "List the reasons a prisoner can be moved between cells",
    description = "Returns every reason this service recognises, in display order - the order of " +
      "the array is the contract, so a client renders it as it arrives rather than sorting it. " +
      "Retired reasons are included with active=false: they cannot be chosen for a new move, but " +
      "historic movements carry them and a screen showing history needs their descriptions. A " +
      "client offering a choice should filter on active; a client resolving a code should not. " +
      "The active codes are exactly those accepted as reasonCode on POST /cell-movements. " +
      "Requires role ROLE_CELL_MOVEMENTS__RO or ROLE_CELL_MOVEMENTS__RW",
    responses = [
      ApiResponse(
        responseCode = "200",
        description = "The cell move reasons",
        content = [
          Content(
            mediaType = "application/json",
            array = ArraySchema(schema = Schema(implementation = CellMoveReasonType::class)),
          ),
        ],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires ROLE_CELL_MOVEMENTS__RO or ROLE_CELL_MOVEMENTS__RW",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun getCellMoveReasons(): List<CellMoveReasonType> = CellMoveReasonCode.entries
    .sortedBy { it.listSeq }
    .map { CellMoveReasonType(code = it.code, description = it.description, active = it.active) }
}
