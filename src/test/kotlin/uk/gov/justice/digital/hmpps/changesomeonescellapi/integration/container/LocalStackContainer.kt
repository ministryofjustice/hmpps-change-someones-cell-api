package uk.gov.justice.digital.hmpps.changesomeonescellapi.integration.container

import org.slf4j.LoggerFactory
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.io.IOException
import java.net.ServerSocket

/**
 * The SQS and SNS behind the prisoner event queue (MAPA-438). As with [PostgresContainer], reuses a
 * LocalStack already listening on 4566 (typically `docker compose up`) and otherwise starts one.
 */
object LocalStackContainer {
  val instance: LocalStackContainer? by lazy { startLocalStackIfNotRunning() }

  fun setLocalStackProperties(localStackContainer: LocalStackContainer, registry: DynamicPropertyRegistry) {
    registry.add("hmpps.sqs.localstackUrl") { localStackContainer.endpoint }
    registry.add("hmpps.sqs.region") { localStackContainer.region }
  }

  private fun startLocalStackIfNotRunning(): LocalStackContainer? {
    if (isLocalStackRunning()) {
      log.warn("Using existing LocalStack")
      return null
    }
    log.info("Creating a LocalStack")
    return LocalStackContainer(DockerImageName.parse("localstack/localstack").withTag("4")).apply {
      withServices("sqs", "sns")
      withEnv("DEFAULT_REGION", "eu-west-2")
      waitingFor(Wait.forLogMessage(".*Ready.*", 1))
      start()
      followOutput(Slf4jLogConsumer(log).withPrefix("localstack"))
    }
  }

  private fun isLocalStackRunning(): Boolean = try {
    val serverSocket = ServerSocket(4566)
    serverSocket.localPort == 0
  } catch (_: IOException) {
    true
  }

  private val log = LoggerFactory.getLogger(this::class.java)
}
