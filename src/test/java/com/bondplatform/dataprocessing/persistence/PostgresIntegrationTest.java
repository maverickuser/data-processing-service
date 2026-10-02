package com.bondplatform.dataprocessing.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need the real database schema.
 *
 * <p>Starts one PostgreSQL 16 container for the test class and applies the Flyway migrations to it.
 * Without Docker these tests are skipped; {@link DockerAvailabilityIT} makes sure that never
 * happens unnoticed in CI.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}
