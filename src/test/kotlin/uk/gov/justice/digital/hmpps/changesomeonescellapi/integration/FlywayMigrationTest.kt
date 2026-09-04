package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the MAPA-277 acceptance criterion "Postgres instance provisioned and Flyway runs on
 * startup". The service had no business tables when this was written, so the observable evidence
 * that the datasource works and migrations ran is flyway_schema_history itself.
 *
 * containsExactly rather than contains: the previous assertion silently stopped covering V5, because
 * a non-exhaustive check passes however many migrations are added after it. Adding a migration now
 * fails here until the list is updated, which is the point.
 */
class FlywayMigrationTest : IntegrationTestBase() {

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Test
  fun `flyway has applied the baseline migration`() {
    val applied = jdbcTemplate.queryForList(
      "select version from flyway_schema_history where success = true order by installed_rank",
      String::class.java,
    )

    assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6")
  }
}
