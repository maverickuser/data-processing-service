package com.bondplatform.dataprocessing.publication.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.Objects;

/**
 * Everything one JSON request supplies for a security, after both stages.
 *
 * @param isin the security, from the file names
 * @param scalars the winning field values and the fields to clear
 * @param collections the distinct collection entries
 */
public record SecurityDetails(Isin isin, SecurityScalars scalars, SecurityCollections collections) {

  /** Rejects a missing part. */
  public SecurityDetails {
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(scalars, "scalars");
    Objects.requireNonNull(collections, "collections");
  }
}
