package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilAsserted
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementNomisEntity
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementNomisRepository
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsTopic
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.sqs.publish
import java.time.LocalDateTime

/**
 * Prisoner merged and booking moved events, published to the domain events topic and read back off
 * the `prisonerevents` queue (MAPA-438). RETAINED is the surviving prisoner number, REMOVED the one
 * NOMIS deletes.
 */
class PrisonerEventListenerTest : IntegrationTestBase() {

  @Autowired
  private lateinit var cellMovementRepository: CellMovementRepository

  @Autowired
  private lateinit var cellMovementNomisRepository: CellMovementNomisRepository

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  @MockitoBean
  private lateinit var telemetryClient: TelemetryClient

  private val queue by lazy { hmppsQueueService.findByQueueId("prisonerevents") as HmppsQueue }
  private val topic by lazy { hmppsQueueService.findByTopicId("domainevents") as HmppsTopic }

  @BeforeEach
  fun setUp() {
    cellMovementRepository.deleteAll()
    cellMovementNomisRepository.deleteAll()
    queue.sqsClient.purgeQueue(PurgeQueueRequest.builder().queueUrl(queue.queueUrl).build()).get()
    queue.sqsDlqClient!!.purgeQueue(PurgeQueueRequest.builder().queueUrl(queue.dlqUrl).build()).get()
  }

  @Test
  fun `a merge moves every movement and migrated reason from the retired number to the retained one`() {
    cellMovementRepository.save(aCellMove(REMOVED, BOOKING_ID))
    cellMovementRepository.save(aCellMove(REMOVED, OTHER_BOOKING_ID))
    cellMovementNomisRepository.save(aMigratedMove(REMOVED, BOOKING_ID))
    cellMovementRepository.save(aCellMove(SOMEONE_ELSE, 999L))

    publishMerge(retained = RETAINED, removed = REMOVED)

    awaitTelemetry("cell-movement-prisoner-merge")
    assertThat(cellMovementRepository.findAll().map { it.prisonerNumber })
      .containsExactlyInAnyOrder(RETAINED, RETAINED, SOMEONE_ELSE)
    assertThat(cellMovementNomisRepository.findAll().map { it.prisonerNumber }).containsExactly(RETAINED)
    verify(telemetryClient).trackEvent(
      eq("cell-movement-prisoner-merge"),
      eq(
        mapOf(
          "NOMS-MERGE-FROM" to REMOVED,
          "NOMS-MERGE-TO" to RETAINED,
          "cellMovementsMoved" to "2",
          "migratedReasonsMoved" to "1",
        ),
      ),
      isNull(),
    )
  }

  @Test
  fun `the read by booking and bed assignment returns the retained number after a merge`() {
    cellMovementRepository.save(aCellMove(REMOVED, BOOKING_ID))

    publishMerge(retained = RETAINED, removed = REMOVED)
    awaitTelemetry("cell-movement-prisoner-merge")

    webTestClient.get().uri("/cell-movements/$BOOKING_ID/bed-assignment/$BED_ASSIGNMENT_SEQUENCE")
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.prisonerNumber").isEqualTo(RETAINED)
  }

  @Test
  fun `a merge of a prisoner never moved through DPS is recorded as a no-op`() {
    // The common case, and also every redelivery of a merge already handled.
    cellMovementRepository.save(aCellMove(SOMEONE_ELSE, BOOKING_ID))

    publishMerge(retained = RETAINED, removed = REMOVED)

    awaitTelemetry("cell-movement-prisoner-merge-no-op")
    assertThat(cellMovementRepository.findAll().single().prisonerNumber).isEqualTo(SOMEONE_ELSE)
  }

  @Test
  fun `a booking move moves only that booking and leaves the rest of the prisoner's history alone`() {
    cellMovementRepository.save(aCellMove(REMOVED, BOOKING_ID))
    cellMovementRepository.save(aCellMove(REMOVED, OTHER_BOOKING_ID))
    cellMovementNomisRepository.save(aMigratedMove(REMOVED, BOOKING_ID))
    cellMovementNomisRepository.save(aMigratedMove(REMOVED, OTHER_BOOKING_ID))

    publishBookingMoved(bookingId = BOOKING_ID, movedFrom = REMOVED, movedTo = RETAINED)

    awaitTelemetry("cell-movement-booking-moved")
    assertThat(cellMovementRepository.findAll().associate { it.bookingId to it.prisonerNumber })
      .isEqualTo(mapOf(BOOKING_ID to RETAINED, OTHER_BOOKING_ID to REMOVED))
    assertThat(cellMovementNomisRepository.findAll().associate { it.bookingId to it.prisonerNumber })
      .isEqualTo(mapOf(BOOKING_ID to RETAINED, OTHER_BOOKING_ID to REMOVED))
    verify(telemetryClient).trackEvent(
      eq("cell-movement-booking-moved"),
      eq(
        mapOf(
          "bookingId" to BOOKING_ID.toString(),
          "movedFromNomsNumber" to REMOVED,
          "movedToNomsNumber" to RETAINED,
          "cellMovementsMoved" to "1",
          "migratedReasonsMoved" to "1",
        ),
      ),
      isNull(),
    )
  }

  @Test
  fun `a booking move leaves a migrated reason with no prisoner number alone`() {
    cellMovementNomisRepository.save(aMigratedMove(null, BOOKING_ID))

    publishBookingMoved(bookingId = BOOKING_ID, movedFrom = REMOVED, movedTo = RETAINED)

    awaitTelemetry("cell-movement-booking-moved-no-op")
    assertThat(cellMovementNomisRepository.findAll().single().prisonerNumber).isNull()
  }

  @Test
  fun `a booking move received twice changes nothing the second time`() {
    cellMovementRepository.save(aCellMove(REMOVED, BOOKING_ID))

    publishBookingMoved(bookingId = BOOKING_ID, movedFrom = REMOVED, movedTo = RETAINED)
    awaitTelemetry("cell-movement-booking-moved")
    publishBookingMoved(bookingId = BOOKING_ID, movedFrom = REMOVED, movedTo = RETAINED)
    awaitTelemetry("cell-movement-booking-moved-no-op")

    assertThat(cellMovementRepository.findAll().single().prisonerNumber).isEqualTo(RETAINED)
  }

  @Test
  fun `malformed events are consumed and dropped rather than retried`() {
    cellMovementRepository.save(aCellMove(REMOVED, BOOKING_ID))

    publishMerge(retained = REMOVED, removed = REMOVED)
    topic.publish(
      MERGED,
      """{"eventType":"$MERGED","additionalInformation":{"nomsNumber":"$RETAINED"}}""",
    )
    topic.publish(
      BOOKING_MOVED,
      """{"eventType":"$BOOKING_MOVED","additionalInformation":{"bookingId":"not-a-number","movedFromNomsNumber":"$REMOVED","movedToNomsNumber":"$RETAINED"}}""",
    )

    // A well-formed merge of someone else, published last, so there is something to wait for. An empty
    // queue alone could just mean the topic has not delivered the malformed events yet.
    publishMerge(retained = "A9999ZZ", removed = "A8888YY")
    awaitTelemetry("cell-movement-prisoner-merge-no-op")
    await untilCallTo { queue.sqsClient.countAllMessagesOnQueue(queue.queueUrl).get() } matches { it == 0 }

    assertThat(queue.sqsDlqClient!!.countAllMessagesOnQueue(queue.dlqUrl!!).get()).isZero()
    assertThat(cellMovementRepository.findAll().single().prisonerNumber).isEqualTo(REMOVED)
    verify(telemetryClient, never()).trackEvent(eq("cell-movement-prisoner-merge"), any(), isNull())
    verify(telemetryClient, never()).trackEvent(eq("cell-movement-booking-moved"), any(), isNull())
    verify(telemetryClient, never()).trackEvent(eq("cell-movement-booking-moved-no-op"), any(), isNull())
    verify(telemetryClient, times(1)).trackEvent(eq("cell-movement-prisoner-merge-no-op"), any(), isNull())
  }

  private fun publishMerge(retained: String, removed: String) = topic.publish(
    MERGED,
    """
    {
      "eventType":"$MERGED",
      "additionalInformation":{"nomsNumber":"$retained","removedNomsNumber":"$removed","reason":"MERGE","bookingId":"$BOOKING_ID"},
      "version":1,
      "occurredAt":"2026-10-13T12:00:00+01:00",
      "description":"A prisoner has been merged from $removed to $retained"
    }
    """.trimIndent(),
  )

  private fun publishBookingMoved(bookingId: Long, movedFrom: String, movedTo: String) = topic.publish(
    BOOKING_MOVED,
    """
    {
      "eventType":"$BOOKING_MOVED",
      "additionalInformation":{"bookingId":"$bookingId","movedFromNomsNumber":"$movedFrom","movedToNomsNumber":"$movedTo","bookingStartDateTime":"2026-09-01T10:00:00"},
      "version":1,
      "occurredAt":"2026-10-13T12:00:00+01:00",
      "description":"A NOMIS booking has moved between prisoners"
    }
    """.trimIndent(),
  )

  private fun awaitTelemetry(eventName: String) {
    await untilAsserted { verify(telemetryClient).trackEvent(eq(eventName), any(), isNull()) }
  }

  private fun aCellMove(prisonerNumber: String, bookingId: Long) = CellMovementEntity(
    prisonerNumber = prisonerNumber,
    bookingId = bookingId,
    bedAssignmentSequence = BED_ASSIGNMENT_SEQUENCE,
    fromLocationKey = "MDI-1-1-001",
    toLocationKey = "MDI-1-1-015",
    reasonCode = "ADM",
    commentText = "Moved following an altercation on the wing",
    occurredAt = LocalDateTime.now(clock),
    recordedBy = "TEST_USER",
    status = CellMovementStatus.COMPLETED,
  )

  private fun aMigratedMove(prisonerNumber: String?, bookingId: Long) = CellMovementNomisEntity(
    bookingId = bookingId,
    bedAssignmentSequence = BED_ASSIGNMENT_SEQUENCE,
    caseNoteLegacyId = 1234567L,
    prisonerNumber = prisonerNumber,
  )

  private companion object {
    const val MERGED = "prison-offender-events.prisoner.merged"
    const val BOOKING_MOVED = "prison-offender-events.prisoner.booking.moved"
    const val RETAINED = "A1111AA"
    const val REMOVED = "A2222BB"
    const val SOMEONE_ELSE = "A3333CC"
    const val BOOKING_ID = 1200866L
    const val OTHER_BOOKING_ID = 1200867L
    const val BED_ASSIGNMENT_SEQUENCE = 3
  }
}
