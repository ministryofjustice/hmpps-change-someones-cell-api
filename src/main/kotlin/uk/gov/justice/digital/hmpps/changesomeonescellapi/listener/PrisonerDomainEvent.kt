package uk.gov.justice.digital.hmpps.changesomeonescellapi.listener

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class SQSMessage(
  @param:JsonProperty("Type") val type: String,
  @param:JsonProperty("Message") val message: String,
  @param:JsonProperty("MessageId") val messageId: String? = null,
)

/**
 * An inbound HMPPS domain event from the `prisonerevents` queue. One shape covers both event types we
 * consume - they differ only in which `additionalInformation` fields are populated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerDomainEvent(
  val eventType: String,
  val additionalInformation: PrisonerEventAdditionalInformation? = null,
)

/**
 * The union of the `additionalInformation` fields we read across the event types on this queue.
 *
 * - `prison-offender-events.prisoner.merged` populates [nomsNumber] (the *retained* number) and
 *   [removedNomsNumber] (the retired one).
 * - `prison-offender-events.prisoner.booking.moved` populates [bookingId], [movedFromNomsNumber] and
 *   [movedToNomsNumber].
 *
 * Every field is nullable because the producer decides what a given event type carries; the handlers
 * check what they actually need rather than trusting the shape. [bookingId] is read as text and parsed
 * by the handler, so a malformed value is logged and dropped rather than failing deserialisation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrisonerEventAdditionalInformation(
  val nomsNumber: String? = null,
  val removedNomsNumber: String? = null,
  val bookingId: String? = null,
  val movedFromNomsNumber: String? = null,
  val movedToNomsNumber: String? = null,
)
