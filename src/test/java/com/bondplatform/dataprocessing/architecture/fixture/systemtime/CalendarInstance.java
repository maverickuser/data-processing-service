package com.bondplatform.dataprocessing.architecture.fixture.systemtime;

import java.util.Calendar;

/** Breaks U-ARCH-03. */
public class CalendarInstance {
  Object value() {
    return Calendar.getInstance();
  }
}
