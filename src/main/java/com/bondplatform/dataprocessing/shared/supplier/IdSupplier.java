package com.bondplatform.dataprocessing.shared.supplier;

import java.util.UUID;

/** Supplies new unique identifiers. Tests substitute a deterministic sequence. */
@FunctionalInterface
public interface IdSupplier {

  /** Returns an identifier that has not been returned before. */
  UUID nextId();
}
