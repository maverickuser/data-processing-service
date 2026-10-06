package com.bondplatform.dataprocessing.shared.adapter.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MasterSecretTest {

  @Test
  void readsTheUsernameAndPasswordRdsStores() {
    assertThat(MasterSecret.parse("{\"username\":\"postgres\",\"password\":\"p@ss\"}"))
        .isEqualTo(new MasterSecret("postgres", "p@ss"));
  }

  @Test
  void rejectsSecretWithoutPassword() {
    assertThatThrownBy(() -> MasterSecret.parse("{\"username\":\"postgres\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The master secret has no username or password");
  }

  @Test
  void rejectsSecretWithoutUsername() {
    assertThatThrownBy(() -> MasterSecret.parse("{\"password\":\"p@ss\"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The master secret has no username or password");
  }

  @Test
  void rejectsBlankPassword() {
    assertThatThrownBy(() -> MasterSecret.parse("{\"username\":\"postgres\",\"password\":\" \"}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The master secret has no username or password");
  }

  @Test
  void rejectsMalformedSecretWithoutQuotingIt() {
    assertThatThrownBy(() -> MasterSecret.parse("{\"password\":\"p@ss"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("The master secret is not a JSON object")
        .hasNoCause();
  }

  @Test
  void neverShowsThePassword() {
    assertThat(new MasterSecret("postgres", "p@ss")).hasToString("MasterSecret[username=postgres]");
  }
}
