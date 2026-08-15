package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
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
}
