package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.util.Date;

/** Breaks U-ARCH-03. */
public class NewDate {
  Object value() {
    return new Date();
  }
}
