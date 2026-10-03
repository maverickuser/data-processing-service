package com.bondplatform.dataprocessing.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceFormatTest {

  @Test
  void namesAreReadInAnyCaseAndOthersAreUnknown() {
    assertThat(SourceFormat.of("csv")).contains(SourceFormat.CSV);
    assertThat(SourceFormat.of("JSON")).contains(SourceFormat.JSON);
    assertThat(SourceFormat.of("xml")).isEmpty();
  }
}
