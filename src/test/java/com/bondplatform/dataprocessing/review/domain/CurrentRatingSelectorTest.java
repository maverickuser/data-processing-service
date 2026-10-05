package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** U-REV-04. */
class CurrentRatingSelectorTest {

  private static final Instant EARLY = Instant.parse("2026-09-01T00:00:00Z");
  private static final Instant LATE = Instant.parse("2026-09-27T00:00:00Z");

  @Test
  void latestRatingDateIsCurrentPerAgency() {
    RatingObservation old = current("CRISIL", "AA", "2019-02-15", LATE);
    RatingObservation latest = current("CRISIL", "AAA", "2020-03-01", EARLY);
    RatingObservation other = current("ICRA", "AA+", "2018-01-01", EARLY);

    CurrentRatingSelector.Selection selection =
        CurrentRatingSelector.select(List.of(old, latest, other));

    assertThat(selection.current()).containsExactly(latest, other);
    assertThat(selection.history()).containsExactly(old);
  }

  @Test
  void tiedOrMissingDatesFallBackToMostRecentlyRecorded() {
    RatingObservation first = current("CRISIL", "AA", "2020-03-01", EARLY);
    RatingObservation second = current("CRISIL", "AAA", "2020-03-01", LATE);
    RatingObservation undatedFirst = current("ICRA", "A", null, EARLY);
    RatingObservation undatedSecond = current("ICRA", "A+", null, LATE);

    CurrentRatingSelector.Selection selection =
        CurrentRatingSelector.select(List.of(first, second, undatedFirst, undatedSecond));

    assertThat(selection.current()).containsExactly(second, undatedSecond);
  }

  @Test
  void datedObservationBeatsUndatedOne() {
    RatingObservation dated = current("CRISIL", "AA", "2019-01-01", EARLY);
    RatingObservation undated = current("CRISIL", "AAA", null, LATE);

    assertThat(CurrentRatingSelector.select(List.of(undated, dated)).current())
        .containsExactly(dated);
  }

  // An EARLIER observation is never current, however new; its agency may have no current rating
  @Test
  void earlierCategoryIsAlwaysHistory() {
    RatingObservation current = current("CRISIL", "AA", "2019-01-01", EARLY);
    RatingObservation earlier = earlier("CRISIL", "AAA", "2025-01-01");
    RatingObservation onlyEarlier = earlier("CARE", "A", "2024-01-01");

    CurrentRatingSelector.Selection selection =
        CurrentRatingSelector.select(List.of(earlier, current, onlyEarlier));

    assertThat(selection.current()).containsExactly(current);
    assertThat(selection.history()).containsExactly(earlier, onlyEarlier);
  }

  @Test
  void historyIsNewestRatingDateFirstThenUndated() {
    RatingObservation undated = earlier("A", "X", null);
    RatingObservation older = earlier("B", "X", "2018-01-01");
    RatingObservation newer = earlier("C", "X", "2022-01-01");

    assertThat(CurrentRatingSelector.select(List.of(undated, older, newer)).history())
        .containsExactly(newer, older, undated);
  }

  @Test
  void observationsWithoutAgencyFormOneGroupListedLast() {
    RatingObservation named = current("ICRA", "AA", "2019-01-01", EARLY);
    RatingObservation anonymousOld = current(null, "A", "2018-01-01", EARLY);
    RatingObservation anonymousNew = current(null, "A+", "2020-01-01", EARLY);

    CurrentRatingSelector.Selection selection =
        CurrentRatingSelector.select(List.of(anonymousOld, named, anonymousNew));

    assertThat(selection.current()).containsExactly(named, anonymousNew);
    assertThat(selection.history()).containsExactly(anonymousOld);
  }

  @Test
  void noRatingsSelectNothing() {
    CurrentRatingSelector.Selection selection = CurrentRatingSelector.select(List.of());

    assertThat(selection.current()).isEmpty();
    assertThat(selection.history()).isEmpty();
  }

  private static RatingObservation current(
      @Nullable String agency, String rating, @Nullable String date, Instant recorded) {
    return observation(true, agency, rating, date, recorded);
  }

  private static RatingObservation earlier(
      @Nullable String agency, String rating, @Nullable String date) {
    return observation(false, agency, rating, date, EARLY);
  }

  private static RatingObservation observation(
      boolean current,
      @Nullable String agency,
      String rating,
      @Nullable String date,
      Instant recorded) {
    return new RatingObservation(
        current,
        agency,
        rating,
        null,
        null,
        date == null ? null : LocalDate.parse(date),
        null,
        null,
        recorded);
  }
}
