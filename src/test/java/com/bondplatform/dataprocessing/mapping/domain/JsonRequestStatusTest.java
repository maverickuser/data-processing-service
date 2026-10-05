package com.bondplatform.dataprocessing.mapping.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.canonical.domain.JsonCanonicalField;
import com.bondplatform.dataprocessing.canonical.domain.JsonFieldReader;
import com.bondplatform.dataprocessing.canonical.domain.JsonFileCanonical;
import com.bondplatform.dataprocessing.canonical.domain.ValidationIssue;
import com.bondplatform.dataprocessing.contract.domain.FieldType;
import com.bondplatform.dataprocessing.contract.domain.RuleRegistry;
import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.job.domain.JobStatus;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

// U-OUT-02
class JsonRequestStatusTest {

  private static final JsonFieldReader READER = new JsonFieldReader(RuleRegistry.standard());
  private static final JsonFileCanonical USABLE = file(text("Simple"));
  private static final JsonFileCanonical PLACEHOLDERS = file(text("-"), text("N.A."));
  private static final JsonFileCanonical SKIPPED =
      new JsonFileCanonical.Skipped(new ValidationIssue(ErrorCode.MALFORMED_JSON, "line 1"));

  // Unchanged valid data is still usable: the status is decided before comparing with stored data
  @Test
  void usableDataWithoutErrorsCompletes() {
    assertThat(JsonRequestStatus.of(List.of(USABLE), 0)).isEqualTo(JobStatus.COMPLETED);
  }

  @Test
  void usableDataWithErrorsCompletesWithErrors() {
    assertThat(JsonRequestStatus.of(List.of(USABLE, SKIPPED), 1))
        .isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
  }

  @Test
  void onlyPlaceholdersOrSkippedFilesFail() {
    assertThat(JsonRequestStatus.of(List.of(PLACEHOLDERS), 0)).isEqualTo(JobStatus.FAILED);
    assertThat(JsonRequestStatus.of(List.of(PLACEHOLDERS, SKIPPED), 1)).isEqualTo(JobStatus.FAILED);
    assertThat(JsonRequestStatus.of(List.of(), 0)).isEqualTo(JobStatus.FAILED);
  }

  private static JsonFileCanonical file(JsonCanonicalField... scalars) {
    return new JsonFileCanonical.Read(List.of(scalars), List.of(), List.of(), List.of());
  }

  private static JsonCanonicalField text(String value) {
    return READER.read("coupon_type", "$.couponType", FieldType.TEXT, new SourceValue.Text(value));
  }
}
