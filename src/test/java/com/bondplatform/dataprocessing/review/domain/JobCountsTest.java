package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JobCountsTest {

  @Test
  void storedCountsAreShownAsStored() {
    Map<String, Long> stored = Map.of("sourceRecords", 2L);

    assertThat(JobCounts.ofFinished("urn:bond-platform:dataset:bse-debt-trades", stored))
        .isSameAs(stored);
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
