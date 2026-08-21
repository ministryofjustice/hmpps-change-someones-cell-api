package uk.gov.justice.digital.hmpps.changesomeonescellapi.dto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test

/**
 * Pins the reference data so that changing it is a reviewed diff rather than a typo.
 *
 * This is not drift detection - it cannot be, since the values live here. **Before adding or
 * changing a code, confirm it exists in both prison-api's `CHG_HOUS_RSN` domain and
 * offender-case-notes' `MOVED_CELL` sub-types.** A code NOMIS rejects fails the move outright; a
 * code case-notes rejects fails *after* the move has already happened in NOMIS, leaving a movement
 * with no case note and the caller a 201. To check all three sets:
 *
 * ```
 * GET  {prison-api}/api/reference-domains/domains/CHG_HOUS_RSN   (Page-Limit: 1000, no role needed)
 * GET  {case-notes}/case-notes/types?includeInactive=true        (then filter to MOVED_CELL)
 * ```
 *
 * Values below captured from prod prison-api on 2026-08-20.
 */
class CellMoveReasonCodeTest {

  @Test
  fun `the reasons are exactly these, in this order`() {
    assertThat(CellMoveReasonCode.entries.sortedBy { it.listSeq })
      .extracting({ it.code }, { it.description }, { it.active })
      .containsExactly(
        tuple("ADM", "Administrative", false),
        tuple("BEH", "Behaviour", false),
        tuple("CLA", "Classification/Re-Classification", false),
        tuple("CON", "Conflict with Other Prisoners", false),
        tuple("VP", "Vulnerable Prisoner", false),
        tuple("LN", "Local Needs", false),
        tuple("RAIM", "Reception and induction moves", true),
        tuple("SS", "Someone’s safety", true),
        tuple("SPP", "Security of the prison or other people", true),
        tuple("HOSP", "Healthcare", true),
        tuple("PCM", "Maintenance of the prison or cell", true),
        tuple("GM", "General moves", true),
      )
  }

  /**
   * The cell swap journey records ADM without asking the user. It is inactive - deliberately, it
   * is a retired reason - so this asserts only that it still *exists*. Remove ADM from the list and
   * every cell swap starts writing a reason code this service does not recognise.
   */
  @Test
  fun `ADM exists, because cell swap records it`() {
    assertThat(CellMoveReasonCode.of("ADM")).isNotNull
  }

  @Test
  fun `listSeq is unique and codes are unique`() {
    assertThat(CellMoveReasonCode.entries.map { it.listSeq }).doesNotHaveDuplicates()
    assertThat(CellMoveReasonCode.entries.map { it.code }).doesNotHaveDuplicates()
  }

  @Test
  fun `codes fit the case note subType limit`() {
    // The code is sent on to offender-case-notes as the MOVED_CELL subType, capped at 12.
    assertThat(CellMoveReasonCode.entries).allSatisfy { assertThat(it.code.length).isLessThanOrEqualTo(12) }
  }

  @Test
  fun `only active reasons are selectable for a new move`() {
    assertThat(CellMoveReasonCode.isSelectable("GM")).isTrue()
    assertThat(CellMoveReasonCode.isSelectable("ADM")).isFalse()
    assertThat(CellMoveReasonCode.isSelectable("NOPE")).isFalse()
    assertThat(CellMoveReasonCode.isSelectable("")).isFalse()
  }
}
