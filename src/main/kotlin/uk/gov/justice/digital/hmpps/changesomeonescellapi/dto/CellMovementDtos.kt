package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementType
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "A request to move a prisoner to a different cell")
data class CellMovementRequest(
  @get:Schema(description = "The prisoner number", example = "A1234BC", requiredMode = Schema.RequiredMode.REQUIRED)
  @get:NotBlank(message = "prisonerNumber must not be blank")
  @get:Pattern(regexp = "^[A-Z][0-9]{4}[A-Z]{2}$", message = "prisonerNumber must be in the format A1234BC")
  val prisonerNumber: String,

  // The full location key, which is what prison-api matches against the NOMIS internal location
  // description. The UI already holds this; it is the same string LIP calls a key.
  @get:Schema(
    description = "The key of the cell to move into",
    example = "MDI-1-1-015",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  @get:NotBlank(message = "toLocationKey must not be blank")
  @get:Size(max = 64, message = "toLocationKey must be no more than 64 characters")
  val toLocationKey: String,

  @get:Schema(
    description = "The reason for the move, a code from the CHG_HOUS_RSN reference domain",
    example = "ADM",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  @get:NotBlank(message = "reasonCode must not be blank")
  @get:Size(max = 12, message = "reasonCode must be no more than 12 characters")
  val reasonCode: String,

  @get:Schema(
    description = "What happened, in the mover's own words. Recorded here and used as the case note text.",
    example = "Moved following an altercation on the wing",
    requiredMode = Schema.RequiredMode.REQUIRED,
  )
  @get:NotBlank(message = "commentText must not be blank")
  val commentText: String,
)

@Schema(
  description = "A request to move a prisoner out of their cell to the prison's cell swap location, " +
    "freeing the cell. Takes nothing but the prisoner: the destination is the prison's own C-SWAP " +
    "location, and this journey records no reason or explanation.",
)
data class CellSwapRequest(
  @get:Schema(description = "The prisoner number", example = "A1234BC", requiredMode = Schema.RequiredMode.REQUIRED)
  @get:NotBlank(message = "prisonerNumber must not be blank")
  @get:Pattern(regexp = "^[A-Z][0-9]{4}[A-Z]{2}$", message = "prisonerNumber must be in the format A1234BC")
  val prisonerNumber: String,
)

@Schema(description = "A recorded cell movement")
data class CellMovement(
  @get:Schema(description = "Our id for this movement")
  val id: UUID,

  @get:Schema(description = "Whether this was a move into a cell or a swap out of one")
  val movementType: CellMovementType,

  @get:Schema(description = "The prisoner number", example = "A1234BC")
  val prisonerNumber: String,

  @get:Schema(description = "The cell they were in before the move, as best known", example = "MDI-1-1-001")
  val fromLocationKey: String?,

  @get:Schema(description = "The cell they were moved into", example = "MDI-1-1-015")
  val toLocationKey: String,

  @get:Schema(description = "The reason code for the move", example = "ADM")
  val reasonCode: String,

  @get:Schema(description = "When the move was recorded")
  val occurredAt: LocalDateTime,

  @get:Schema(description = "Who made the move")
  val recordedBy: String,

  @get:Schema(
    description = "The case note recording the explanation. Null if the case note could not be created, " +
      "in which case status is CASE_NOTE_FAILED and the move itself still succeeded.",
  )
  val caseNoteUuid: UUID?,

  @get:Schema(description = "How far the movement got")
  val status: CellMovementStatus,
)
