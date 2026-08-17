package uk.gov.justice.digital.hmpps.changesomeonescellapi.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.LocalDateTime
import java.util.UUID

/** The case note type NOMIS uses for a cell move. Its subType is the cell move reason code. */
private const val MOVED_CELL = "MOVED_CELL"

/**
 * Records the MOVED_CELL case note for a cell move.
 *
 * The case notes service is the source of truth for case notes - fully migrated, with its own
 * database, syncing back to NOMIS asynchronously. We do not write case notes anywhere else.
 *
 * MOVED_CELL is a "sync to nomis" type, which the service refuses to write without a real NOMIS
 * user. It decides who the user is from the `Username` header, falling back to the token subject,
 * then looks that name up in manage-users. We send the header explicitly rather than depending on
 * how HMPPS Auth happens to populate the subject of a client-credentials token.
 */
@Component
class CaseNotesApiClient(
  @param:Qualifier("caseNotesApiWebClient") private val webClient: WebClient,
) {
  fun createCellMoveCaseNote(
    prisonerNumber: String,
    reasonCode: String,
    text: String,
    occurredAt: LocalDateTime,
    username: String,
  ): CaseNote = webClient
    .post()
    .uri("/case-notes/{prisonerNumber}", mapOf("prisonerNumber" to prisonerNumber))
    .header("Username", username)
    .bodyValue(
      CreateCaseNoteRequest(
        type = MOVED_CELL,
        subType = reasonCode,
        text = text,
        occurrenceDateTime = occurredAt,
      ),
    )
    .retrieve()
    .bodyToMono<CaseNote>()
    .block()!!

  /**
   * Reads a case note back, for a movement migrated from whereabouts where the explanation was
   * never stored on our side and lives only in the case note.
   *
   * [caseNoteId] may be either the UUID or the deprecated numeric legacy id - case-notes decides
   * which it has been given and looks it up accordingly. Migrated rows only ever have the legacy
   * id, so that is the form this is called with in practice.
   *
   * Returns null on a 404. That is a real and expected outcome rather than a fault: whereabouts
   * recorded the link at the moment of the move and never revisited it, so it can point at a case
   * note that has since been deleted or amended away. The caller degrades to returning the
   * movement without its explanation instead of failing the read.
   */
  fun getCaseNote(prisonerNumber: String, caseNoteId: String): CaseNote? = try {
    webClient
      .get()
      .uri("/case-notes/{prisonerNumber}/{caseNoteId}", mapOf("prisonerNumber" to prisonerNumber, "caseNoteId" to caseNoteId))
      .retrieve()
      .bodyToMono<CaseNote>()
      .block()
  } catch (e: WebClientResponseException) {
    if (e.statusCode == HttpStatus.NOT_FOUND) null else throw e
  }
}

data class CreateCaseNoteRequest(
  val type: String,
  val subType: String,
  val text: String,
  val occurrenceDateTime: LocalDateTime,
  val systemGenerated: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CaseNote(
  /** Despite the name this is the UUID; the numeric id is [legacyId] and is deprecated. */
  val caseNoteId: UUID,
  val legacyId: Long? = null,
  val type: String? = null,
  /**
   * For a MOVED_CELL case note this is the CHG_HOUS_RSN reason code for the move. It is the only
   * place the reason code survives for a movement migrated from whereabouts, whose table had no
   * column for it.
   */
  val subType: String? = null,
  /** The explanation of the move, in the mover's own words. Absent from a create response. */
  val text: String? = null,
)
