package com.bondplatform.dataprocessing.review.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One recorded rating of a security (LLD section 15.6).
 *
 * @param current whether it came from the source's current ratings, not its earlier ones
 * @param firstRecordedAt when the service first recorded it
 */
public record RatingObservation(
    boolean current,
    @Nullable String ratingAgencyName,
    @Nullable String rating,
    @Nullable String outlook,
    @Nullable String ratingAction,
    @Nullable LocalDate ratingDate,
    @Nullable LocalDate ratingChangeDate,
    @Nullable LocalDate verificationDate,
    Instant firstRecordedAt) {}
