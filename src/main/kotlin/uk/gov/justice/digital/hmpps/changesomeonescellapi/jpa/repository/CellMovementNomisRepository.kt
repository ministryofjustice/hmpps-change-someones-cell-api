package uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisId

@Repository
interface CellMovementNomisRepository : JpaRepository<CellMovementNomisEntity, CellMovementNomisId>
