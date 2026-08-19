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

  @get:Schema(
    description = "The locations-inside-prison UUID for fromLocationKey - the location's fixed identity, " +
      "stable when keys are renamed. Null if it could not be resolved at the time of the move.",
  )
  val fromLocationId: UUID?,

  @get:Schema(description = "The cell they were moved into", example = "MDI-1-1-015")
  val toLocationKey: String,

  @get:Schema(
    description = "The locations-inside-prison UUID for toLocationKey - the location's fixed identity, " +
      "stable when keys are renamed. Null if it could not be resolved at the time of the move.",
  )
  val toLocationId: UUID?,

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

@Schema(description = "Where the record of a cell movement came from")
enum class CellMovementSource {
  /** Recorded by this service, from MAPA-278 onwards. Complete. */
  CELL_MOVEMENTS,

  /**
   * Migrated from whereabouts-api's CELL_MOVE_REASON table. Carries only a case note reference;
   * everything else is either resolved from that case note or absent.
   */
  MIGRATED_FROM_WHEREABOUTS,
}

/**
 * Why a prisoner was moved into a particular cell, keyed the way NOMIS keys the bed assignment it
 * relates to.
 *
 * Replaces the two hops consumers make today - whereabouts for the case note id, then case-notes
 * for its text. For anything this service recorded, [commentText] is served from our own row with
 * no downstream call at all.
 *
 * Several fields are nullable because migrated records genuinely do not have them, not because
 * they are optional in general. [source] says which kind of record this is, and is the field to
 * branch on rather than inferring from which values came back null.
 */
@Schema(description = "The reason a prisoner was moved into a cell, and the explanation given at the time")
data class CellMovementReason(
  @get:Schema(description = "The NOMIS booking the movement was recorded against", example = "1200866")
  val bookingId: Long,

  @get:Schema(description = "The NOMIS bed assignment this movement created", example = "3")
  val bedAssignmentSequence: Int,

  @get:Schema(description = "Which record this came from, and therefore how complete it is")
  val source: CellMovementSource,

  @get:Schema(
    description = "The locations-inside-prison UUIDs and keys for the movement's locations. Present only " +
      "for movements this service recorded - whereabouts never held locations at all.",
    example = "MDI-1-1-015",
  )
  val toLocationKey: String? = null,
  val toLocationId: UUID? = null,
  val fromLocationKey: String? = null,
  val fromLocationId: UUID? = null,

  @get:Schema(
    description = "The prisoner moved. Always present for a movement this service recorded. For a " +
      "migrated one it is resolved from the booking, and is null if that booking is no longer the " +
      "prisoner's current one.",
    example = "A1234BC",
  )
  val prisonerNumber: String?,

  @get:Schema(
    description = "The reason for the move, a code from the CHG_HOUS_RSN reference domain. For a " +
      "migrated movement this is recovered from the case note's subType, so it is null when the " +
      "case note could not be read.",
    example = "ADM",
  )
  val reasonCode: String?,

  @get:Schema(
    description = "What happened, in the mover's own words - the \"what happened\" text. Null for a " +
      "cell swap, which never asks for an explanation, and for a migrated movement whose case note " +
      "could not be read.",
    example = "Moved following an altercation on the wing",
  )
  val commentText: String?,

  @get:Schema(description = "The case note recording the explanation, where there is one")
  val caseNoteUuid: UUID?,

  @get:Schema(
    description = "The deprecated numeric case note id. Present for a migrated movement, which is " +
      "the only form whereabouts stored, so a caller can still fall back to reading the case note " +
      "directly.",
    example = "1234567",
  )
  val caseNoteLegacyId: Long?,

  @get:Schema(description = "When the move was recorded. Not held for a migrated movement.")
  val occurredAt: LocalDateTime?,

  @get:Schema(description = "Who made the move. Not held for a migrated movement.", example = "AUTH_ADM")
  val recordedBy: String?,

  @get:Schema(
    description = "Which journey this was. Null for a migrated movement: whereabouts recorded no " +
      "cell swaps, but nothing in the data proves a given row was not one.",
  )
  val movementType: CellMovementType?,
)
