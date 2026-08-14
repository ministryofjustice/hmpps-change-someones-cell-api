package uk.gov.justice.digital.hmpps.changesomeonescellapi.config

/**
 * Roles this service recognises, in the modern namespaced style.
 *
 * whereabouts-api required no role at all for a cell move - any authenticated HMPPS token could
 * move any prisoner. That is not carried forward: every endpoint added from MAPA-278 onwards must
 * name one of these in @PreAuthorize, and ResourceSecurityTest fails the build if one does not.
 */
object Roles {
  /** Read cell movements. */
  const val CELL_MOVEMENTS_RO = "ROLE_CELL_MOVEMENTS__RO"

  /** Record a cell movement. */
  const val CELL_MOVEMENTS_RW = "ROLE_CELL_MOVEMENTS__RW"

  /** Reserved for the NOMIS sync and migration endpoints in phase 3. */
  const val CELL_MOVEMENTS_SYNC_RW = "ROLE_CELL_MOVEMENTS__SYNC__RW"
}
