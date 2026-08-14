package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.changesomeonescellapi.SYSTEM_USERNAME
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.hmpps.kotlin.auth.AuthAwareTokenConverter
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper

/**
 * MAPA-277 acceptance criterion: "HMPPS Auth client can obtain a token that carries the end
 * user's username."
 *
 * This is the whole reason WebClientConfiguration overrides the autoconfigured
 * OAuth2AuthorizedClientManager. prison-api's cell move is @ProxyUser and offender-case-notes
 * refuses MOVED_CELL without a NOMIS user, so a token minted for the bare system client is not
 * good enough. Asserting on the form body of the token request is the only way to see that the
 * username actually left the building.
 */
class UsernamePropagationTest : IntegrationTestBase() {

  @Autowired
  @Qualifier("prisonApiWebClient")
  private lateinit var prisonApiWebClient: WebClient

  @Autowired
  private lateinit var authorizedClientService: OAuth2AuthorizedClientService

  @Autowired
  private lateinit var jwtAuthorisationHelper: JwtAuthorisationHelper

  @Autowired
  private lateinit var jwtDecoder: JwtDecoder

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
    prisonApi.stubAnyGet("/api/some-endpoint")
    // Tokens are cached per (registration, principal), so a token cached by an earlier test
    // would stop this one issuing a token request at all.
    authorizedClientService.removeAuthorizedClient(SYSTEM_USERNAME, USER)
    authorizedClientService.removeAuthorizedClient(SYSTEM_USERNAME, SYSTEM_USERNAME)
    SecurityContextHolder.clearContext()
    // The client manager is request scoped, so there has to be a request in scope for it to
    // resolve at all.
    RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest()))
  }

  @AfterEach
  fun tearDown() {
    RequestContextHolder.resetRequestAttributes()
    SecurityContextHolder.clearContext()
  }

  @Test
  fun `the token request carries the end user's username`() {
    authenticateAs(username = USER)

    callPrisonApi()

    assertThat(tokenRequestBody()).contains("username=$USER")
  }

  @Test
  fun `a client-only caller falls back to the client id rather than failing`() {
    authenticateAs(username = null)

    callPrisonApi()

    // Documents deliberately what whereabouts-api did by accident: with no end user, the client
    // identity is sent. MAPA-278 must not rely on this path for a real cell move, because NOMIS
    // needs a genuine user.
    assertThat(tokenRequestBody()).contains("username=$SYSTEM_USERNAME")
  }

  private fun authenticateAs(username: String?) {
    val token = jwtAuthorisationHelper.createJwtAccessToken(
      clientId = SYSTEM_USERNAME,
      username = username,
    )
    SecurityContextHolder.getContext().authentication =
      AuthAwareTokenConverter().convert(jwtDecoder.decode(token))
  }

  private fun callPrisonApi() {
    prisonApiWebClient.get()
      .uri("/api/some-endpoint")
      .retrieve()
      .bodyToMono(String::class.java)
      .block()
  }

  private fun tokenRequestBody(): String {
    val requests = hmppsAuth.findAll(postRequestedFor(urlEqualTo("/auth/oauth/token")))
    assertThat(requests).describedAs("expected a token request to HMPPS Auth").isNotEmpty
    return requests.first().bodyAsString
  }

  private companion object {
    const val USER = "TEST_USER"
  }
}
