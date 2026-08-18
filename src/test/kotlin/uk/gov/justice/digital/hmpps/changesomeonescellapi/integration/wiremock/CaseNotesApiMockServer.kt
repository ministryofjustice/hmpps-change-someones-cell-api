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

class CaseNotesApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val caseNotesApi = CaseNotesApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    caseNotesApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    caseNotesApi.resetAll()
  }

  override fun afterAll(context: ExtensionContext) {
    caseNotesApi.stop()
  }
}

class CaseNotesApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8092
    const val CASE_NOTE_UUID = "6bc0e6a9-7e0f-4a4a-9c62-0d0a0b1d1234"
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

  /** Note caseNoteId is the UUID; legacyId is the deprecated numeric id. */
  fun stubCreateCaseNote(prisonerNumber: String, caseNoteId: String = CASE_NOTE_UUID) {
    stubFor(
      post(urlPathEqualTo("/case-notes/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "caseNoteId": "$caseNoteId",
                "legacyId": 1234567,
                "type": "MOVED_CELL",
                "subType": "ADM"
              }
            """.trimIndent(),
          )
          .withStatus(201),
      ),
    )
  }

  /**
   * Reading a case note back by its deprecated numeric legacy id, which is the only identifier a
   * movement migrated from whereabouts has. case-notes accepts either form on this path.
   */
  fun stubGetCaseNote(
    prisonerNumber: String,
    caseNoteId: String,
    caseNoteUuid: String = CASE_NOTE_UUID,
    subType: String = "ADM",
    text: String = "Moved following an altercation on the wing",
  ) {
    stubFor(
      get(urlPathEqualTo("/case-notes/$prisonerNumber/$caseNoteId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(
            """
              {
                "caseNoteId": "$caseNoteUuid",
                "offenderIdentifier": "$prisonerNumber",
                "legacyId": $caseNoteId,
                "type": "MOVED_CELL",
                "subType": "$subType",
                "text": "$text",
                "authorName": "Jane Smith",
                "creationDateTime": "2026-08-01T10:00:00",
                "occurrenceDateTime": "2026-08-01T09:55:00"
              }
            """.trimIndent(),
          )
          .withStatus(200),
      ),
    )
  }

  /** The migrated link points at a case note that no longer exists - deleted or amended away. */
  fun stubGetCaseNoteNotFound(prisonerNumber: String, caseNoteId: String) {
    stubFor(
      get(urlPathEqualTo("/case-notes/$prisonerNumber/$caseNoteId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":404,"userMessage":"Case note $caseNoteId not found"}""")
          .withStatus(404),
      ),
    )
  }

  /** case-notes is down. The read must still answer with what we hold. */
  fun stubGetCaseNoteFails(prisonerNumber: String, caseNoteId: String, status: Int = 500) {
    stubFor(
      get(urlPathEqualTo("/case-notes/$prisonerNumber/$caseNoteId")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":$status,"userMessage":"Unexpected error"}""")
          .withStatus(status),
      ),
    )
  }

  /**
   * A failing create. 403 is the realistic case: MOVED_CELL is a sync-to-nomis type, which
   * case-notes refuses to write without a real NOMIS user.
   */
  fun stubCreateCaseNoteFails(prisonerNumber: String, status: Int = 403) {
    stubFor(
      post(urlPathEqualTo("/case-notes/$prisonerNumber")).willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody("""{"status":$status,"developerMessage":"Unable to author 'sync to nomis' type without a nomis user"}""")
          .withStatus(status),
      ),
    )
  }
}
