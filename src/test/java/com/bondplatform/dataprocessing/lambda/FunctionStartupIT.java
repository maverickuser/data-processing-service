package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import com.bondplatform.dataprocessing.shared.adapter.database.MasterSecret;
import java.io.IOException;
import java.util.Arrays;
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

  /** A role of its own, so the grants migration V5 makes are what the functions rely on. */
  private static final String APPLICATION_ROLE = "processing_role_check";

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
    // As in AWS: the functions are configured with the application role, the migration logs in
    // with the master user from the secret
    MigrationHandler migration =
        startedOn(
            newDatabase,
            APPLICATION_ROLE,
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
  void theApplicationRoleCanRunTheScheduledFunctionsButNotChangeTheSchema() {
    jdbc.sql("DROP DATABASE IF EXISTS role_check").update();
    jdbc.sql("CREATE DATABASE role_check").update();
    String newDatabase = databaseUrl.replaceFirst("/[^/?]+(\\?|$)", "/role_check$1");
    startedOn(
            newDatabase,
            APPLICATION_ROLE,
            "",
            () ->
                new MigrationHandler(
                    MigrationHandler.asMaster(
                        MigrationHandler.start().getBean(Flyway.class),
                        newDatabase,
                        () -> new MasterSecret(username, password))))
        .handleRequest(Map.of(), new FixedLambdaContext());
    // The test database has no rds_iam role, so the application role logs in with a password
    jdbc.sql("ALTER ROLE " + APPLICATION_ROLE + " PASSWORD 'role-check'").update();

    assertThat(
            startedOn(newDatabase, APPLICATION_ROLE, "role-check", OutboxSweeperHandler::new)
                .handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("failed stuckJobs=0 delivered outboxEvents=0");
    assertThat(
            startedOn(newDatabase, APPLICATION_ROLE, "role-check", RetentionHandler::new)
                .handleRequest(new ScheduledEvent(), new FixedLambdaContext()))
        .isEqualTo("deleted validationIssues=0 rejectedRecords=0 outboxEvents=0");
    JdbcClient asApplication =
        JdbcClient.create(new DriverManagerDataSource(newDatabase, APPLICATION_ROLE, "role-check"));
    assertThatThrownBy(
            () -> asApplication.sql("CREATE TABLE data_processing.not_allowed (id INT)").update())
        .hasMessageContaining("permission denied");
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
