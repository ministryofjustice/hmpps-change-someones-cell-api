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
 * A period a prisoner was assigned to a location. An open row ([endAt] null) is a current assignment.
 *
 * This is the state, where [CellMovementEntity] is the command: a cell movement records a move this
 * service performed, and a bed assignment records where the prisoner was as a result, whoever made the
 * change. During coexistence only the NOMIS to DPS sync writes these rows (MAPA-291 section 7.1).
 *
 * A row from NOMIS also has a [BedAssignmentNomisEntity] holding the values it was copied from.
 */
@Entity
@Table(name = "bed_assignment")
class BedAssignmentEntity(

  @Id
  @GeneratedUuidV7
  @Column(name = "id", updatable = false, nullable = false)
  val id: UUID? = null,

  var prisonerNumber: String,

  var bookingId: Long,

  // The locations-inside-prison UUID. Null when the NOMIS living unit has no equivalent there: the row
  // is kept, identified only by locationKey.
  var locationId: UUID? = null,

  // {prisonId}-{pathHierarchy} at the time, or the NOMIS description verbatim when it could not be mapped.
  var locationKey: String,

  var startAt: LocalDateTime,

  /** A CHG_HOUS_RSN code. */
  var startReason: String? = null,

  var endAt: LocalDateTime? = null,

  /** A CHG_HOUS_RSN code. NOMIS records none, so only an assignment DPS ends can have one. */
  var endReason: String? = null,

  @Enumerated(EnumType.STRING)
  var source: BedAssignmentSource,

  var recordedBy: String,

  // The move that opened this assignment, when this service made it. Not a relationship: nothing
  // navigates from an assignment to its command, and loading one would cost a join on every history read.
  var cellMovementId: UUID? = null,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as BedAssignmentEntity
    return id != null && id == other.id
  }

  override fun hashCode(): Int = javaClass.hashCode()
}

enum class BedAssignmentSource {
  NOMIS,
  DPS,
}
