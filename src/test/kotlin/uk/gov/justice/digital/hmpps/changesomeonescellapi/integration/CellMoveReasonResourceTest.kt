package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.junit.jupiter.api.Test

/**
 * The cell move reason *reference data* (MAPA-289) - the reasons that exist at all.
 *
 * Not [CellMovementReasonResourceTest], which despite the near-identical name covers a different
 * endpoint: why a particular prisoner was moved into a particular cell.
 */
class CellMoveReasonResourceTest : IntegrationTestBase() {

  private val uri = "/cell-movements/reasons"

  @Test
  fun `returns 401 without a token`() {
    webTestClient.get().uri(uri)
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `returns 403 without a cell movements role`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_SOMETHING_ELSE")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `is readable with the read role`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
  }

  /** A client that may record a move may read the reasons it must choose between. */
  @Test
  fun `is readable with the write role`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RW")))
      .exchange()
      .expectStatus().isOk
  }

  @Test
  fun `returns every reason in display order`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.length()").isEqualTo(12)
      .jsonPath("$[*].code").isEqualTo(
        listOf("ADM", "BEH", "CLA", "CON", "VP", "LN", "RAIM", "SS", "SPP", "HOSP", "PCM", "GM"),
      )
      .jsonPath("$[0].description").isEqualTo("Administrative")
      .jsonPath("$[11].description").isEqualTo("General moves")
  }

  /**
   * The point of returning inactive reasons: the history screen resolves a description for every
   * historic movement, and retired codes are all over that history.
   */
  @Test
  fun `includes retired reasons, marked inactive`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$[?(@.code == 'ADM')].active").isEqualTo(false)
      .jsonPath("$[?(@.code == 'LN')].active").isEqualTo(false)
      .jsonPath("$[?(@.code == 'GM')].active").isEqualTo(true)
      .jsonPath("$[?(@.code == 'RAIM')].active").isEqualTo(true)
  }

  /** listSeq orders the array; it is not part of the contract clients see. */
  @Test
  fun `does not expose listSeq`() {
    webTestClient.get().uri(uri)
      .headers(setAuthorisation(roles = listOf("ROLE_CELL_MOVEMENTS__RO")))
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$[0].listSeq").doesNotExist()
  }
}
