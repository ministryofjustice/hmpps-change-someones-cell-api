package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class LocationsInsidePrisonExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val locationsInsidePrison = LocationsInsidePrisonMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    locationsInsidePrison.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    locationsInsidePrison.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    locationsInsidePrison.stop()
  }
}

class LocationsInsidePrisonMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8095
    const val FROM_LOCATION_ID = "2475f250-434a-4257-afe7-b911f1773a4d"
    const val TO_LOCATION_ID = "de91dfa7-821f-4552-a427-bf2f32eafeb0"
  }

  fun stubHealthPing(status: Int) {
    stubFor(
      get("/health/ping").willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(if (status == 200) """{"status":"UP"}""" else """{"status":"DOWN"}""")
          .withStatus(status),
      ),
    )
  }

  /**
   * The bulk key lookup. LIP's contract is that unrecognised keys are silently omitted from the
   * response rather than errors, so stubbing a subset of what was asked for is how "key not found"
   * is expressed.
   */
  fun stubResolveKeys(vararg keyToId: Pair<String, String>) {
    val body = keyToId.joinToString(",", "[", "]") { (key, id) ->
      """{"id": "$id", "prisonId": "${key.substringBefore('-')}", "key": "$key", "pathHierarchy": "${key.substringAfter('-')}", "locationType": "CELL"}"""
    }
    stubFor(
      post(urlPathEqualTo("/locations/keys")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(body)
          .withStatus(200),
      ),
    )
  }

  fun stubResolveKeysFails(status: Int = 500) {
    stubFor(
      post(urlPathEqualTo("/locations/keys")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":$status}""")
          .withStatus(status),
      ),
    )
  }
}
