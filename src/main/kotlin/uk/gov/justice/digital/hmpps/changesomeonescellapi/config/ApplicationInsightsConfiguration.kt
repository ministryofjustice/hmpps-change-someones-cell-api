package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

import com.microsoft.applicationinsights.TelemetryClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * TelemetryClient gets altered at runtime by the java agent and so is a no-op otherwise.
 *
 * The agent is attached in the deployed image and reads `applicationinsights.json`; locally and in
 * tests there is no agent and no connection string, so the client constructs fine and every
 * `trackEvent` silently does nothing.
 *
 * This bean also switches on hmpps-sqs's own telemetry: `HmppsSqsConfiguration` takes a nullable
 * TelemetryClient, and reports DLQ, purge and error-visibility events once one exists.
 */
@Configuration
class ApplicationInsightsConfiguration {
  @Bean
  fun telemetryClient(): TelemetryClient = TelemetryClient()
}
