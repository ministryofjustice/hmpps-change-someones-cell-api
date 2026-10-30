package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import org.hibernate.Hibernate
import java.time.LocalDateTime
import java.util.UUID

/**
 * The NOMIS BED_ASSIGNMENT_HISTORIES values a [BedAssignmentEntity] was migrated or synced from,
 * unchanged.
 *
 * Shares the bed assignment's id. The link is one way, from here to the assignment: a two-way
 * one-to-one cannot be lazy on the side that does not own the key, so every history read would load
 * this row too.
 *
 * (nomisBookingId, nomisSequence) is unique. It is how the migration and the sync find a row they have
 * already brought across, which is what makes both safe to re-run.
 */
@Entity
@Table(name = "bed_assignment_nomis")
class BedAssignmentNomisEntity(

  @MapsId
  @OneToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "bed_assignment_id")
  val bedAssignment: BedAssignmentEntity,

  @Column(updatable = false)
  val nomisBookingId: Long,

  @Column(updatable = false)
  val nomisSequence: Int,

  // Kept even when NOMIS no longer holds the location.
  var livingUnitId: Long,

  // Null when NOMIS no longer holds the location.
  var livingUnitDescription: String? = null,

  @Column(updatable = false)
  val migratedAt: LocalDateTime,
) {
  @Id
  @Column(name = "bed_assignment_id")
  var bedAssignmentId: UUID? = null

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as BedAssignmentNomisEntity
    return bedAssignmentId != null && bedAssignmentId == other.bedAssignmentId
  }

  override fun hashCode(): Int = javaClass.hashCode()
}
