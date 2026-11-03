package uk.gov.justice.digital.hmpps.changesomeonescellapi.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.changesomeonescellapi.config.Roles
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentNomisMapping
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentSyncResponse
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.NomisBedAssignment
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.BedAssignmentSyncService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse

/**
 * Where hmpps-prisoner-from-nomis-migration writes NOMIS bed assignment history, following the CSRA API's
 * /nomis-sync pattern. During coexistence these are the only writers of bed_assignment (MAPA-291 paper
 * section 7.1).
 */
@RestController
@Validated
@RequestMapping("/nomis-sync/bed-assignments", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "NOMIS migration and sync", description = "Bed assignment history migrated and synchronised from NOMIS")
@PreAuthorize("hasRole('${Roles.CELL_MOVEMENTS_SYNC_RW}')")
class BedAssignmentNomisSyncResource(
  private val bedAssignmentSyncService: BedAssignmentSyncService,
) {

  @PostMapping("/migrate/{prisonerNumber}")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
    summary = "Migrate a prisoner's bed assignment history from NOMIS",
    description = "Receives every bed assignment NOMIS holds for the prisoner, across all bookings, and returns the " +
      "DPS id of each with its NOMIS key. Safe to repeat: a prisoner migrated again keeps the same ids. " +
      "Requires role ROLE_CELL_MOVEMENTS__SYNC__RW",
    responses = [
      ApiResponse(responseCode = "201", description = "Bed assignment history migrated"),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, including the same NOMIS row sent twice",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun migrate(
    @Parameter(description = "The prisoner number", example = "A1234AA")
    @PathVariable
    @Pattern(regexp = "^[A-Z][0-9]{4}[A-Z]{2}$", message = "prisonerNumber must be in the format A1234BC")
    prisonerNumber: String,
    @RequestBody @Valid assignments: List<NomisBedAssignment>,
  ): List<BedAssignmentNomisMapping> = bedAssignmentSyncService.migrate(prisonerNumber, assignments)

  @PutMapping("/sync/{prisonerNumber}")
  @Operation(
    summary = "Synchronise a prisoner's bed assignment history from NOMIS",
    description = "Receives every bed assignment NOMIS now holds for the prisoner and makes DPS match it: new rows " +
      "are created, known rows updated in place, and rows NOMIS no longer holds against the prisoner deleted. " +
      "Send the whole history, not only what changed. Returns what changed so the NOMIS mappings can follow. " +
      "Requires role ROLE_CELL_MOVEMENTS__SYNC__RW",
    responses = [
      ApiResponse(responseCode = "200", description = "Bed assignment history synchronised"),
      ApiResponse(
        responseCode = "400",
        description = "Invalid request, including the same NOMIS row sent twice",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Missing required role. Requires the ROLE_CELL_MOVEMENTS__SYNC__RW role",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  fun sync(
    @Parameter(description = "The prisoner number", example = "A1234AA")
    @PathVariable
    @Pattern(regexp = "^[A-Z][0-9]{4}[A-Z]{2}$", message = "prisonerNumber must be in the format A1234BC")
    prisonerNumber: String,
    @RequestBody @Valid assignments: List<NomisBedAssignment>,
  ): BedAssignmentSyncResponse = bedAssignmentSyncService.sync(prisonerNumber, assignments)
}
