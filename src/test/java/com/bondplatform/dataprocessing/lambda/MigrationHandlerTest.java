package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class MigrationHandlerTest {

  private static final String SECRET_ARN =
      "arn:aws:secretsmanager:ap-south-1:123456789012:secret:rds!db-1";

  @Test
  void reportsHowManyMigrationsRanAndTheVersionReached() {
    MigrationHandler handler = new MigrationHandler(() -> result("3", "5", 2));

    assertThat(handler.handleRequest(Map.of(), new FixedLambdaContext()))
        .isEqualTo("applied migrations=2 schemaVersion=5");
  }

  @Test
  void reportsTheCurrentVersionWhenNothingWasPending() {
    MigrationHandler handler = new MigrationHandler(() -> result("5", null, 0));

    assertThat(handler.handleRequest(Map.of(), new FixedLambdaContext()))
        .isEqualTo("applied migrations=0 schemaVersion=5");
  }

  @Test
  void reportsNoneWhenTheDatabaseHasNoVersionAtAll() {
    MigrationHandler handler = new MigrationHandler(() -> result(null, null, 0));

    assertThat(handler.handleRequest(Map.of(), new FixedLambdaContext()))
        .isEqualTo("applied migrations=0 schemaVersion=none");
  }

  @Test
  void failedMigrationFailsTheInvocation() {
    MigrationHandler handler =
        new MigrationHandler(
            () -> {
              throw new FlywayException("checksum mismatch");
            });

    assertThatThrownBy(() -> handler.handleRequest(Map.of(), new FixedLambdaContext()))
        .isInstanceOf(FlywayException.class);
  }

  @Test
  void migratesWithTheConfiguredLoginWhenNoMasterSecretIsNamed() {
    Flyway flyway = mock(Flyway.class);
    MigrateResult applied = result("4", "5", 1);
    when(flyway.migrate()).thenReturn(applied);

    assertThat(
            MigrationHandler.migration(
                    context(flyway, ""),
                    (environment, arn) -> {
                      throw new AssertionError("no secret is read");
                    })
                .get())
        .isSameAs(applied);
  }

  @Test
  void readsTheNamedMasterSecretOnlyWhenTheMigrationRuns() {
    Flyway flyway = mock(Flyway.class);
    List<String> reads = new ArrayList<>();

    Supplier<MigrateResult> migration =
        MigrationHandler.migration(
            context(flyway, SECRET_ARN),
            (environment, arn) ->
                () -> {
                  reads.add(arn);
                  throw new IllegalStateException("The master secret has no username or password");
                });

    assertThat(reads).isEmpty();
    assertThatThrownBy(migration::get).hasMessageContaining("no username or password");
    assertThat(reads).containsExactly(SECRET_ARN);
    verify(flyway, never()).migrate();
  }

  @Test
  void secretsManagerIsNotCalledUntilTheSecretIsRead() {
    MockEnvironment environment =
        new MockEnvironment().withProperty("data-processing.database.region", "ap-south-1");

    assertThat(MigrationHandler.secretsManager(environment, SECRET_ARN)).isNotNull();
  }

  private static ConfigurableApplicationContext context(Flyway flyway, String secretArn) {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    when(context.getBean(Flyway.class)).thenReturn(flyway);
    when(context.getEnvironment())
        .thenReturn(
            new MockEnvironment()
                .withProperty("data-processing.database.master-secret-arn", secretArn)
                .withProperty("data-processing.database.region", "ap-south-1")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db.internal.example/x"));
    return context;
  }

  /** Flyway leaves the target version unset when nothing ran. */
  private static MigrateResult result(
      @Nullable String initial, @Nullable String target, int executed) {
    MigrateResult result =
        new MigrateResult("11", "data_processing", "data_processing", "PostgreSQL");
    result.initialSchemaVersion = initial;
    result.targetSchemaVersion = target;
    result.migrationsExecuted = executed;
    return result;
  }
}
