package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.bondplatform.dataprocessing.persistence.PostgresIntegrationTest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The scheduled and migration functions start as Lambda starts them, through their public
 * constructors, and each handles a fixture event (plan PR 43). The API and worker functions are
 * started the same way in {@link EventIngestionRejectionsIT} and {@link WorkerStartupIT}.
 */
class FunctionStartupIT extends PostgresIntegrationTest {

  private static final String URL = "spring.datasource.url";
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
  void migrationAppliesEveryMigrationToANewDatabaseOnce() throws IOException {
    jdbc.sql("DROP DATABASE IF EXISTS migration_check").update();
    jdbc.sql("CREATE DATABASE migration_check").update();
    Resource[] files =
        new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
    final int migrations = files.length;
    final int latestVersion = latestVersionOf(files);
    String newDatabase = databaseUrl.replaceFirst("/[^/?]+(\\?|$)", "/migration_check$1");
    MigrationHandler migration = startedOn(newDatabase, MigrationHandler::new);

    assertThat(migration.handleRequest(Map.of(), new FixedLambdaContext()))
        .as("every migration is applied on the first invocation, none at startup")
        .isEqualTo("applied migrations=" + migrations + " schemaVersion=" + latestVersion);
    assertThat(migration.handleRequest(Map.of(), new FixedLambdaContext()))
        .isEqualTo("applied migrations=0 schemaVersion=" + latestVersion);
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
    System.setProperty(URL, url);
    System.setProperty("spring.datasource.username", username);
    System.setProperty("spring.datasource.password", password);
    return function.get();
  }
}
