package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentNomisEntity
import java.util.UUID

@Repository
interface BedAssignmentNomisRepository : JpaRepository<BedAssignmentNomisEntity, UUID> {

  /** How the migration and the sync find a NOMIS row they have already brought across. */
  fun findByNomisBookingIdAndNomisSequence(nomisBookingId: Long, nomisSequence: Int): BedAssignmentNomisEntity?
}
