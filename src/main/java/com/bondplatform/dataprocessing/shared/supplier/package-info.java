/**
 * The only place that reads the system clock or generates random identifiers. Everything else
 * receives a {@link java.time.Clock} or an {@link
 * com.bondplatform.dataprocessing.shared.supplier.IdSupplier}, so tests are deterministic.
 */
@NullMarked
package com.bondplatform.dataprocessing.shared.supplier;

import org.jspecify.annotations.NullMarked;
