package com.bondplatform.dataprocessing.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExchangeNameTest {

  @ParameterizedTest
  @ValueSource(strings = {"BSE", " bse ", "Bse"})
  void trimsAndUppercases(String rawValue) {
    assertThat(ExchangeName.of(rawValue)).isEqualTo(new ExchangeName("BSE")).hasToString("BSE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "  "})
  void rejectsBlank(String rawValue) {
    assertThatIllegalArgumentException().isThrownBy(() -> ExchangeName.of(rawValue));
  }

  @Test
  void constructorRejectsValuesThatAreNotNormalized() {
    assertThatIllegalArgumentException().isThrownBy(() -> new ExchangeName("bse"));
    assertThatIllegalArgumentException().isThrownBy(() -> new ExchangeName(" "));
  }
}
