@file:Suppress("ktlint:standard:filename")

package uk.gov.justice.digital.hmpps.changesomeonescellapi.health

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.hmpps.kotlin.health.HealthPingCheck

// HMPPS Auth health ping is required if your service calls HMPPS Auth to get a token to call other services
@Component("hmppsAuth")
class HmppsAuthHealthPing(@Qualifier("hmppsAuthHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)

@Component("prisonApi")
class PrisonApiHealthPing(@Qualifier("prisonApiHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)

@Component("caseNotesApi")
class CaseNotesApiHealthPing(@Qualifier("caseNotesApiHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)

@Component("prisonerSearch")
class PrisonerSearchHealthPing(@Qualifier("prisonerSearchHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)

@Component("locationsInsidePrison")
class LocationsInsidePrisonHealthPing(@Qualifier("locationsInsidePrisonApiHealthWebClient") webClient: WebClient) : HealthPingCheck(webClient)
