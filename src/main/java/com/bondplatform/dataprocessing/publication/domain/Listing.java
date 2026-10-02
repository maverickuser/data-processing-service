package com.bondplatform.dataprocessing.publication.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** One exchange listing of a security, as observed in the source. Every value is optional. */
public record Listing(@Nullable String exchangeName, @Nullable LocalDate listingDate) {}
