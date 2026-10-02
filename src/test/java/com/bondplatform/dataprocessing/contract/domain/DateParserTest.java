package com.bondplatform.dataprocessing.contract.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Test case U-DATE-01. */
class DateParserTest {

  @ParameterizedTest
  @CsvSource({
    "10-06-2019, 2019-06-10",
    "2019-06-10, 2019-06-10",
    "29-02-2028, 2028-02-29",
    "31-12-2099, 2099-12-31",
    "01-01-2026, 2026-01-01",
    "01-01-1900, 1900-01-01"
  })
  void acceptsBothSourceFormats(String text, LocalDate expected) {
    assertThat(DateParser.parse(text)).isEqualTo(new FieldResult.Valid<>(expected));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "32-13-2026",
        "2026-02-30",
        "30-02-2026",
        "29-02-2026",
        "10/06/2019",
        "10-6-2019",
        "2019-6-10",
        "10-06-19",
        "20190610",
        "10-06-2019 ",
        "",
        "today",
        "+2019-06-10",
        "10-06-2019T00:00:00",
        "0000-01-01",
        "01-01-0000",
        "31-12-1899",
        "0019-06-10"
      })
  void rejectsImpossibleDatesAndOtherFormats(String text) {
    assertThat(DateParser.parse(text))
        .isInstanceOfSatisfying(
            FieldResult.Rejected.class,
            rejected -> assertThat(rejected.code()).isEqualTo(ErrorCode.INVALID_DATE));
  }
}
