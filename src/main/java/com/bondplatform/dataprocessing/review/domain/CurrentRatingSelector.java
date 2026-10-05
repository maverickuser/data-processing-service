package com.bondplatform.dataprocessing.review.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Derives a security's current ratings at read time (LLD section 13.6).
 *
 * <p>For each agency, the current rating is its {@code CURRENT}-category observation with the
 * latest rating date; a dated observation beats an undated one, and when dates tie or are both
 * absent the most recently recorded wins. Every other observation, {@code EARLIER} ones included,
 * is history, newest rating date first. An agency that stops appearing keeps its last current
 * rating. Agencies are matched by exact name; observations without one form their own group.
 */
public final class CurrentRatingSelector {

  /** Latest rating date first, undated last; then most recently recorded first. */
  static final Comparator<RatingObservation> NEWEST_FIRST =
      Comparator.comparing(
              RatingObservation::ratingDate,
              Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder()))
          .thenComparing(RatingObservation::firstRecordedAt)
          .reversed();

  private static final Comparator<RatingObservation> BY_AGENCY =
      Comparator.comparing(
          RatingObservation::ratingAgencyName, Comparator.nullsLast(Comparator.naturalOrder()));

  private CurrentRatingSelector() {}

  /**
   * The split of a security's ratings.
   *
   * @param current one per agency, by agency name
   * @param history every other observation, newest first
   */
  public record Selection(List<RatingObservation> current, List<RatingObservation> history) {

    /** Copies the lists. */
    public Selection {
      current = List.copyOf(current);
      history = List.copyOf(history);
    }
  }

  /** Splits the observations into current ratings and history. */
  public static Selection select(List<RatingObservation> observations) {
    Map<Optional<String>, RatingObservation> current = new LinkedHashMap<>();
    observations.stream()
        .filter(RatingObservation::current)
        .sorted(NEWEST_FIRST)
        .forEach(
            observation ->
                current.putIfAbsent(
                    Optional.ofNullable(observation.ratingAgencyName()), observation));
    List<RatingObservation> history = new ArrayList<>(observations);
    current.values().forEach(chosen -> history.remove(chosen));
    history.sort(NEWEST_FIRST);
    return new Selection(current.values().stream().sorted(BY_AGENCY).toList(), history);
  }
}
