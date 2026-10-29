package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.test.json.JsonCompareMode
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonExtension.Companion.locationsInsidePrison
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiMockServer.Companion.bedAssignment
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch

/**
 * Who a prisoner shared a cell with over a period (MAPA-477).
 */
class CellSharingResourceTest : IntegrationTestBase() {

  private val readRole = listOf("ROLE_CELL_MOVEMENTS__RO")

  @BeforeEach
  fun setUp() {
    hmppsAuth.stubGrantToken()
  }

  @Nested
  inner class Security {
    @Test
    fun `returns 401 without a token`() {
      webTestClient.get().uri(uri())
        .exchange()
        .expectStatus().isUnauthorized
    }

    @Test
    fun `returns 403 without the read role`() {
      webTestClient.get().uri(uri())
        .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RW")))
        .exchange()
        .expectStatus().isForbidden
    }
  }

  @Nested
  inner class Validation {
    @Test
    fun `returns 400 when from is after to`() {
      webTestClient.get().uri(uri(from = "2026-07-09T10:15:00", to = "2026-07-06T21:07:00"))
        .headers(setAuthorisation(roles = readRole))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 400 when from is missing`() {
      webTestClient.get().uri("/cell-movements/prisoner/$PRISONER/cell-sharing")
        .headers(setAuthorisation(roles = readRole))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 400 when a date is not a date`() {
      webTestClient.get().uri(uri(from = "yesterday"))
        .headers(setAuthorisation(roles = readRole))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 400 for a badly formed prisoner number`() {
      webTestClient.get().uri(uri(prisonerNumber = "NOT-A-NUMBER"))
        .headers(setAuthorisation(roles = readRole))
        .exchange()
        .expectStatus().isBadRequest
    }

    @Test
    fun `returns 404 when prison-api does not know the prisoner`() {
      prisonApi.stubCellHistoryNotFound(PRISONER)

      webTestClient.get().uri(uri())
        .headers(setAuthorisation(roles = readRole))
        .exchange()
        .expectStatus().isNotFound
        .expectBody()
        .jsonPath("$.errorCode").isEqualTo("PrisonerNotFound")
    }
  }

  @Test
  fun `returns each cell stay where the prisoner shared, with who and when`() {
    prisonApi.stubCellHistory(
      PRISONER,
      // Alone here - not returned.
      bedAssignment(OWN_BOOKING, PRISONER, 200, "LEI-A-1-020", from = "2026-07-08T14:00:00"),
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00", to = "2026-07-08T14:00:00"),
      // Reception is never sharing, however busy.
      bedAssignment(OWN_BOOKING, PRISONER, 1, "LEI-RECP", from = "2026-07-06T20:00:00", to = "2026-07-06T21:10:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00", to = "2026-07-08T14:00:00"),
      // An earlier booking of the same prisoner is not a cellmate.
      bedAssignment(5, PRISONER, 100, "LEI-A-1-015", from = "2026-01-01T09:00:00", to = "2026-07-06T21:20:00"),
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-07-01T09:00:00", to = "2026-07-07T09:00:00"),
      bedAssignment(11, "A2222BB", 100, "LEI-A-1-015", from = "2026-07-07T12:00:00"),
      // Moved out the minute the prisoner moved in - did not share.
      bedAssignment(12, "A3333CC", 100, "LEI-A-1-015", from = "2026-07-01T09:00:00", to = "2026-07-06T21:10:00"),
    )
    prisonApi.stubCellOccupancy(
      200,
      bedAssignment(OWN_BOOKING, PRISONER, 200, "LEI-A-1-020", from = "2026-07-08T14:00:00"),
    )
    prisonerSearch.stubFindPrisoners(Triple("A1245XY", "James", "Clive"), Triple("A2222BB", "Sam", "Jones"))
    locationsInsidePrison.stubResolveKeys("LEI-A-1-015" to LOCATION_ID)

    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .json(
        """
          {
            "prisonerNumber": "$PRISONER",
            "from": "2026-07-06T21:07:00",
            "to": "2026-07-09T10:15:00",
            "cells": [
              {
                "prisonId": "LEI",
                "locationKey": "LEI-A-1-015",
                "pathHierarchy": "A-1-015",
                "dpsLocationId": "$LOCATION_ID",
                "nomisLocationId": 100,
                "movedIn": "2026-07-06T21:10:00",
                "movedOut": "2026-07-08T14:00:00",
                "sharedWith": [
                  {
                    "prisonerNumber": "A1245XY",
                    "firstName": "James",
                    "lastName": "Clive",
                    "sharedFrom": "2026-07-06T21:10:00",
                    "sharedTo": "2026-07-07T09:00:00"
                  },
                  {
                    "prisonerNumber": "A2222BB",
                    "firstName": "Sam",
                    "lastName": "Jones",
                    "sharedFrom": "2026-07-07T12:00:00",
                    "sharedTo": "2026-07-08T14:00:00"
                  }
                ]
              }
            ]
          }
        """.trimIndent(),
        JsonCompareMode.STRICT,
      )
      .jsonPath("$.cells[0].bookingId").doesNotExist()
      .jsonPath("$.cells[0].bedAssignmentHistorySequence").doesNotExist()

    // Asked about the part of each stay inside the period, and never about reception.
    prisonApi.verify(
      getRequestedFor(urlPathEqualTo("/api/offenders/$PRISONER/cell-history"))
        .withQueryParam("fromDate", equalTo("2026-07-06T21:07:00"))
        .withQueryParam("toDate", equalTo("2026-07-09T10:15:00")),
    )
    prisonApi.verify(
      getRequestedFor(urlPathEqualTo("/api/cell/100/history"))
        .withQueryParam("fromDate", equalTo("2026-07-06T21:10:00"))
        .withQueryParam("toDate", equalTo("2026-07-08T14:00:00")),
    )
    prisonApi.verify(
      getRequestedFor(urlPathEqualTo("/api/cell/200/history"))
        .withQueryParam("fromDate", equalTo("2026-07-08T14:00:00"))
        .withQueryParam("toDate", equalTo("2026-07-09T10:15:00")),
    )
    prisonApi.verify(0, getRequestedFor(urlPathEqualTo("/api/cell/1/history")))
  }

  @Test
  fun `covers up to now when no end is given, and shows who is still sharing`() {
    // TestBase.clock is 2026-08-14T12:34:56 Europe/London.
    prisonApi.stubCellHistory(
      PRISONER,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-08-10T10:00:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-08-10T10:00:00"),
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-08-01T09:00:00"),
    )
    prisonerSearch.stubFindPrisoners(Triple("A1245XY", "James", "Clive"))
    locationsInsidePrison.stubResolveKeys("LEI-A-1-015" to LOCATION_ID)

    webTestClient.get().uri("/cell-movements/prisoner/$PRISONER/cell-sharing?from=2026-08-10T09:00:00")
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.to").isEqualTo("2026-08-14T12:34:56")
      .jsonPath("$.cells.length()").isEqualTo(1)
      .jsonPath("$.cells[0].movedOut").isEmpty
      .jsonPath("$.cells[0].sharedWith[0].prisonerNumber").isEqualTo("A1245XY")
      .jsonPath("$.cells[0].sharedWith[0].sharedFrom").isEqualTo("2026-08-10T10:00:00")
      .jsonPath("$.cells[0].sharedWith[0].sharedTo").isEmpty
  }

  @Test
  fun `lists a cellmate once when they shared more than once during the same stay`() {
    prisonApi.stubCellHistory(
      PRISONER,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00", to = "2026-07-09T08:00:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-07-06T22:00:00", to = "2026-07-07T09:00:00"),
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-07-08T09:00:00", to = "2026-07-08T18:00:00"),
    )
    prisonerSearch.stubFindPrisoners(Triple("A1245XY", "James", "Clive"))
    locationsInsidePrison.stubResolveKeys("LEI-A-1-015" to LOCATION_ID)

    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.cells[0].sharedWith.length()").isEqualTo(1)
      .jsonPath("$.cells[0].sharedWith[0].sharedFrom").isEqualTo("2026-07-06T22:00:00")
      .jsonPath("$.cells[0].sharedWith[0].sharedTo").isEqualTo("2026-07-08T18:00:00")
  }

  @Test
  fun `returns two entries, most recent first, when the prisoner returns to a cell`() {
    prisonApi.stubCellHistory(
      PRISONER,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-08T12:00:00", to = "2026-07-09T08:00:00"),
      bedAssignment(OWN_BOOKING, PRISONER, 200, "LEI-A-1-020", from = "2026-07-07T12:00:00", to = "2026-07-08T12:00:00"),
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00", to = "2026-07-07T12:00:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-07-01T09:00:00"),
    )
    prisonApi.stubCellOccupancy(200)
    prisonerSearch.stubFindPrisoners(Triple("A1245XY", "James", "Clive"))
    locationsInsidePrison.stubResolveKeys("LEI-A-1-015" to LOCATION_ID)

    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.cells.length()").isEqualTo(2)
      .jsonPath("$.cells[0].movedIn").isEqualTo("2026-07-08T12:00:00")
      .jsonPath("$.cells[0].sharedWith[0].sharedFrom").isEqualTo("2026-07-08T12:00:00")
      .jsonPath("$.cells[1].movedIn").isEqualTo("2026-07-06T21:10:00")
      .jsonPath("$.cells[1].sharedWith[0].sharedTo").isEqualTo("2026-07-07T12:00:00")
  }

  @Test
  fun `still returns the list when locations-inside-prison and prisoner-search do not know the location or person`() {
    prisonApi.stubCellHistory(
      PRISONER,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00", to = "2026-07-08T14:00:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(10, "A1245XY", 100, "LEI-A-1-015", from = "2026-07-01T09:00:00"),
    )
    prisonerSearch.stubFindPrisoners()
    locationsInsidePrison.stubResolveKeysFails()

    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.cells[0].locationKey").isEqualTo("LEI-A-1-015")
      .jsonPath("$.cells[0].pathHierarchy").isEqualTo("A-1-015")
      .jsonPath("$.cells[0].dpsLocationId").isEmpty
      .jsonPath("$.cells[0].nomisLocationId").isEqualTo(100)
      .jsonPath("$.cells[0].sharedWith[0].prisonerNumber").isEqualTo("A1245XY")
      .jsonPath("$.cells[0].sharedWith[0].firstName").isEmpty
  }

  @Test
  fun `returns an empty list without looking anyone up when the prisoner did not share`() {
    prisonApi.stubCellHistory(
      PRISONER,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00"),
    )
    prisonApi.stubCellOccupancy(
      100,
      bedAssignment(OWN_BOOKING, PRISONER, 100, "LEI-A-1-015", from = "2026-07-06T21:10:00"),
    )

    webTestClient.get().uri(uri())
      .headers(setAuthorisation(roles = readRole))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.cells.length()").isEqualTo(0)

    prisonerSearch.verify(0, postRequestedFor(urlPathEqualTo("/prisoner-search/prisoner-numbers")))
  }

  private fun uri(
    prisonerNumber: String = PRISONER,
    from: String = "2026-07-06T21:07:00",
    to: String = "2026-07-09T10:15:00",
  ) = "/cell-movements/prisoner/$prisonerNumber/cell-sharing?from=$from&to=$to"

  private companion object {
    const val PRISONER = "A1234AA"
    const val OWN_BOOKING = 1200866L
    const val LOCATION_ID = "2475f250-434a-4257-afe7-b911f1773a4d"
  }
}
