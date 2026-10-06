package com.bondplatform.dataprocessing.shared.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The pool is created but never opens a connection, so no database or AWS access is needed. */
class DatabaseIamConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
          .withUserConfiguration(DatabaseIamConfiguration.class)
          .withPropertyValues(
              "spring.datasource.url=jdbc:postgresql://db.internal.example:5432/data_processing",
              "spring.datasource.username=data_processing_app",
              "data-processing.database.region=ap-south-1");

  @Test
  void givesThePoolIamTokensWhenSwitchedOn() {
    runner
        .withPropertyValues("data-processing.database.iam-authentication=true")
        .run(
            context -> {
              HikariDataSource pool = context.getBean(HikariDataSource.class);
              assertThat(pool.getCredentialsProvider()).isInstanceOf(RdsIamCredentials.class);
            });
  }

  @Test
  void leavesPasswordLoginAloneByDefault() {
    runner.run(
        context ->
            assertThat(context.getBean(HikariDataSource.class).getCredentialsProvider()).isNull());
  }
}
