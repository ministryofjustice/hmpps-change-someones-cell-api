package uk.gov.justice.digital.hmpps.changesomeonescellapi

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Identifies this service to HMPPS Auth. Must match the
 * spring.security.oauth2.client.registration key in application.yml, and is the fallback "who"
 * when a request carries no end user.
 */
const val SYSTEM_USERNAME = "HMPPS_CHANGE_SOMEONES_CELL_API"

@SpringBootApplication
class ChangeSomeonesCellApi

fun main(args: Array<String>) {
  runApplication<ChangeSomeonesCellApi>(*args)
}
