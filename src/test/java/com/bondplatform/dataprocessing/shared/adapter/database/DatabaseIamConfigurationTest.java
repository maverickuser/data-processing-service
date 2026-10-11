package com.bondplatform.dataprocessing.shared.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.Credentials;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The pool is created but never opens a connection, and a token is signed locally, so no database
 * or AWS access is needed.
 */
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
  void signsTokensWithTheDefaultCredentials() {
    System.setProperty("aws.accessKeyId", "AKIDEXAMPLE");
    System.setProperty("aws.secretAccessKey", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
    try {
      runner
          .withPropertyValues("data-processing.database.iam-authentication=true")
          .run(
              context -> {
                Credentials credentials =
                    context
                        .getBean(HikariDataSource.class)
                        .getCredentialsProvider()
                        .getCredentials();
                assertThat(credentials.getUsername()).isEqualTo("data_processing_app");
                assertThat(credentials.getPassword())
                    .startsWith("db.internal.example:5432/?")
                    .contains("Action=connect", "X-Amz-Credential=AKIDEXAMPLE");
              });
    } finally {
      System.clearProperty("aws.accessKeyId");
      System.clearProperty("aws.secretAccessKey");
    }
  }

  @Test
  void leavesPasswordLoginAloneByDefault() {
    runner.run(
        context ->
            assertThat(context.getBean(HikariDataSource.class).getCredentialsProvider()).isNull());
  }
}
