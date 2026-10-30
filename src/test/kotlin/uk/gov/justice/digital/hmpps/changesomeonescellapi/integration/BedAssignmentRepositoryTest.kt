package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * Exercises the bed assignment tables directly. There are no endpoints over them yet (MAPA-481 PR1):
 * the migration and sync endpoints that write them, and their resource tests, follow.
 */
class BedAssignmentRepositoryTest : IntegrationTestBase() {

  @Autowired
  private lateinit var bedAssignmentRepository: BedAssignmentRepository

  @Autowired
  private lateinit var bedAssignmentNomisRepository: BedAssignmentNomisRepository

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  private val cellId = UUID.fromString("0199e3c0-0000-7000-8000-000000000001")
  private val firstAssigned = LocalDateTime.parse("2023-05-01T09:15:00")
  private val moved = LocalDateTime.parse("2023-06-10T14:30:00")

  // Cleared before and after: other tests delete cell_movement rows, which a bed assignment can reference.
  @BeforeEach
  @AfterEach
  fun clear() {
    bedAssignmentNomisRepository.deleteAll()
    bedAssignmentRepository.deleteAll()
  }

  @Test
  fun `saves an assignment from NOMIS with the values it was copied from`() {
    val saved = saveFromNomis(sequence = 1, locationId = cellId, locationKey = "MDI-A-1-001", startAt = firstAssigned, endAt = moved)

    with(bedAssignmentRepository.findById(saved.id!!).orElseThrow()) {
      assertThat(prisonerNumber).isEqualTo("A1234AA")
      assertThat(locationId).isEqualTo(cellId)
      assertThat(locationKey).isEqualTo("MDI-A-1-001")
      assertThat(startAt).isEqualTo(firstAssigned)
      assertThat(startReason).isEqualTo("ADM")
      assertThat(endAt).isEqualTo(moved)
      assertThat(endReason).isNull()
      assertThat(source).isEqualTo(BedAssignmentSource.NOMIS)
      assertThat(recordedBy).isEqualTo("NOMIS_USER")
      assertThat(cellMovementId).isNull()
    }

    with(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(1234L, 1)!!) {
      assertThat(bedAssignmentId).isEqualTo(saved.id)
      assertThat(livingUnitId).isEqualTo(-3009L)
      assertThat(livingUnitDescription).isEqualTo("MDI-A-1-001")
      assertThat(migratedAt).isEqualTo(moved)
    }
  }

  @Test
  fun `keeps an assignment whose location could not be mapped`() {
    val saved = saveFromNomis(sequence = 1, locationId = null, locationKey = "MDI-OLD-WING-1", livingUnitDescription = null)

    with(bedAssignmentRepository.findById(saved.id!!).orElseThrow()) {
      assertThat(locationId).isNull()
      assertThat(locationKey).isEqualTo("MDI-OLD-WING-1")
    }
    assertThat(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(1234L, 1)!!.livingUnitDescription).isNull()
  }

  @Test
  fun `finds a prisoner's assignments in start order`() {
    saveFromNomis(sequence = 2, startAt = moved)
    saveFromNomis(sequence = 1, startAt = firstAssigned, endAt = moved)

    assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt("A1234AA").map { it.startAt })
      .containsExactly(firstAssigned, moved)
  }

  @Test
  fun `rejects a second copy of the same NOMIS row`() {
    saveFromNomis(sequence = 1)

    assertThatThrownBy { saveFromNomis(sequence = 1) }.isInstanceOf(DataIntegrityViolationException::class.java)
  }

  @Test
  fun `deleting an assignment deletes its NOMIS values`() {
    val saved = saveFromNomis(sequence = 1)

    jdbcTemplate.update("delete from bed_assignment where id = ?", saved.id)

    assertThat(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(1234L, 1)).isNull()
  }

  @Test
  fun `occupancy index covers open assignments only`() {
    val definition = jdbcTemplate.queryForObject(
      "select indexdef from pg_indexes where indexname = 'bed_assignment_open_location_idx'",
      String::class.java,
    )

    assertThat(definition).contains("(location_id)").contains("WHERE (end_at IS NULL)")
  }

  private fun saveFromNomis(
    sequence: Int,
    locationId: UUID? = cellId,
    locationKey: String = "MDI-A-1-001",
    livingUnitDescription: String? = locationKey,
    startAt: LocalDateTime = firstAssigned,
    endAt: LocalDateTime? = null,
  ): BedAssignmentEntity {
    val assignment = bedAssignmentRepository.save(
      BedAssignmentEntity(
        prisonerNumber = "A1234AA",
        locationId = locationId,
        locationKey = locationKey,
        startAt = startAt,
        startReason = "ADM",
        endAt = endAt,
        source = BedAssignmentSource.NOMIS,
        recordedBy = "NOMIS_USER",
      ),
    )
    bedAssignmentNomisRepository.saveAndFlush(
      BedAssignmentNomisEntity(
        bedAssignment = assignment,
        nomisBookingId = 1234L,
        nomisSequence = sequence,
        livingUnitId = -3009L,
        livingUnitDescription = livingUnitDescription,
        migratedAt = moved,
      ),
    )
    return assignment
  }
}
