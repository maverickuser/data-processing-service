package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.Objects;

/**
 * One collection entry of a security, with where it came from.
 *
 * @param isin the security the entry belongs to
 * @param value the cash flow, listing, rating, or collateral asset
 * @param source the canonical record the entry was mapped from
 * @param <T> the kind of entry
 */
public record SecurityEntry<T>(Isin isin, T value, SourceReference source) {

  /** Rejects an entry missing any part. */
  public SecurityEntry {
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(source, "source");
  }
}
