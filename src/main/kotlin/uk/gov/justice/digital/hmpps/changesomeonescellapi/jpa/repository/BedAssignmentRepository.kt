package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentEntity
import java.util.UUID

/**
 * Written only by the NOMIS to DPS migration and sync during coexistence (MAPA-481), apart from the
 * prisoner number following merges and booking moves. Nothing reads it yet; the reads arrive with the
 * endpoints that need them.
 */
@Repository
interface BedAssignmentRepository : JpaRepository<BedAssignmentEntity, UUID> {
  fun findAllByPrisonerNumberOrderByStartAt(prisonerNumber: String): List<BedAssignmentEntity>

  /** Moves every assignment from a prisoner number NOMIS has merged away. Returns the rows moved. */
  @Modifying
  @Query("update BedAssignmentEntity a set a.prisonerNumber = :retained where a.prisonerNumber = :removed")
  fun reassignPrisonerNumber(@Param("removed") removed: String, @Param("retained") retained: String): Int

  /**
   * Moves every assignment from a NOMIS booking to the prisoner number NOMIS has moved it to. Returns the
   * rows moved, so a redelivery reports zero. bed_assignment holds no booking id, so the booking's rows are
   * found through the NOMIS key in bed_assignment_nomis; a row DPS created has none, and never moves.
   */
  @Modifying
  @Query(
    "update BedAssignmentEntity a set a.prisonerNumber = :movedTo where a.prisonerNumber <> :movedTo " +
      "and a.id in (select n.bedAssignmentId from BedAssignmentNomisEntity n where n.nomisBookingId = :bookingId)",
  )
  fun reassignBooking(@Param("bookingId") bookingId: Long, @Param("movedTo") movedTo: String): Int
}
