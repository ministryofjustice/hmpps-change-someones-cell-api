package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentEntity
import java.util.UUID

/**
 * Written only by the NOMIS to DPS migration and sync during coexistence (MAPA-481). Nothing reads it
 * yet; the reads arrive with the endpoints that need them.
 */
@Repository
interface BedAssignmentRepository : JpaRepository<BedAssignmentEntity, UUID> {
  fun findAllByPrisonerNumberOrderByStartAt(prisonerNumber: String): List<BedAssignmentEntity>
}
