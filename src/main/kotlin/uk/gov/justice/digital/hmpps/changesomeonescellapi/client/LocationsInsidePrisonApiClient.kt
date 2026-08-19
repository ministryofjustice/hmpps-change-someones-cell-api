package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.util.UUID

/**
 * Resolves location keys to their locations-inside-prison UUIDs.
 *
 * A key ({prisonId}-{pathHierarchy}) is mutable - LIP allows codes and hierarchy to be renamed -
 * while the UUID is a location's fixed identity. Movements store both: the key for NOMIS-era APIs
 * and humans, the UUID for identity over time.
 *
 * **Best effort by contract.** Recording a UUID must never block the move it describes, so any
 * failure here returns an empty map and a warning rather than propagating. LIP's own bulk endpoint
 * has the same spirit: keys it does not recognise are silently omitted from the response, not
 * errors.
 */
@Component
class LocationsInsidePrisonApiClient(
  @param:Qualifier("locationsInsidePrisonApiWebClient") private val webClient: WebClient,
) {
  /** The LIP UUID for each resolvable key. Unrecognised keys are absent; on failure the map is empty. */
  fun resolveKeys(keys: Collection<String>): Map<String, UUID> {
    if (keys.isEmpty()) return emptyMap()
    return try {
      webClient
        .post()
        .uri("/locations/keys")
        .bodyValue(keys.distinct())
        .retrieve()
        .bodyToMono<List<LipLocation>>()
        .block()
        .orEmpty()
        .associate { it.key to it.id }
    } catch (e: Exception) {
      log.warn("Could not resolve location keys {} through locations-inside-prison: {}", keys, e.message)
      emptyMap()
    }
  }

  private companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LipLocation(
  val id: UUID,
  val key: String,
)
