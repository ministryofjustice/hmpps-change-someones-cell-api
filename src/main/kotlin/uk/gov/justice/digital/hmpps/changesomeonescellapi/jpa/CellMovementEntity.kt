package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.Hibernate
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.helper.GeneratedUuidV7
import java.time.LocalDateTime
import java.util.UUID

/**
 * A prisoner being moved to a different cell.
 *
 * Holds the movement that this service performs. Rows migrated from whereabouts-api's
 * CELL_MOVE_REASON carry far less - only a booking id, bed assignment sequence and numeric case
 * note id - so they live in a separate side table rather than being forced into these columns.
 */
@Entity
@Table(name = "cell_movement")
class CellMovementEntity(

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,

  var prisonerNumber: String,

  // Resolved from prisoner-search rather than accepted from the caller: bookingId is a NOMIS-only
  // concept and is not part of this service's API contract.
  var bookingId: Long,

  // Set once prison-api confirms the move. Stays null if the prisoner was already in the
  // destination cell, which prison-api treats as a successful no-op.
  var bedAssignmentSequence: Int? = null,

  // Best known at the time of the move. prisoner-search lags NOMIS slightly, so this is not
  // authoritative.
  var fromLocationKey: String? = null,

  var toLocationKey: String,

  var reasonCode: String,

  var commentText: String,

  // The case notes service is UUID canonical. The legacy numeric id is only for migrated rows.
  var caseNoteUuid: UUID? = null,
  var caseNoteLegacyId: Long? = null,

  var occurredAt: LocalDateTime,

  var recordedBy: String,

  @Enumerated(EnumType.STRING)
  var status: CellMovementStatus = CellMovementStatus.PENDING,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CellMovementEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
