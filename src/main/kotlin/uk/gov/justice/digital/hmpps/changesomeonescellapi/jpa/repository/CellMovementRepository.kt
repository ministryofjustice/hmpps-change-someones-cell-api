package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import java.time.LocalDateTime
import java.util.UUID

@Repository
interface CellMovementRepository : JpaRepository<CellMovementEntity, UUID> {

  /**
   * Backs the duplicate guard. Deliberately scoped to the same destination and a short window
   * rather than "any movement for this prisoner": prisoners legitimately move back to a cell later
   * in the day, so anything broader would reject real moves.
   */
  fun existsByPrisonerNumberAndToLocationKeyAndOccurredAtAfter(
    prisonerNumber: String,
    toLocationKey: String,
    occurredAt: LocalDateTime,
  ): Boolean

  /**
   * Backs the "what happened" read, which arrives keyed the way NOMIS keys a bed assignment.
   *
   * Returns the most recent match rather than requiring uniqueness. The pair is not unique here:
   * a move that failed leaves a PENDING row behind, and a later attempt that succeeded records the
   * same booking and, once NOMIS assigns it, can carry the same sequence. The completed one is the
   * one that describes what happened, and it is the later of the two.
   */
  fun findFirstByBookingIdAndBedAssignmentSequenceOrderByOccurredAtDesc(
    bookingId: Long,
    bedAssignmentSequence: Int,
  ): CellMovementEntity?

  /** Moves every movement from a prisoner number NOMIS has merged away (MAPA-438). Returns the rows moved. */
  @Modifying
  @Query("update CellMovementEntity m set m.prisonerNumber = :retained where m.prisonerNumber = :removed")
  fun reassignPrisonerNumber(@Param("removed") removed: String, @Param("retained") retained: String): Int

  /**
   * Moves every movement for a booking to the prisoner number NOMIS has moved it to (MAPA-438). Returns
   * the rows moved, so a redelivery reports zero.
   */
  @Modifying
  @Query("update CellMovementEntity m set m.prisonerNumber = :movedTo where m.bookingId = :bookingId and m.prisonerNumber <> :movedTo")
  fun reassignBooking(@Param("bookingId") bookingId: Long, @Param("movedTo") movedTo: String): Int
}
