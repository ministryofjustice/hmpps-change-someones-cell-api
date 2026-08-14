/*
 * Baseline migration. Deliberately creates no objects.
 *
 * MAPA-277 bootstraps this service as a deployable skeleton; the cell movement tables arrive
 * with MAPA-278. Flyway needs at least one migration to run at all, and applying this one is
 * what creates flyway_schema_history — which is the observable proof that the database is
 * reachable, the credentials are right and migrations run on startup. Without it a broken
 * datasource would not be noticed until the first real migration landed.
 *
 * Do not delete this file to "tidy up": removing an applied migration makes Flyway fail
 * validation against every environment that has already run it.
 */
