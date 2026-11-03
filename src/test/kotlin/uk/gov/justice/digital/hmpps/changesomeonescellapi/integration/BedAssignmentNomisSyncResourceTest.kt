package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.ParameterizedTypeReference
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentNomisMapping
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.BedAssignmentSyncResponse
import uk.gov.justice.digital.hmpps.changesomeonescellapi.dto.NomisBedAssignment
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.BedAssignmentSource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.BedAssignmentRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * The NOMIS to DPS migration and sync of bed assignment history (MAPA-481).
 */
class BedAssignmentNomisSyncResourceTest : IntegrationTestBase() {

  @Autowired
  private lateinit var bedAssignmentRepository: BedAssignmentRepository

  @Autowired
  private lateinit var bedAssignmentNomisRepository: BedAssignmentNomisRepository

  @Autowired
  private lateinit var cellMovementRepository: CellMovementRepository

  private val syncRole = listOf("ROLE_CELL_MOVEMENTS__SYNC__RW")
  private val cellId = UUID.fromString("0199e3c0-0000-7000-8000-000000000001")
  private val otherCellId = UUID.fromString("0199e3c0-0000-7000-8000-000000000002")
  private val admitted = LocalDateTime.parse("2023-05-01T09:15:00")
  private val moved = LocalDateTime.parse("2023-06-10T14:30:00")
  private val now = LocalDateTime.now(TestBase.clock)

  // Cleared before and after, bed assignments first: they can reference a cell movement.
  @BeforeEach
  @AfterEach
  fun clear() {
    bedAssignmentNomisRepository.deleteAll()
    bedAssignmentRepository.deleteAll()
    cellMovementRepository.deleteAll()
  }

  @Nested
  inner class Security {
    @Test
    fun `returns 401 without a token`() {
      webTestClient.post().uri("/nomis-sync/bed-assignments/migrate/$PRISONER_NUMBER")
        .bodyValue(listOf(firstCell()))
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `migrate returns 403 without the sync role`() {
      webTestClient.post().uri("/nomis-sync/bed-assignments/migrate/$PRISONER_NUMBER")
        .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RW")))
        .bodyValue(listOf(firstCell()))
        .exchange()
        .expectStatus().isForbidden
    }

    @Test
    fun `sync returns 403 without the sync role`() {
      webTestClient.put().uri("/nomis-sync/bed-assignments/sync/$PRISONER_NUMBER")
        .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RW")))
        .bodyValue(listOf(firstCell()))
        .exchange()
        .expectStatus().isForbidden
    }
  }

  @Nested
  inner class Validation {
    @Test
    fun `returns 400 for a badly formed prisoner number`() {
      webTestClient.put().uri("/nomis-sync/bed-assignments/sync/not-a-prisoner")
        .headers(setAuthorisation(roles = syncRole))
        .bodyValue(listOf(firstCell()))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 400 when the same NOMIS row is sent twice`() {
      webTestClient.put().uri("/nomis-sync/bed-assignments/sync/$PRISONER_NUMBER")
        .headers(setAuthorisation(roles = syncRole))
        .bodyValue(listOf(firstCell(), firstCell()))
        .exchange()
        .expectStatus().isBadRequest
        .expectBody().jsonPath("$.userMessage").value<String> {
          assertThat(it).contains("booking $BOOKING_ID and sequence 1 was sent more than once")
        }

      assertThat(bedAssignmentRepository.count()).isZero()
    }

    @Test
    fun `returns 400 when a row has no creating user`() {
      webTestClient.put().uri("/nomis-sync/bed-assignments/sync/$PRISONER_NUMBER")
        .headers(setAuthorisation(roles = syncRole))
        .bodyValue(listOf(firstCell().copy(createdBy = " ")))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 400 when a reason code is too long`() {
      webTestClient.put().uri("/nomis-sync/bed-assignments/sync/$PRISONER_NUMBER")
        .headers(setAuthorisation(roles = syncRole))
        .bodyValue(listOf(firstCell().copy(assignmentReasonCode = "THIRTEEN_CHAR")))
        .exchange()
        .expectStatus().isBadRequest
    }
  }

  @Nested
  inner class Migrate {
    @Test
    fun `stores each NOMIS row and returns its DPS id with the NOMIS key`() {
      val mappings = migrate(firstCell(), secondCell())

      val stored = bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)
      assertThat(mappings).containsExactlyInAnyOrder(
        BedAssignmentNomisMapping(id = stored[0].id!!, bookingId = BOOKING_ID, sequence = 1),
        BedAssignmentNomisMapping(id = stored[1].id!!, bookingId = BOOKING_ID, sequence = 2),
      )

      with(stored[0]) {
        assertThat(locationId).isEqualTo(cellId)
        assertThat(locationKey).isEqualTo("MDI-A-1-001")
        assertThat(startAt).isEqualTo(admitted)
        assertThat(startReason).isEqualTo("ADM")
        assertThat(endAt).isEqualTo(moved)
        assertThat(endReason).isNull()
        assertThat(source).isEqualTo(BedAssignmentSource.NOMIS)
        assertThat(recordedBy).isEqualTo("NOMIS_USER")
        assertThat(cellMovementId).isNull()
      }
      assertThat(stored[1].endAt).isNull()

      with(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(BOOKING_ID, 1)!!) {
        assertThat(bedAssignmentId).isEqualTo(stored[0].id)
        assertThat(livingUnitId).isEqualTo(-3009L)
        assertThat(livingUnitDescription).isEqualTo("MDI-A-1-001")
        assertThat(migratedAt).isEqualTo(now)
      }
    }

    @Test
    fun `keeps a row whose location has no locations-inside-prison equivalent`() {
      migrate(firstCell().copy(locationId = null, livingUnitDescription = "MDI-OLD-WING-1"))

      with(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER).single()) {
        assertThat(locationId).isNull()
        assertThat(locationKey).isEqualTo("MDI-OLD-WING-1")
      }
    }

    @Test
    fun `keeps a row whose location NOMIS no longer holds`() {
      migrate(firstCell().copy(locationId = null, livingUnitDescription = null))

      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER).single().locationKey).isEqualTo("-3009")
      assertThat(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(BOOKING_ID, 1)!!.livingUnitDescription).isNull()
    }

    @Test
    fun `migrating a prisoner again keeps the same ids`() {
      val first = migrate(firstCell(), secondCell())
      val again = migrate(firstCell(), secondCell())

      assertThat(again).containsExactlyInAnyOrderElementsOf(first)
      assertThat(bedAssignmentRepository.count()).isEqualTo(2)
    }

    @Test
    fun `links a row to the move this service made`() {
      val movement = cellMovementRepository.save(aCellMovement(bedAssignmentSequence = 2))

      migrate(firstCell(), secondCell())

      val stored = bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)
      assertThat(stored.map { it.cellMovementId }).containsExactly(null, movement.id)
    }
  }

  @Nested
  inner class Sync {
    @Test
    fun `creates, updates in place and deletes to match what NOMIS holds`() {
      val migrated = migrate(firstCell(), secondCell()).associateBy { it.sequence }

      val result = sync(
        PRISONER_NUMBER,
        firstCell(),
        secondCell().copy(assignmentEndDateTime = now.minusDays(1)),
        thirdCell(),
      )

      assertThat(result.updated).containsExactlyInAnyOrder(migrated[1], migrated[2])
      assertThat(result.created.single().sequence).isEqualTo(3)
      assertThat(result.deleted).isEmpty()

      val stored = bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)
      assertThat(stored.map { it.id }).containsExactly(migrated[1]!!.id, migrated[2]!!.id, result.created.single().id)
      assertThat(stored[1].endAt).isEqualTo(now.minusDays(1))

      val afterRemoval = sync(PRISONER_NUMBER, firstCell(), secondCell())

      assertThat(afterRemoval.deleted).containsExactly(result.created.single())
      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)).hasSize(2)
      assertThat(bedAssignmentNomisRepository.findByNomisBookingIdAndNomisSequence(BOOKING_ID, 3)).isNull()
    }

    @Test
    fun `repeating a sync changes nothing`() {
      sync(PRISONER_NUMBER, firstCell(), secondCell())
      val before = snapshot()

      val again = sync(PRISONER_NUMBER, firstCell(), secondCell())

      assertThat(again.created).isEmpty()
      assertThat(again.deleted).isEmpty()
      assertThat(snapshot()).isEqualTo(before)
    }

    @Test
    fun `an older sync arriving late leaves DPS as that NOMIS read had it, and the next sync restores it`() {
      val current = arrayOf(firstCell(), secondCell().copy(assignmentEndDateTime = now.minusDays(1)), thirdCell())
      sync(PRISONER_NUMBER, *current)
      val latest = snapshot()

      sync(PRISONER_NUMBER, firstCell(), secondCell())
      sync(PRISONER_NUMBER, *current)

      assertThat(snapshot().map { it.copy(id = null) }).isEqualTo(latest.map { it.copy(id = null) })
    }

    @Test
    fun `follows a booking NOMIS has moved to another prisoner`() {
      val migrated = migrate(firstCell(), secondCell())

      val result = sync(OTHER_PRISONER_NUMBER, firstCell(), secondCell())

      assertThat(result.updated).containsExactlyInAnyOrderElementsOf(migrated)
      assertThat(result.created).isEmpty()
      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)).isEmpty()
      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(OTHER_PRISONER_NUMBER).map { it.id })
        .containsExactlyInAnyOrderElementsOf(migrated.map { it.id })
    }

    @Test
    fun `a prisoner whose only booking has moved away is left with nothing`() {
      val migrated = migrate(firstCell(), secondCell())

      val result = sync(PRISONER_NUMBER)

      assertThat(result.deleted).containsExactlyInAnyOrderElementsOf(migrated)
      assertThat(bedAssignmentRepository.count()).isZero()
      assertThat(bedAssignmentNomisRepository.count()).isZero()
    }

    @Test
    fun `leaves other prisoners alone`() {
      sync(OTHER_PRISONER_NUMBER, firstCell().copy(bookingId = OTHER_BOOKING_ID))

      sync(PRISONER_NUMBER, firstCell())

      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(OTHER_PRISONER_NUMBER)).hasSize(1)
      assertThat(bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER)).hasSize(1)
    }
  }

  private fun migrate(vararg assignments: NomisBedAssignment): List<BedAssignmentNomisMapping> = webTestClient.post()
    .uri("/nomis-sync/bed-assignments/migrate/$PRISONER_NUMBER")
    .headers(setAuthorisation(roles = syncRole))
    .bodyValue(assignments.toList())
    .exchange()
    .expectStatus().isCreated
    .expectBody(object : ParameterizedTypeReference<List<BedAssignmentNomisMapping>>() {})
    .returnResult().responseBody!!

  private fun sync(prisonerNumber: String, vararg assignments: NomisBedAssignment): BedAssignmentSyncResponse = webTestClient.put()
    .uri("/nomis-sync/bed-assignments/sync/$prisonerNumber")
    .headers(setAuthorisation(roles = syncRole))
    .bodyValue(assignments.toList())
    .exchange()
    .expectStatus().isOk
    .expectBody(BedAssignmentSyncResponse::class.java)
    .returnResult().responseBody!!

  private fun snapshot() = bedAssignmentRepository.findAllByPrisonerNumberOrderByStartAt(PRISONER_NUMBER).map { it.toRow() }

  private data class Row(val id: UUID?, val locationId: UUID?, val locationKey: String, val startAt: LocalDateTime, val endAt: LocalDateTime?)

  private fun BedAssignmentEntity.toRow() = Row(id, locationId, locationKey, startAt, endAt)

  private fun firstCell() = NomisBedAssignment(
    bookingId = BOOKING_ID,
    sequence = 1,
    livingUnitId = -3009,
    livingUnitDescription = "MDI-A-1-001",
    locationId = cellId,
    assignmentDateTime = admitted,
    assignmentEndDateTime = moved,
    assignmentReasonCode = "ADM",
    createdBy = "NOMIS_USER",
  )

  private fun secondCell() = NomisBedAssignment(
    bookingId = BOOKING_ID,
    sequence = 2,
    livingUnitId = -3010,
    livingUnitDescription = "MDI-A-1-002",
    locationId = otherCellId,
    assignmentDateTime = moved,
    assignmentReasonCode = "GM",
    createdBy = "NOMIS_USER",
  )

  private fun thirdCell() = firstCell().copy(sequence = 3, assignmentDateTime = now.minusDays(1), assignmentEndDateTime = null)

  private fun aCellMovement(bedAssignmentSequence: Int) = CellMovementEntity(
    prisonerNumber = PRISONER_NUMBER,
    bookingId = BOOKING_ID,
    bedAssignmentSequence = bedAssignmentSequence,
    fromLocationKey = "MDI-A-1-001",
    toLocationKey = "MDI-A-1-002",
    reasonCode = "GM",
    occurredAt = moved,
    recordedBy = "DPS_USER",
    status = CellMovementStatus.COMPLETED,
  )

  private companion object {
    const val PRISONER_NUMBER = "A1234AA"
    const val OTHER_PRISONER_NUMBER = "B5678BB"
    const val BOOKING_ID = 1234L
    const val OTHER_BOOKING_ID = 5678L
  }
}
