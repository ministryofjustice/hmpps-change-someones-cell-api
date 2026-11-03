package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentNomisEntity
import java.util.UUID

@Repository
interface BedAssignmentNomisRepository : JpaRepository<BedAssignmentNomisEntity, UUID> {

  /** How the migration and the sync find a NOMIS row they have already brought across. */
  fun findByNomisBookingIdAndNomisSequence(nomisBookingId: Long, nomisSequence: Int): BedAssignmentNomisEntity?

  /** The NOMIS rows DPS currently holds against a prisoner, which a sync of that prisoner replaces. */
  fun findAllByBedAssignmentPrisonerNumber(prisonerNumber: String): List<BedAssignmentNomisEntity>

  /**
   * The NOMIS rows DPS holds for these bookings, whoever they are held against. A booking NOMIS has moved
   * to another prisoner is found here, and its rows follow it rather than being created again.
   */
  fun findAllByNomisBookingIdIn(nomisBookingIds: Collection<Long>): List<BedAssignmentNomisEntity>
}
