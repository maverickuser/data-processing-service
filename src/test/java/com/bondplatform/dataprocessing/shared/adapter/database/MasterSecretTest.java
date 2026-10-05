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
  void neverShowsThePassword() {
    assertThat(new MasterSecret("postgres", "p@ss")).hasToString("MasterSecret[username=postgres]");
  }
}
