package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * Services inject [Clock] rather than calling `LocalDateTime.now()` directly, so that time can be
 * fixed in tests. The zone comes from the Jackson setting so that what we record and what we
 * serialise cannot drift apart.
 */
@Configuration
class ClockConfiguration(
  @param:Value($$"${spring.jackson.time-zone}") private val timeZone: String,
) {
  @Bean
  fun clock(): Clock = Clock.system(ZoneId.of(timeZone))
}
