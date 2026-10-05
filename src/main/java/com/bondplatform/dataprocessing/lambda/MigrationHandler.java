package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Lambda entry point for the migration function, invoked by the deployment workflow before the new
 * code version takes traffic (LLD sections 18 and 23.6): applies the pending Flyway migrations. The
 * event's contents are not used. A failed migration propagates, so the invocation fails and the
 * deployment stops.
 *
 * <p>The migrations run when the function is invoked, never while it initializes: with SnapStart,
 * initialization happens when a version is published, before the workflow decides to migrate.
 */
public class MigrationHandler implements RequestHandler<Map<String, Object>, String> {

  private static final Logger LOG = LoggerFactory.getLogger(MigrationHandler.class);

  private final Supplier<MigrateResult> migrate;

  /** Used by Lambda: starts the application context without a web server. */
  public MigrationHandler() {
    this(
        new SpringApplicationBuilder(DataProcessingApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.enabled=true")
                .initializers(MigrationHandler::deferMigration)
                .run()
                .getBean(Flyway.class)
            ::migrate);
  }

  /** Creates a handler that migrates with the given function. */
  MigrationHandler(Supplier<MigrateResult> migrate) {
    this.migrate = migrate;
  }

  @Override
  public String handleRequest(Map<String, Object> event, Context context) {
    MigrateResult result = migrate.get();
    String version =
        Objects.requireNonNullElse(
            result.targetSchemaVersion, String.valueOf(result.initialSchemaVersion));
    LOG.info(
        "Migrations applied, migrationsExecuted={}, schemaVersion={}",
        result.migrationsExecuted,
        version);
    return "applied migrations=" + result.migrationsExecuted + " schemaVersion=" + version;
  }

  /** Keeps Flyway from migrating as the context starts; the invocation migrates instead. */
  private static void deferMigration(ConfigurableApplicationContext application) {
    FlywayMigrationStrategy deferred = flyway -> {};
    application.getBeanFactory().registerSingleton("deferredMigration", deferred);
  }
}
