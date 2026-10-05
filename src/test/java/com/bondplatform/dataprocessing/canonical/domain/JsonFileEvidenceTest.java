package com.bondplatform.dataprocessing.canonical.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.bondplatform.dataprocessing.contract.domain.SourceValue;
import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonFileEvidenceTest {

  private static final String WITH_ERRORS = JsonEvidence.WITH_ERRORS;
  private static final JsonRejection.ValueIgnored IGNORED = JsonEvidence.IGNORED;
  private static final ValidationIssue MALFORMED = JsonEvidence.MALFORMED;

  /** The scalars the contract reads under {@code $.coupensVo}. */
  private static final int COUPON_FIELDS =
      (int)
          NsdlContract.CONTRACT.scalars().stream()
              .filter(field -> field.path().startsWith("$.coupensVo."))
              .count();

  @Test
  void listsRejectionsInReviewOrder() {
    JsonFileEvidence file = read(WITH_ERRORS, List.of(IGNORED));

    List<JsonRejection> rejections = file.rejections();

    assertThat(rejections)
        .extracting(JsonRejection::disposition, JsonRejection::path)
        .containsExactly(
            tuple(JsonRejection.Disposition.SECTION_REJECTED, "$.currentRatings"),
            tuple(JsonRejection.Disposition.FIELD_REJECTED, "$.coupensVo.couponDetails.couponRate"),
            tuple(JsonRejection.Disposition.ENTRY_FIELDS_REJECTED, "$.listingDetails[0]"),
            tuple(JsonRejection.Disposition.VALUE_IGNORED, IGNORED.path()));
  }

  @Test
  void fieldIssueKeepsNameRawValueAndError() {
    JsonRejection.Issue issue =
        read(WITH_ERRORS, List.of()).rejections().get(1).issues().getFirst();

    assertThat(issue.field()).isEqualTo("coupon_rate");
    assertThat(issue.path()).isEqualTo("$.coupensVo.couponDetails.couponRate");
    assertThat(issue.rawValue()).isEqualTo(new SourceValue.Text("abc"));
    assertThat(issue.actionTaken()).isNull();
  }

  @Test
  void entryIssuesComeFromItsFailedFields() {
    JsonRejection entry = read(WITH_ERRORS, List.of()).rejections().get(2);

    assertThat(entry.issues())
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.field()).isEqualTo("listing_date");
              assertThat(issue.path()).isEqualTo("$.listingDetails[0].listingDate");
              assertThat(issue.rawValue()).isEqualTo(new SourceValue.Text("32-13-2026"));
            });
  }

  @Test
  void entryWithoutValidFieldsIsSkipped() {
    JsonFileEvidence file =
        read("{\"listingDetails\": [{\"listingDate\": \"31-02-2020\"}]}", List.of());

    assertThat(file.rejections())
        .singleElement()
        .extracting(JsonRejection::disposition)
        .isEqualTo(JsonRejection.Disposition.ENTRY_SKIPPED);
  }

  @Test
  void sectionAndIgnoredValueHaveOneIssueEach() {
    List<JsonRejection> rejections = read(WITH_ERRORS, List.of(IGNORED)).rejections();

    JsonRejection.Issue section = rejections.getFirst().issues().getFirst();
    assertThat(section.field()).isNull();
    assertThat(section.issue().code()).isEqualTo(ErrorCode.INVALID_TYPE);
    assertThat(section.rawValue()).isEqualTo(new SourceValue.Missing());
    assertThat(rejections.getLast().issues())
        .containsExactly(
            new JsonRejection.Issue(
                null, IGNORED.path(), IGNORED.rawValue(), IGNORED.issue(), IGNORED.actionTaken()));
  }

  @Test
  void skippedFileIsOneRejectionAtTheRoot() {
    JsonFileEvidence file = skipped();

    assertThat(file.rejections())
        .singleElement()
        .satisfies(
            rejection -> {
              assertThat(rejection.path()).isEqualTo("$");
              assertThat(rejection.disposition()).isEqualTo(JsonRejection.Disposition.FILE_SKIPPED);
              assertThat(rejection.issues())
                  .containsExactly(
                      new JsonRejection.Issue(
                          null, "$", new SourceValue.Missing(), MALFORMED, null));
            });
    assertThat(file.rejectedFieldCount()).isZero();
  }

  @Test
  void cleanFileHasNoRejections() {
    JsonFileEvidence file =
        read("{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"Simple\"}}}", List.of());

    assertThat(file.rejections()).isEmpty();
    assertThat(file.rejectedFieldCount()).isZero();
  }

  // The rate and the entry's date; a collection of the wrong kind has no fields to count
  @Test
  void countsEveryFailedField() {
    assertThat(read(WITH_ERRORS, List.of()).rejectedFieldCount()).isEqualTo(2);
  }

  // Scalars under a section of the wrong kind fail without errors of their own, and still count
  @Test
  void countsScalarsUnderSectionOfWrongKind() {
    JsonFileEvidence file = read("{\"coupensVo\": []}", List.of());

    assertThat(file.rejections()).hasSize(1);
    assertThat(file.rejectedFieldCount()).isEqualTo(COUPON_FIELDS);
  }

  @Test
  void fileNameIsTheKeysLastSegment() {
    assertThat(skipped().fileName()).isEqualTo("INE831R08076_listings.json");
  }

  @Test
  void skippedFileCannotHaveIgnoredValues() {
    assertThatThrownBy(
            () ->
                new JsonFileEvidence(
                    "bucket",
                    "a/b.json",
                    new JsonFileCanonical.Skipped(MALFORMED),
                    List.of(IGNORED)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a/b.json");
  }

  @Test
  void rejectedFieldAndEntryNeedErrors() {
    JsonFileCanonical.Read clean =
        NsdlContract.canonical(
            "{\"coupensVo\": {\"couponDetails\": {\"couponType\": \"Simple\"}},"
                + " \"listingDetails\": [{\"exchangeName\": \"NSE\"}]}");

    assertThatThrownBy(() -> new JsonRejection.FieldRejected(clean.scalars().getFirst()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("has no errors");
    assertThatThrownBy(() -> new JsonRejection.EntryRejected(clean.entries().getFirst()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("$.listingDetails[0]");
  }

  private static JsonFileEvidence read(String json, List<JsonRejection.ValueIgnored> ignored) {
    return JsonEvidence.read(json, ignored);
  }

  private static JsonFileEvidence skipped() {
    return JsonEvidence.skipped();
  }
}
