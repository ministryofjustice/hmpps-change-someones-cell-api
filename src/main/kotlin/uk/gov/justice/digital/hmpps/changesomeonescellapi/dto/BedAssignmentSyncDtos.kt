package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime
import java.util.UUID

/**
 * One NOMIS BED_ASSIGNMENT_HISTORIES row, as sent by hmpps-prisoner-from-nomis-migration.
 *
 * The NOMIS fields are named as nomis-prisoner-api returns them (BedAssignmentHistoryResponse), so the
 * migration and sync can pass that response on with only [locationId] and [createdBy] added. Unknown
 * fields such as prisonId are ignored.
 *
 * The booking id is here only because it is half of the NOMIS key. It is stored as provenance in
 * bed_assignment_nomis and is not part of the bed assignment itself (ADR-NX-04).
 */
@Schema(description = "A bed assignment as held in NOMIS, with its location mapped to locations-inside-prison where possible")
data class NomisBedAssignment(
  @get:Schema(description = "NOMIS booking id: the first part of the NOMIS key", example = "2345678", requiredMode = Schema.RequiredMode.REQUIRED)
  val bookingId: Long,

  @get:Schema(description = "Sequence within the booking: the second part of the NOMIS key", example = "3", requiredMode = Schema.RequiredMode.REQUIRED)
  val sequence: Int,

  @get:Schema(description = "NOMIS internal location id of the living unit, sent even when NOMIS no longer holds the location", example = "-3009", requiredMode = Schema.RequiredMode.REQUIRED)
  val livingUnitId: Long,

  @get:Schema(description = "NOMIS description of the living unit, null when NOMIS no longer holds the location", example = "MDI-A-1-001")
  val livingUnitDescription: String? = null,

  // Mapped by the caller from the NOMIS location mapping (MAPA-291 paper section 7.2), not looked up here.
  @get:Schema(
    description = "The locations-inside-prison id of the living unit. Leave out when the living unit has no " +
      "equivalent there; the assignment is still kept, identified by its NOMIS description",
    example = "0199e3c0-0000-7000-8000-000000000001",
  )
  val locationId: UUID? = null,

  @get:Schema(description = "When the assignment began", example = "2023-05-01T09:15:00", requiredMode = Schema.RequiredMode.REQUIRED)
  val assignmentDateTime: LocalDateTime,

  @get:Schema(description = "When the assignment ended, null for a current assignment", example = "2023-06-10T14:30:00")
  val assignmentEndDateTime: LocalDateTime? = null,

  @get:Schema(description = "The reason code (domain CHG_HOUS_RSN)", example = "ADM")
  @get:Size(max = 12, message = "assignmentReasonCode must be no more than 12 characters")
  val assignmentReasonCode: String? = null,

  @get:Schema(description = "The NOMIS user who created the row", example = "NQP56Y", requiredMode = Schema.RequiredMode.REQUIRED)
  @get:NotBlank(message = "createdBy must not be blank")
  @get:Size(max = 64, message = "createdBy must be no more than 64 characters")
  val createdBy: String,
)

@Schema(description = "A bed assignment's DPS id with the NOMIS key it came from, for the NOMIS mapping service")
data class BedAssignmentNomisMapping(
  @get:Schema(description = "The DPS bed assignment id", example = "0199e3c0-0000-7000-8000-00000000000a")
  val id: UUID,

  @get:Schema(description = "NOMIS booking id", example = "2345678")
  val bookingId: Long,

  @get:Schema(description = "Sequence within the booking", example = "3")
  val sequence: Int,
)

@Schema(description = "What a sync changed, so the caller can bring the NOMIS mappings into line")
data class BedAssignmentSyncResponse(
  @get:Schema(description = "Assignments new to DPS")
  val created: List<BedAssignmentNomisMapping>,

  @get:Schema(description = "Assignments already in DPS, updated in place. Their ids are unchanged")
  val updated: List<BedAssignmentNomisMapping>,

  @get:Schema(description = "Assignments removed because NOMIS no longer holds them against this prisoner")
  val deleted: List<BedAssignmentNomisMapping>,
)
