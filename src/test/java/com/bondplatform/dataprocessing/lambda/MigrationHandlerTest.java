package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class MigrationHandlerTest {

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

    assertThat(MigrationHandler.migration(context(flyway, "")).get()).isSameAs(applied);
  }

  @Test
  void readsTheMasterSecretOnlyWhenInvoked() {
    Flyway flyway = mock(Flyway.class);

    MigrationHandler.migration(
        context(flyway, "arn:aws:secretsmanager:ap-south-1:123456789012:secret:rds!db-1"));

    verifyNoInteractions(flyway);
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
