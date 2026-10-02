package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.util.Date;
import java.util.function.Supplier;

/** Breaks U-ARCH-03. */
public class NewDateReference {
  Object value() {
    return (Supplier<Date>) Date::new;
  }
}
