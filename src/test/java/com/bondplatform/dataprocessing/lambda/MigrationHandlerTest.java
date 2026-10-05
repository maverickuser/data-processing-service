package com.bondplatform.dataprocessing.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

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
