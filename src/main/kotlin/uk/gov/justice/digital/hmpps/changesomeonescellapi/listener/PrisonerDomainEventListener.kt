package uk.gov.justice.digital.hmpps.changesomeonescellapi.listener

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.changesomeonescellapi.service.PrisonerMergeService

/**
 * Consumes HMPPS domain events from the `prisonerevents` SQS queue, which is subscribed to the shared
 * domain events topic (MAPA-438).
 *
 * Every cell movement carries the prisoner number it was recorded against. NOMIS changes which number
 * a booking belongs to in two ways, and both have to reach us or our records are stranded under a
 * number nothing will ask for:
 *
 * - **`prison-offender-events.prisoner.merged`** - NOMIS has resolved two prisoner numbers for the
 *   same person into one. Everything under the retired number moves to the retained one.
 * - **`prison-offender-events.prisoner.booking.moved`** - NOMIS has moved one booking from one
 *   prisoner number to another. Everything recorded against that booking moves with it.
 *
 * Booking moves are taken from the topic directly rather than through Syscon's NOMIS sync, as CSRA and
 * property do. Those services have a NOMIS sync and mapping tables to correct; this one has neither,
 * and already stores the booking id, so the event alone says exactly which rows to move.
 *
 * This is deliberately the *only* `@SqsListener` on the queue. A second listener class naming the same
 * queue would create a second container polling it, and SQS delivers each message to exactly one
 * consumer, so roughly half of each event type would land in the wrong handler and be silently
 * dropped. One listener, dispatching on `eventType`.
 */
@Service
class PrisonerDomainEventListener(
  private val objectMapper: ObjectMapper,
  private val prisonerMergeService: PrisonerMergeService,
) {

  @SqsListener("prisonerevents", factory = "hmppsQueueContainerFactoryProxy")
  fun onDomainEvent(rawMessage: String) {
    val sqsMessage = objectMapper.readValue<SQSMessage>(rawMessage)
    val event = objectMapper.readValue<PrisonerDomainEvent>(sqsMessage.message)
    when (event.eventType) {
      PRISONER_MERGED_EVENT_TYPE -> handlePrisonerMerged(event)
      BOOKING_MOVED_EVENT_TYPE -> handleBookingMoved(event)
      else -> log.debug("Ignoring domain event of type {}", event.eventType)
    }
  }

  /**
   * Anything malformed - a missing number, or a "merge" of a number into itself - is logged and
   * dropped rather than thrown: throwing would retry and eventually dead-letter a message that will
   * never become valid.
   */
  private fun handlePrisonerMerged(event: PrisonerDomainEvent) {
    val retained = event.additionalInformation?.nomsNumber
    val removed = event.additionalInformation?.removedNomsNumber
    if (retained.isNullOrBlank() || removed.isNullOrBlank()) {
      log.warn("Ignoring {} with missing retained or removed prisoner number", event.eventType)
      return
    }
    if (retained == removed) {
      log.warn("Ignoring {} that merges {} into itself", event.eventType, retained)
      return
    }
    prisonerMergeService.prisonerMerged(retained = retained, removed = removed)
  }

  /** Malformed events are dropped, for the same reason as a malformed merge. */
  private fun handleBookingMoved(event: PrisonerDomainEvent) {
    val bookingId = event.additionalInformation?.bookingId?.toLongOrNull()
    val movedFrom = event.additionalInformation?.movedFromNomsNumber
    val movedTo = event.additionalInformation?.movedToNomsNumber
    if (bookingId == null || movedFrom.isNullOrBlank() || movedTo.isNullOrBlank()) {
      log.warn("Ignoring {} with missing booking id or prisoner number", event.eventType)
      return
    }
    if (movedFrom == movedTo) {
      log.warn("Ignoring {} that moves booking {} to the prisoner it is already on", event.eventType, bookingId)
      return
    }
    prisonerMergeService.bookingMoved(bookingId = bookingId, movedFrom = movedFrom, movedTo = movedTo)
  }

  private companion object {
    private const val PRISONER_MERGED_EVENT_TYPE = "prison-offender-events.prisoner.merged"
    private const val BOOKING_MOVED_EVENT_TYPE = "prison-offender-events.prisoner.booking.moved"
    private val log = LoggerFactory.getLogger(PrisonerDomainEventListener::class.java)
  }
}
