package com.bondplatform.dataprocessing.shared.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.util.Credentials;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

class RdsIamCredentialsTest {

  private static final RdsUtilities RDS =
      RdsUtilities.builder()
          .region(Region.AP_SOUTH_1)
          .credentialsProvider(
              StaticCredentialsProvider.create(
                  AwsBasicCredentials.create("AKIDEXAMPLE", "secret-key")))
          .build();

  @Test
  void signsTokenForTheDatabaseHostPortAndUser() {
    Credentials credentials =
        new RdsIamCredentials(
                RDS,
                "jdbc:postgresql://db.internal.example:6543/data_processing?sslmode=verify-full",
                "data_processing_app")
            .getCredentials();

    assertThat(credentials.getUsername()).isEqualTo("data_processing_app");
    assertThat(credentials.getPassword())
        .startsWith("db.internal.example:6543/?")
        .contains("Action=connect", "DBUser=data_processing_app")
        .contains("X-Amz-Credential=AKIDEXAMPLE%2F")
        .contains("%2Fap-south-1%2Frds-db%2Faws4_request")
        .contains("X-Amz-Expires=900");
  }

  @Test
  void usesThePostgresPortWhenTheUrlNamesNone() {
    Credentials credentials =
        new RdsIamCredentials(RDS, "jdbc:postgresql://db.internal.example/data_processing", "app")
            .getCredentials();

    assertThat(credentials.getPassword()).startsWith("db.internal.example:5432/?");
  }

  @Test
  void signsFreshTokenForEveryConnection() {
    RdsIamCredentials provider =
        new RdsIamCredentials(RDS, "jdbc:postgresql://db.internal.example/x", "app");

    assertThat(provider.getCredentials()).isNotSameAs(provider.getCredentials());
  }

  @Test
  void rejectsUrlWithoutHost() {
    assertThatThrownBy(() -> new RdsIamCredentials(RDS, "jdbc:postgresql:data_processing", "app"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No database host");
  }
}
