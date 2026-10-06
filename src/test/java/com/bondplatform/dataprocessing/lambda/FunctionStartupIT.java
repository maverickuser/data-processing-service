package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.adapter.database.MasterSecret;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The scheduled and migration functions start as Lambda starts them, through their public
 * constructors, and each handles a fixture event (plan PR 43). The API and worker functions are
 * started the same way in {@link EventIngestionRejectionsIT} and {@link WorkerStartupIT}.
 */
class FunctionStartupIT extends PostgresIntegrationTest {

  private static final String URL = "spring.datasource.url";

  /** The login roles migration V5 creates, one per function. */
  static final List<String> FUNCTION_ROLES =
      List.of(
          "processing_reader",
          "processing_submission",
          "processing_worker",
          "processing_sweeper",
          "processing_retention");

  /**
   * The login the migration function is configured with; it is never used, because the migration
   * logs in as the master user from the secret.
   */
  private static final String UNUSED_LOGIN = "processing_reader";

  private static final Pattern VERSIONED_FILE = Pattern.compile("^V(\\d+)__");

  @Value("${spring.datasource.url}")
  private String databaseUrl;

  @Value("${spring.datasource.username}")
  private String username;

  @Value("${spring.datasource.password}")
  private String password;

  @AfterEach
  void forgetTheDatabaseSettings() {
    for (String name :
        new String[] {URL, "spring.datasource.username", "spring.datasource.password"}) {
      System.clearProperty(name);
    }
  }

  @Test
  void sweeperStartsAndSweepsAnEmptyOutbox() {
    OutboxSweeperHandler sweeper = startedOn(databaseUrl, OutboxSweeperHandler::new);

    assertThat(sweeper.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("failed stuckJobs=0 delivered outboxEvents=0");
  }

  @Test
  void retentionStartsAndFindsNothingToDelete() {
    RetentionHandler retention = startedOn(databaseUrl, RetentionHandler::new);

    assertThat(retention.handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("deleted validationIssues=0 rejectedRecords=0 outboxEvents=0");
  }

  @Test
  void migrationAppliesEveryMigrationToANewDatabaseOnceAsTheMaster() throws IOException {
    jdbc.sql("DROP DATABASE IF EXISTS migration_check").update();
    jdbc.sql("CREATE DATABASE migration_check").update();
    Resource[] files =
        new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
    final int migrations = files.length;
    final int latestVersion = latestVersionOf(files);
    String newDatabase = databaseUrl.replaceFirst("/[^/?]+(\\?|$)", "/migration_check$1");
    // As in AWS: the migration logs in with the master user from the secret, not its own login
    MigrationHandler migration =
        startedOn(
            newDatabase,
            UNUSED_LOGIN,
            "",
            () -> {
              ConfigurableApplicationContext application = MigrationHandler.start();
              return new MigrationHandler(
                  MigrationHandler.asMaster(
                      application.getBean(Flyway.class),
                      newDatabase,
                      () -> new MasterSecret(username, password)));
            });

    assertThat(migration.handleRequest(Map.of(), new FixedLambdaContext()))
        .as("every migration is applied on the first invocation, none at startup")
        .isEqualTo("applied migrations=" + migrations + " schemaVersion=" + latestVersion);
    assertThat(migration.handleRequest(Map.of(), new FixedLambdaContext()))
        .isEqualTo("applied migrations=0 schemaVersion=" + latestVersion);
  }

  @Test
  void everyFunctionRoleGetsIamLoginAndCannotChangeTheSchemaOrTheMigrationHistory() {
    jdbc.sql("DROP DATABASE IF EXISTS role_check").update();
    jdbc.sql("CREATE DATABASE role_check").update();
    String newDatabase = databaseUrl.replaceFirst("/[^/?]+(\\?|$)", "/role_check$1");
    // A stand-in for the role RDS provides; here it carries no IAM login, so the function roles
    // keep a password. Roles span the whole server, so it is dropped again before other tests.
    jdbc.sql("CREATE ROLE rds_iam").update();
    try {
      migrateAsMaster(newDatabase);
      assertThat(FUNCTION_ROLES)
          .allSatisfy(
              role ->
                  assertThat(
                          jdbc.sql("SELECT pg_has_role(:role, 'rds_iam', 'MEMBER')")
                              .param("role", role)
                              .query(Boolean.class)
                              .single())
                      .as(role)
                      .isTrue());
    } finally {
      jdbc.sql("DROP ROLE rds_iam").update();
    }

    for (String role : FUNCTION_ROLES) {
      jdbc.sql("ALTER ROLE " + role + " PASSWORD 'role-check'").update();
      JdbcClient asFunction =
          JdbcClient.create(new DriverManagerDataSource(newDatabase, role, "role-check"));
      assertThatThrownBy(
              () -> asFunction.sql("CREATE TABLE data_processing.not_allowed (id INT)").update())
          .as(role)
          .rootCause()
          .hasMessageContaining("permission denied");
      assertThatThrownBy(
              () -> asFunction.sql("DELETE FROM data_processing.flyway_schema_history").update())
          .as("only migrations change the migration history")
          .rootCause()
          .hasMessageContaining("permission denied");
    }
  }

  /** Runs the migration function on the database as the master. */
  private void migrateAsMaster(String url) {
    startedOn(
            url,
            UNUSED_LOGIN,
            "",
            () ->
                new MigrationHandler(
                    MigrationHandler.asMaster(
                        MigrationHandler.start().getBean(Flyway.class),
                        url,
                        () -> new MasterSecret(username, password))))
        .handleRequest(Map.of(), new FixedLambdaContext());
  }

  /** The highest {@code V<n>__} number among the migration files. */
  private static int latestVersionOf(Resource[] files) {
    return Arrays.stream(files)
        .map(Resource::getFilename)
        .filter(Objects::nonNull)
        .map(VERSIONED_FILE::matcher)
        .filter(Matcher::find)
        .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
        .max()
        .orElseThrow();
  }

  /** Starts a function as Lambda would, with the database settings Lambda's environment gives. */
  private <T> T startedOn(String url, Supplier<T> function) {
    return startedOn(url, username, password, function);
  }

  private static <T> T startedOn(String url, String user, String secret, Supplier<T> function) {
    System.setProperty(URL, url);
    System.setProperty("spring.datasource.username", user);
    System.setProperty("spring.datasource.password", secret);
    return function.get();
  }
}
