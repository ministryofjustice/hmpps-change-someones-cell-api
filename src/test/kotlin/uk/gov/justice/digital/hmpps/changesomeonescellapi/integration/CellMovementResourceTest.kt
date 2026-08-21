package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.BodyInserters
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiExtension.Companion.caseNotesApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.CaseNotesApiMockServer
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonExtension.Companion.locationsInsidePrison
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.LocationsInsidePrisonMockServer
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.wiremock.PrisonerSearchExtension.Companion.prisonerSearch
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.CellMovementStatus
import uk.gov.justice.digital.hmpps.changesomeonescellapi.jpa.repository.CellMovementRepository

class CellMovementResourceTest : IntegrationTestBase() {

  @Autowired
  private lateinit var cellMovementRepository: CellMovementRepository

  private val writeRole = listOf("ROLE_CELL_MOVEMENTS__RW")

  @BeforeEach
  fun setUp() {
    cellMovementRepository.deleteAll()
    hmppsAuth.stubGrantToken()
    prisonerSearch.stubGetPrisoner(PRISONER_NUMBER, bookingId = BOOKING_ID.toString(), cellLocation = FROM_CELL)
    prisonApi.stubMoveToCell(BOOKING_ID, TO_LOCATION_KEY)
    caseNotesApi.stubCreateCaseNote(PRISONER_NUMBER)
    locationsInsidePrison.stubResolveKeys(
      "MDI-$FROM_CELL" to LocationsInsidePrisonMockServer.FROM_LOCATION_ID,
      TO_LOCATION_KEY to LocationsInsidePrisonMockServer.TO_LOCATION_ID,
    )
  }

  @Test
  fun `returns 401 without a token`() {
    webTestClient.post().uri("/cell-movements")
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody()))
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 without the write role`() {
    // A valid body deliberately: @PreAuthorize fires after argument resolution, so an invalid one
    // would 400 before the role was ever checked.
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody()))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `moves the prisoner, records the movement and creates the case note`() {
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody()))
      .exchange()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.prisonerNumber").isEqualTo(PRISONER_NUMBER)
      .jsonPath("$.toLocationKey").isEqualTo(TO_LOCATION_KEY)
      .jsonPath("$.fromLocationKey").isEqualTo("MDI-$FROM_CELL")
      .jsonPath("$.fromLocationId").isEqualTo(LocationsInsidePrisonMockServer.FROM_LOCATION_ID)
      .jsonPath("$.toLocationId").isEqualTo(LocationsInsidePrisonMockServer.TO_LOCATION_ID)
      .jsonPath("$.status").isEqualTo("COMPLETED")
      .jsonPath("$.caseNoteUuid").isEqualTo(CaseNotesApiMockServer.CASE_NOTE_UUID)
      .jsonPath("$.recordedBy").isEqualTo(USER)

    val movement = cellMovementRepository.findAll().single()
    assertThat(movement.status).isEqualTo(CellMovementStatus.COMPLETED)
    assertThat(movement.bookingId).isEqualTo(BOOKING_ID)
    assertThat(movement.bedAssignmentSequence).isEqualTo(2)
    assertThat(movement.commentText).isEqualTo(COMMENT)
  }

  @Test
  fun `asks prison-api to lock the record, and passes the reason through`() {
    postMove().expectStatus().isCreated

    val requests = prisonApi.findAll(
      putRequestedFor(urlPathEqualTo("/api/bookings/$BOOKING_ID/living-unit/$TO_LOCATION_KEY")),
    )
    assertThat(requests).hasSize(1)
    // lockTimeout is what makes NOMIS return 423 rather than block when the record is open in
    // P-NOMIS, so its absence would silently lose that behaviour.
    assertThat(requests.first().url).contains("lockTimeout=true").contains("reasonCode=GM")
  }

  @Test
  fun `sends the end user's username to case-notes`() {
    postMove().expectStatus().isCreated

    val request = caseNotesApi.findAll(postRequestedFor(urlPathEqualTo("/case-notes/$PRISONER_NUMBER"))).single()
    // MOVED_CELL is a sync-to-nomis type, which case-notes refuses to write without a NOMIS user.
    assertThat(request.getHeader("Username")).isEqualTo(USER)
    assertThat(request.bodyAsString).contains("\"type\":\"MOVED_CELL\"", "\"subType\":\"GM\"", COMMENT)
  }

  @Test
  fun `a full or unusable cell surfaces as 400, not 500`() {
    prisonApi.stubMoveToCellFails(BOOKING_ID, TO_LOCATION_KEY, status = 400)

    postMove()
      .expectStatus().isBadRequest
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("CellNotAvailable")

    // The movement stays PENDING: it records that we tried and NOMIS refused.
    assertThat(cellMovementRepository.findAll().single().status).isEqualTo(CellMovementStatus.PENDING)
  }

  @Test
  fun `a record open in P-NOMIS surfaces as 423`() {
    prisonApi.stubMoveToCellFails(BOOKING_ID, TO_LOCATION_KEY, status = 423)

    postMove()
      .expectStatus().isEqualTo(423)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerRecordLocked")
  }

  @Test
  fun `a failed case note does not fail the move, and the comment survives`() {
    caseNotesApi.stubCreateCaseNoteFails(PRISONER_NUMBER)

    postMove()
      .expectStatus().isCreated
      .expectBody()
      .jsonPath("$.status").isEqualTo("CASE_NOTE_FAILED")
      .jsonPath("$.caseNoteUuid").doesNotExist()

    // The whole point of storing the comment ourselves: the case note can be recreated from this.
    val movement = cellMovementRepository.findAll().single()
    assertThat(movement.status).isEqualTo(CellMovementStatus.CASE_NOTE_FAILED)
    assertThat(movement.commentText).isEqualTo(COMMENT)
  }

  @Test
  fun `a prisoner already in the destination cell is not an error`() {
    // prison-api treats this as a successful no-op and returns no bed assignment sequence.
    prisonApi.stubMoveToCell(BOOKING_ID, TO_LOCATION_KEY, bedAssignmentHistorySequence = null)

    postMove().expectStatus().isCreated

    val movement = cellMovementRepository.findAll().single()
    assertThat(movement.status).isEqualTo(CellMovementStatus.COMPLETED)
    assertThat(movement.bedAssignmentSequence).isNull()
  }

  @Test
  fun `a repeated submission is rejected with 409`() {
    postMove().expectStatus().isCreated

    postMove()
      .expectStatus().isEqualTo(409)
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("DuplicateCellMovement")

    assertThat(cellMovementRepository.findAll()).hasSize(1)
  }

  @Test
  fun `an unknown prisoner is rejected with 404`() {
    prisonerSearch.stubPrisonerNotFound(PRISONER_NUMBER)

    postMove()
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerNotFound")

    assertThat(cellMovementRepository.findAll()).isEmpty()
  }

  @Test
  fun `a released prisoner cannot be moved`() {
    prisonerSearch.stubGetReleasedPrisoner(PRISONER_NUMBER)

    postMove()
      .expectStatus().isNotFound
      .expectBody()
      .jsonPath("$.errorCode").isEqualTo("PrisonerNotInPrison")
  }

  @Test
  fun `rejects a malformed prisoner number`() {
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody(prisonerNumber = "NOT-A-NUMBER")))
      .exchange()
      .expectStatus().isBadRequest
  }

  /**
   * Before MAPA-289 an unrecognised code reached prison-api, whose 400 is the same one it uses for
   * a full cell - so the user was told the cell was unavailable. Now it fails here, and nothing is
   * written: the row is only created once the request is past validation.
   */
  @Test
  fun `rejects an unrecognised reason code without recording anything`() {
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody(reasonCode = "NOPE")))
      .exchange()
      .expectStatus().isBadRequest

    assertThat(cellMovementRepository.findAll()).isEmpty()
    assertThat(prisonApi.findAll(putRequestedFor(urlPathEqualTo("/api/bookings/$BOOKING_ID/living-unit/$TO_LOCATION_KEY")))).isEmpty()
  }

  /** Retired reasons are still served for display, but must not be used for a new move. */
  @Test
  fun `rejects a retired reason code`() {
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody(reasonCode = "ADM")))
      .exchange()
      .expectStatus().isBadRequest

    assertThat(cellMovementRepository.findAll()).isEmpty()
  }

  @Test
  fun `rejects a blank comment`() {
    webTestClient.post().uri("/cell-movements")
      .headers(setAuthorisation(username = USER, roles = writeRole))
      .contentType(MediaType.APPLICATION_JSON)
      .body(BodyInserters.fromValue(requestBody(comment = "")))
      .exchange()
      .expectStatus().isBadRequest
  }

  @Test
  fun `resolves both location UUIDs in one call`() {
    postMove().expectStatus().isCreated

    // Keys are mutable - the UUID is the location's fixed identity - and both must come from a
    // single bulk request, not one per key.
    val requests = locationsInsidePrison.findAll(
      com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/locations/keys")),
    )
    org.assertj.core.api.Assertions.assertThat(requests).hasSize(1)
    org.assertj.core.api.Assertions.assertThat(requests.single().bodyAsString)
      .contains("MDI-$FROM_CELL")
      .contains(TO_LOCATION_KEY)
  }

  @Test
  fun `locations-inside-prison being down does not block the move`() {
    locationsInsidePrison.stubResolveKeysFails()

    postMove().expectStatus().isCreated

    // The NOMIS move is the business-critical operation; recording a UUID is ancillary and the
    // nulls are backfillable. Same stance as a failed case note.
    val movement = cellMovementRepository.findAll().single()
    org.assertj.core.api.Assertions.assertThat(movement.status).isEqualTo(CellMovementStatus.COMPLETED)
    org.assertj.core.api.Assertions.assertThat(movement.fromLocationId).isNull()
    org.assertj.core.api.Assertions.assertThat(movement.toLocationId).isNull()
  }

  @Test
  fun `a key locations-inside-prison does not recognise nulls only that UUID`() {
    // LIP silently omits unrecognised keys from the bulk response rather than erroring.
    locationsInsidePrison.stubResolveKeys(TO_LOCATION_KEY to LocationsInsidePrisonMockServer.TO_LOCATION_ID)

    postMove().expectStatus().isCreated

    val movement = cellMovementRepository.findAll().single()
    org.assertj.core.api.Assertions.assertThat(movement.fromLocationId).isNull()
    org.assertj.core.api.Assertions.assertThat(movement.toLocationId)
      .isEqualTo(java.util.UUID.fromString(LocationsInsidePrisonMockServer.TO_LOCATION_ID))
  }

  private fun postMove() = webTestClient.post().uri("/cell-movements")
    .headers(setAuthorisation(username = USER, roles = writeRole))
    .contentType(MediaType.APPLICATION_JSON)
    .body(BodyInserters.fromValue(requestBody()))
    .exchange()

  private fun requestBody(
    prisonerNumber: String = PRISONER_NUMBER,
    toLocationKey: String = TO_LOCATION_KEY,
    // An active reason. ADM, the old default here, is a retired code and is no longer accepted for
    // a new move - see CellMoveReasonCode.
    reasonCode: String = "GM",
    comment: String = COMMENT,
  ) = """
    {
      "prisonerNumber": "$prisonerNumber",
      "toLocationKey": "$toLocationKey",
      "reasonCode": "$reasonCode",
      "commentText": "$comment"
    }
  """.trimIndent()

  private companion object {
    const val PRISONER_NUMBER = "A1234BC"
    const val BOOKING_ID = 1200866L
    const val FROM_CELL = "1-1-001"
    const val TO_LOCATION_KEY = "MDI-1-1-015"
    const val COMMENT = "Moved following an altercation on the wing"
    const val USER = "TEST_USER"
  }
}
