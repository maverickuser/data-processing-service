package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.AbstractMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JobCountsTest {

  // PR 36 review AM-4: exactly the agreed names, in order, whatever was stored
  @Test
  void storedCountsAreShownUnderTheAgreedNamesOnly() {
    Map<String, Long> stored = Map.of("acceptedRows", 1L, "sourceRecords", 2L, "other", 9L);

    assertThat(JobCounts.ofFinished("urn:bond-platform:dataset:bse-debt-trades", stored))
        .containsExactly(
            Map.entry("sourceRecords", 2L),
            Map.entry("acceptedRows", 1L),
            new AbstractMap.SimpleEntry<>("invalidRows", null),
            new AbstractMap.SimpleEntry<>("supersededRows", null));
  }

  @Test
  void jobThatCountedNothingShowsItsDatasetsCountsAsNull() {
    assertThat(JobCounts.ofFinished("urn:bond-platform:dataset:bse-debt-trades", null))
        .containsOnlyKeys("sourceRecords", "acceptedRows", "invalidRows", "supersededRows")
        .allSatisfy((name, value) -> assertThat(value).isNull());
    assertThat(JobCounts.ofFinished("urn:bond-platform:dataset:nsdl-security", null))
        .containsOnlyKeys(
            "filesListed",
            "filesProcessed",
            "filesSkipped",
            "scalarFieldsChanged",
            "collectionEntriesAppended",
            "fieldsRejected");
  }

  @Test
  void datasetWithoutAgreedCountsHasNone() {
    assertThat(JobCounts.ofFinished("urn:bond-platform:dataset:other", null)).isNull();
  }
}
