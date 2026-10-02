package com.bondplatform.dataprocessing.persistence;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need the real database schema.
 *
 * <p>One PostgreSQL 16 container is started for the whole test run and shared by every test class,
 * because Spring reuses one application context across classes and that context keeps the first
 * container's address. The Flyway migrations are applied to it when the context starts. The
 * container is removed when the test JVM exits.
 *
 * <p>Without Docker these tests are skipped; {@link DockerAvailabilityIT} makes sure that never
 * happens unnoticed in CI.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

  @DynamicPropertySource
  static void connectToSharedDatabase(DynamicPropertyRegistry registry) {
    PostgreSQLContainer database = SharedDatabase.CONTAINER;
    registry.add("spring.datasource.url", database::getJdbcUrl);
    registry.add("spring.datasource.username", database::getUsername);
    registry.add("spring.datasource.password", database::getPassword);
  }

  /** Starts the container the first time a database test needs it. */
  private static final class SharedDatabase {

    static final PostgreSQLContainer CONTAINER = start();

    private static PostgreSQLContainer start() {
      PostgreSQLContainer container = new PostgreSQLContainer("postgres:16-alpine");
      container.start();
      return container;
    }
  }
}
