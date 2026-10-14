package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId

/**
 * The rows were written by the completed backfill. The only thing that changes them since is NOMIS
 * moving a booking to another prisoner number, by a merge or a booking move (MAPA-438).
 */
@Repository
interface CellMovementNomisRepository : JpaRepository<CellMovementNomisEntity, CellMovementNomisId> {

  /** Moves every migrated reason from a prisoner number NOMIS has merged away. Returns the rows moved. */
  @Modifying
  @Query("update CellMovementNomisEntity m set m.prisonerNumber = :retained where m.prisonerNumber = :removed")
  fun reassignPrisonerNumber(@Param("removed") removed: String, @Param("retained") retained: String): Int

  /**
   * Moves every migrated reason for a booking to the prisoner number NOMIS has moved it to. Returns the
   * rows moved, so a redelivery reports zero. A row the backfill could not put a prisoner number to is
   * left alone: it holds no number to move.
   */
  @Modifying
  @Query("update CellMovementNomisEntity m set m.prisonerNumber = :movedTo where m.bookingId = :bookingId and m.prisonerNumber <> :movedTo")
  fun reassignBooking(@Param("bookingId") bookingId: Long, @Param("movedTo") movedTo: String): Int
}
