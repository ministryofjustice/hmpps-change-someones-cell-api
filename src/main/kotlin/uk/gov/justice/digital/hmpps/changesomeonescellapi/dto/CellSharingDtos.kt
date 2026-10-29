package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

/**
 * Who a prisoner shared a cell with over a period (MAPA-477).
 *
 * Kept free of NOMIS internal identifiers - no booking ids or bed assignment sequences. Locations are
 * identified by DPS id and key and people by prisoner number, so this contract does not change when
 * the data stops coming from NOMIS. The one exception is [CellShare.nomisLocationId], which is
 * transitional - see there.
 */
@Schema(description = "The cells a prisoner shared with someone else during a period, and who with")
data class CellSharingHistory(
  @get:Schema(description = "The prisoner number", example = "A1234AA")
  val prisonerNumber: String,

  @get:Schema(description = "Start of the period", example = "2026-07-06T21:07:00")
  val from: LocalDateTime,

  @get:Schema(description = "End of the period - now, if no end was given", example = "2026-07-09T10:15:00")
  val to: LocalDateTime,

  @get:Schema(
    description = "One entry per cell stay in the period where the prisoner shared, most recent first. " +
      "A prisoner who leaves a cell and later returns to it has two entries. Empty when they did not share.",
  )
  val cells: List<CellShare>,
)

@Schema(description = "A stay in a cell during which the prisoner shared with at least one other prisoner")
data class CellShare(
  @get:Schema(description = "The prison the cell is in", example = "LEI")
  val prisonId: String,

  @get:Schema(description = "The location key of the cell", example = "LEI-A-1-015")
  val locationKey: String,

  @get:Schema(description = "The cell without the prison prefix, for display", example = "A-1-015")
  val pathHierarchy: String,

  @get:Schema(
    description = "The DPS location id of the cell. Null if locations-inside-prison could not be reached " +
      "or does not know the key - locationKey is always present.",
    example = "2475f250-434a-4257-afe7-b911f1773a4d",
  )
  val dpsLocationId: UUID?,

  // Transitional. The prisoner profile's location history page - the CSRA's "View details" link -
  // takes the NOMIS location id in its address. Remove this once that page accepts a DPS location id.
  @get:Schema(
    description = "The NOMIS location id of the cell. Only for linking to the prisoner profile location history " +
      "page, which still takes it - use dpsLocationId for anything else. Will be removed once that page accepts " +
      "a DPS location id.",
    example = "2326301",
  )
  val nomisLocationId: Long,

  @get:Schema(
    description = "When the prisoner moved into the cell. The whole stay, not cut to the period.",
    example = "2026-07-06T21:10:00",
  )
  val movedIn: LocalDateTime,

  @get:Schema(
    description = "When the prisoner moved out of the cell. The whole stay, not cut to the period. Null if they are still there.",
    example = "2026-07-08T14:00:00",
  )
  val movedOut: LocalDateTime?,

  @get:Schema(description = "Everyone who shared the cell with the prisoner during the period, earliest first")
  val sharedWith: List<Cellmate>,
)

@Schema(description = "A prisoner who shared a cell with the prisoner during the period")
data class Cellmate(
  @get:Schema(description = "The cellmate's prisoner number", example = "A1245XY")
  val prisonerNumber: String,

  @get:Schema(description = "The cellmate's first name. Null if prisoner-search has no record of them.", example = "James")
  val firstName: String?,

  @get:Schema(description = "The cellmate's last name. Null if prisoner-search has no record of them.", example = "Clive")
  val lastName: String?,

  @get:Schema(
    description = "When they started sharing, within the period. If they shared more than once during the " +
      "same stay, the first time.",
    example = "2026-07-06T21:10:00",
  )
  val sharedFrom: LocalDateTime,

  @get:Schema(
    description = "When they stopped sharing, within the period. If they shared more than once during the " +
      "same stay, the last time. Null if they are still sharing and no end to the period was given.",
    example = "2026-07-08T14:00:00",
  )
  val sharedTo: LocalDateTime?,
)
