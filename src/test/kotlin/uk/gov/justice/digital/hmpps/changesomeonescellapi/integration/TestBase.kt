package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration

import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.container.LocalStackContainer
import uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.container.PostgresContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@ActiveProfiles("test")
abstract class TestBase {
  companion object {
    val clock: Clock = Clock.fixed(
      Instant.parse("2026-08-14T12:34:56+01:00"),
      ZoneId.of("Europe/London"),
    )

    private val pgContainer = PostgresContainer.instance

    // Every context needs it, not only the listener tests: hmpps-sqs creates the prisoner event
    // queue when the application starts, so without it no integration test could start at all.
    private val localStackContainer = LocalStackContainer.instance

    // Only set when a container was started. If a local Postgres is already listening on 5432
    // the datasource comes from application-test.yml instead, and likewise LocalStack on 4566.
    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      localStackContainer?.also { LocalStackContainer.setLocalStackProperties(it, registry) }
      pgContainer?.run {
        registry.add("spring.datasource.url", pgContainer::getJdbcUrl)
        registry.add("spring.datasource.username", pgContainer::getUsername)
        registry.add("spring.datasource.password", pgContainer::getPassword)
        registry.add("spring.flyway.url", pgContainer::getJdbcUrl)
        registry.add("spring.flyway.user", pgContainer::getUsername)
        registry.add("spring.flyway.password", pgContainer::getPassword)
      }
    }
  }
}
