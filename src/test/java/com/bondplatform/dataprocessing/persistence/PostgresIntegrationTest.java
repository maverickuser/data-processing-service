package com.bondplatform.dataprocessing.persistence;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need the real database schema.
 *
 * <p>One PostgreSQL 16 container is started for the whole test run and shared by every test class,
 * because Spring reuses one application context across classes and that context keeps the first
 * container's address. The Flyway migrations are applied to it when the context starts. Every table
 * is emptied before each test, so no test sees another's rows. The container is removed when the
 * test JVM exits.
 *
 * <p>Without Docker these tests are skipped; {@link DockerAvailabilityIT} makes sure that never
 * happens unnoticed in CI.
 */
@SpringBootTest(properties = "spring.flyway.enabled=true")
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTest {

  /** Database access for tests, on the application's own datasource. */
  @Autowired protected JdbcClient jdbc;

  @DynamicPropertySource
  static void connectToSharedDatabase(DynamicPropertyRegistry registry) {
    PostgreSQLContainer database = SharedDatabase.CONTAINER;
    registry.add("spring.datasource.url", database::getJdbcUrl);
    registry.add("spring.datasource.username", database::getUsername);
    registry.add("spring.datasource.password", database::getPassword);
  }

  @BeforeEach
  void emptyEveryTable() {
    List<String> tables =
        jdbc.sql(
                """
                SELECT table_schema || '.' || table_name FROM information_schema.tables
                WHERE table_schema IN ('securities_data', 'data_processing')
                  AND table_name <> 'flyway_schema_history'
                """)
            .query(String.class)
            .list();
    jdbc.sql("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").update();
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
