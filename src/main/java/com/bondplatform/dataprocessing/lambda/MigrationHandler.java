package com.bondplatform.dataprocessing.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.bondplatform.dataprocessing.DataProcessingApplication;
import com.bondplatform.dataprocessing.shared.adapter.database.MasterSecret;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

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
    this(migration(start(), MigrationHandler::secretsManager));
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
            result.targetSchemaVersion, Objects.toString(result.initialSchemaVersion, "none"));
    LOG.info(
        "Migrations applied, migrationsExecuted={}, schemaVersion={}",
        result.migrationsExecuted,
        version);
    return "applied migrations=" + result.migrationsExecuted + " schemaVersion=" + version;
  }

  /** Starts the application context with Flyway configured but not yet migrating. */
  static ConfigurableApplicationContext start() {
    return new SpringApplicationBuilder(DataProcessingApplication.class)
        .web(WebApplicationType.NONE)
        .initializers(MigrationHandler::deferMigration)
        .run("--spring.flyway.enabled=true");
  }

  /**
   * Returns the migration for the started context. In AWS the master secret is named, and the
   * migration logs in as the master user read from it when invoked, never at initialization.
   * Without one, as in tests, it logs in with the configured user and password.
   *
   * @param masterSecret given the environment and the secret's ARN, reads the secret when called
   */
  static Supplier<MigrateResult> migration(
      ConfigurableApplicationContext application,
      BiFunction<Environment, String, Supplier<MasterSecret>> masterSecret) {
    Flyway flyway = application.getBean(Flyway.class);
    Environment environment = application.getEnvironment();
    String secretArn = environment.getProperty("data-processing.database.master-secret-arn", "");
    if (secretArn.isBlank()) {
      return flyway::migrate;
    }
    return asMaster(
        flyway,
        environment.getRequiredProperty("spring.flyway.url"),
        masterSecret.apply(environment, secretArn));
  }

  /** Reads the master secret from Secrets Manager each time it is called. */
  static Supplier<MasterSecret> secretsManager(Environment environment, String secretArn) {
    SecretsManagerClient secrets =
        SecretsManagerClient.builder()
            .region(Region.of(environment.getRequiredProperty("data-processing.database.region")))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    return () ->
        MasterSecret.parse(
            secrets.getSecretValue(request -> request.secretId(secretArn)).secretString());
  }

  /** Migrates with Flyway's configuration, logged in as the master user the secret names. */
  static Supplier<MigrateResult> asMaster(
      Flyway flyway, String url, Supplier<MasterSecret> secret) {
    return () -> {
      MasterSecret master = secret.get();
      Configuration configuration = flyway.getConfiguration();
      return Flyway.configure(configuration.getClassLoader())
          .configuration(configuration)
          .dataSource(url, master.username(), master.password())
          .load()
          .migrate();
    };
  }

  /** Keeps Flyway from migrating as the context starts; the invocation migrates instead. */
  private static void deferMigration(ConfigurableApplicationContext application) {
    FlywayMigrationStrategy deferred = flyway -> {};
    application.getBeanFactory().registerSingleton("deferredMigration", deferred);
  }
}
