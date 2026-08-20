package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * The cell move reasons this service recognises - the `CHG_HOUS_RSN` reference domain, owned here
 * rather than read from prison-api (MAPA-289).
 *
 * **Why owned and not proxied.** The code is validated by two downstream services, and both check
 * only that it *exists*, never that it is active: prison-api's `MovementUpdateService` on the
 * living-unit move, and offender-case-notes on the `MOVED_CELL` sub-type. Their failure modes are
 * very different. A code NOMIS rejects fails the move outright - noisy, but nothing is written. A
 * code case-notes rejects fails *after* the NOMIS move has succeeded, leaving a move on the
 * prisoner's record with no case note while the caller still gets a 201
 * ([uk.gov.justice.digital.hmpps.changesomeonescellapi.service.CellMovementService] marks it
 * `CASE_NOTE_FAILED`). case-notes' set comes from a single migration and is effectively frozen at
 * these twelve; NOMIS can gain a code at any time. Proxying prison-api would put such a code
 * straight into the picker and into that silent-failure path on the first click. A list we own
 * cannot: a new code reaches users only when someone adds it here, and whoever does that is told
 * by this comment to check case-notes first.
 *
 * **Editing this list.** Confirm the code exists in *both* prison-api's `CHG_HOUS_RSN` domain and
 * offender-case-notes' `MOVED_CELL` sub-types before adding it. `CellMoveReasonCodeTest` pins the
 * contents so any change is a reviewed diff, and the reconciliation check described there compares
 * all three sets.
 *
 * Values captured from **prod prison-api** on 2026-08-20
 * (`GET /api/reference-domains/domains/CHG_HOUS_RSN`, `Page-Limit: 1000`), which is what the UI
 * renders today. Descriptions deliberately match prison-api rather than case-notes' differently
 * worded copies, so our labels say what P-NOMIS says for the same move.
 *
 * Six codes are inactive. They are retired for *new* moves but still attached to years of history,
 * so they are served (and must keep being served) for display - see [active].
 */
enum class CellMoveReasonCode(
  /**
   * The wire code. Held as a property rather than taken from [name] so a future NOMIS code that is
   * not a valid Kotlin identifier still fits.
   */
  val code: String,
  val description: String,
  /** Display order, from NOMIS `LIST_SEQ`. Not exposed on the wire; it orders the response. */
  val listSeq: Int,
  /**
   * Whether the code may be chosen for a *new* move. Inactive codes are still returned, because
   * historic moves carry them and the cell move history screen resolves descriptions from this
   * same list - filtering them out would render years of moves as "Not entered".
   */
  val active: Boolean,
) {
  // Retired, but present throughout the history migrated from whereabouts (MAPA-304).
  ADM("ADM", "Administrative", 1, false),
  BEH("BEH", "Behaviour", 2, false),
  CLA("CLA", "Classification/Re-Classification", 3, false),
  CON("CON", "Conflict with Other Prisoners", 4, false),
  VP("VP", "Vulnerable Prisoner", 5, false),
  LN("LN", "Local Needs", 6, false),

  // The codes a user can pick today.
  RAIM("RAIM", "Reception and induction moves", 7, true),
  SS("SS", "Someone’s safety", 8, true),
  SPP("SPP", "Security of the prison or other people", 9, true),
  HOSP("HOSP", "Healthcare", 10, true),
  PCM("PCM", "Maintenance of the prison or cell", 11, true),
  GM("GM", "General moves", 12, true),
  ;

  companion object {
    /** In display order - the order [CellMoveReasonCode] declares, which is `LIST_SEQ` order. */
    private val byCode = entries.associateBy { it.code }

    fun of(code: String): CellMoveReasonCode? = byCode[code]

    /** True when [code] may be used for a new move. Unknown and retired codes are both false. */
    fun isSelectable(code: String): Boolean = of(code)?.active == true
  }
}

/**
 * A cell move reason as served to clients.
 *
 * `listSeq` is deliberately not on the wire: the array is returned in display order, so a client
 * renders it as it arrives rather than re-implementing the sort. Inactive entries are included -
 * a client offering a choice filters on [active], a client resolving a historic code does not.
 */
@Schema(description = "A reason a prisoner can be moved between cells")
data class CellMoveReasonType(
  @get:Schema(description = "The code recorded against the movement", example = "GM")
  val code: String,

  @get:Schema(description = "The description to show", example = "General moves")
  val description: String,

  @get:Schema(
    description = "Whether this reason can be chosen for a new move. Retired reasons are still " +
      "returned, because historic movements carry them and need a description",
    example = "true",
  )
  val active: Boolean,
)
