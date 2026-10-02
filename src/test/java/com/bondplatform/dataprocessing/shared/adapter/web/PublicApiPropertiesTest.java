package com.bondplatform.dataprocessing.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicApiPropertiesTest {

  @Test
  void buildsAbsoluteUrlsWithOrWithoutTrailingSlashInTheOrigin() {
    assertThat(new PublicApiProperties(URI.create("https://processing.kagent.app/")).urlOf("/v1/x"))
        .isEqualTo(URI.create("https://processing.kagent.app/v1/x"));
    assertThat(new PublicApiProperties(URI.create("http://localhost:8080")).urlOf("/v1/x"))
        .isEqualTo(URI.create("http://localhost:8080/v1/x"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"processing.kagent.app", "/v1", "ftp://processing.kagent.app", "mailto:a@b"})
  void originThatIsNotAbsoluteHttpUrlIsRefused(String origin) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new PublicApiProperties(URI.create(origin)))
        .withMessageContaining("public-base-url");
  }

  @Test
  void missingOriginIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> new PublicApiProperties(null));
  }
}
