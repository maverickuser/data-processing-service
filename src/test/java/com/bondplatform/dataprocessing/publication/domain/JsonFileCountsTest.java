package com.bondplatform.dataprocessing.publication.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bondplatform.dataprocessing.job.domain.JobOutcome;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import org.junit.jupiter.api.Test;

class JsonFileCountsTest {

  @Test
  void outcomeCarriesTheNsdlCountsInTheirDocumentedShape() {
    JobOutcome outcome =
        new JsonFileCounts(6, 5, 1, 2).outcome(JobStatus.COMPLETED_WITH_ERRORS, 4, 12, 3);

    assertThat(outcome)
        .isEqualTo(
            new JobOutcome(
                JobStatus.COMPLETED_WITH_ERRORS,
                "{\"filesListed\":6,\"filesProcessed\":5,\"filesSkipped\":1,"
                    + "\"scalarFieldsChanged\":4,\"collectionEntriesAppended\":12,"
                    + "\"fieldsRejected\":2}",
                3));
  }

  @Test
  void rejectsCountsThatDoNotAddUp() {
    assertThatThrownBy(() -> new JsonFileCounts(6, 4, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Processed and skipped files must add up to the listed files: 4 + 1 != 6");
    assertThatThrownBy(() -> new JsonFileCounts(0, -1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Counts must not be negative");
    assertThatThrownBy(() -> new JsonFileCounts(1, 1, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonFileCounts(1, 2, -1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
