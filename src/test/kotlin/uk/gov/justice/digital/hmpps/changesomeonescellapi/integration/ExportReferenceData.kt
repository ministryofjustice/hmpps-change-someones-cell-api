package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.CellMoveReasonCode
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementType
import java.io.File

/**
 * Writes reference-data.csv, the permitted values behind every coded column in the schema.
 *
 * Every code here is stored as an unconstrained varchar with no check constraint - deliberately, so
 * that adding a state stays a code change rather than a migration - which means a consumer reading
 * the schema alone sees a varchar(20) with no idea which values are legal.
 *
 * Reason code descriptions are read from [CellMoveReasonCode]'s own `description` property rather than
 * restated here, so they cannot drift from the code. That enum is already pinned by
 * CellMoveReasonCodeTest; generating from it keeps one source of truth rather than creating a second.
 *
 * The status and movement type enums carry their descriptions only as KDoc, so those are written out
 * below. Both maps are typed on the enum, so a new value cannot be added without a description - the
 * compiler rejects it.
 *
 * Excluded from normal test runs; run with `./gradlew -Pinit-db=true test` (see build.gradle.kts).
 */
class ExportReferenceData {

  @Test
  fun `exports reference data`() {
    val rows = mutableListOf<Row>()

    rows += CellMoveReasonCode.entries.map {
      Row(
        columnRef = "cell_movement.reason_code",
        code = it.code,
        description = it.description,
        notes = if (it.active) {
          "selectable for a new move"
        } else {
          "retired; not selectable, but still present on movements migrated from whereabouts"
        },
      )
    }

    rows += enumRows("cell_movement.status", CellMovementStatus.entries, STATUSES)
    rows += enumRows("cell_movement.movement_type", CellMovementType.entries, MOVEMENT_TYPES)

    assertThat(rows.filter { it.description.isBlank() }.map { "${it.columnRef}.${it.code}" })
      .describedAs("every exported value needs a description - an undescribed code is not reference data")
      .isEmpty()

    val output = File(System.getProperty("referenceDataOutput") ?: "reference-data.csv")
    output.writeText(
      buildString {
        append("column_ref,code,description,notes\n")
        rows.forEach { append("${it.toCsv()}\n") }
      },
      Charsets.UTF_8,
    )
    println("Wrote ${rows.size} reference data rows to ${output.absolutePath}")
  }

  private fun <T : Enum<T>> enumRows(
    columnRef: String,
    values: List<T>,
    descriptions: Map<T, String>,
  ): List<Row> = values.map { Row(columnRef, it.name, descriptions.getValue(it), "") }

  private data class Row(
    val columnRef: String,
    val code: String,
    val description: String,
    val notes: String = "",
  ) {
    fun toCsv() = listOf(columnRef, code, description, notes).joinToString(",") { escape(it) }

    private fun escape(value: String) = "\"${value.replace("\"", "\"\"")}\""
  }

  private companion object {
    val STATUSES = mapOf(
      CellMovementStatus.PENDING to "Recorded here, but the move has not been confirmed in NOMIS yet. A row left in this state means the move failed.",
      CellMovementStatus.COMPLETED to "The prisoner was moved in NOMIS. A case note may or may not exist - check case_note_uuid.",
      CellMovementStatus.CASE_NOTE_FAILED to "The prisoner was moved but the case note could not be created. The move is not undone; comment_text is held here so the case note can be recreated later.",
    )

    val MOVEMENT_TYPES = mapOf(
      CellMovementType.CELL_MOVE to "A move into a named cell, with a reason and an explanation, which also creates a case note.",
      CellMovementType.CELL_SWAP to "A move out to the prison's virtual CSWAP location to free the cell. Carries no explanation and creates no case note, because the journey does not ask for one.",
    )
  }
}
