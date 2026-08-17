package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.io.Serializable

/**
 * A cell move reason migrated from whereabouts-api's CELL_MOVE_REASON table.
 *
 * Deliberately holds only what the source held. Everything a [CellMovementEntity] carries beyond
 * these three columns - prisoner number, reason code, the explanation, who moved them and when -
 * whereabouts never recorded, so there is nothing honest to put in those fields. The explanation
 * lives only in the case note that [caseNoteLegacyId] points at.
 *
 * Read only in normal operation: written once by the one-off migration, and never again by this
 * service. New movements go in [CellMovementEntity].
 */
@Entity
@Table(name = "cell_movement_nomis")
@IdClass(CellMovementNomisId::class)
class CellMovementNomisEntity(

  @Id
  @Column(name = "booking_id", updatable = false, nullable = false)
  val bookingId: Long,

  @Id
  @Column(name = "bed_assignment_sequence", updatable = false, nullable = false)
  val bedAssignmentSequence: Int,

  /**
   * The numeric case note id, copied unchanged from the source. Still resolvable: the case notes
   * service accepts either a UUID or a legacy id on `GET /case-notes/{personIdentifier}/{id}`,
   * even though it treats the numeric form as deprecated.
   */
  @Column(name = "case_note_legacy_id", nullable = false)
  val caseNoteLegacyId: Long,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as CellMovementNomisEntity
    return bookingId == other.bookingId && bedAssignmentSequence == other.bedAssignmentSequence
  }

  override fun hashCode(): Int = 31 * bookingId.hashCode() + bedAssignmentSequence
}

/**
 * The composite key. It is the natural key from the source table, kept rather than replaced with a
 * surrogate id so that re-running the one-off migration cannot duplicate a row.
 */
data class CellMovementNomisId(
  val bookingId: Long = 0,
  val bedAssignmentSequence: Int = 0,
) : Serializable
