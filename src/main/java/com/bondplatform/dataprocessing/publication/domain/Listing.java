package com.bondplatform.dataprocessing.publication.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * One exchange listing of a security, as observed in the source. Every value is optional.
 *
 * @param exchangeName the exchange as the source writes it, with letter case preserved (LLD section
 *     13.4); it is not normalised like the exchange of a daily market summary
 */
public record Listing(@Nullable String exchangeName, @Nullable LocalDate listingDate) {}
