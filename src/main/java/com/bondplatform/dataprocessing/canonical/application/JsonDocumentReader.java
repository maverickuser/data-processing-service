package com.bondplatform.dataprocessing.canonical.application;

import com.bondplatform.dataprocessing.canonical.domain.JsonRead;

/** Reads a JSON source file strictly, so stage 1 sees exactly what the file holds. */
public interface JsonDocumentReader {

  /**
   * Returns the file's single JSON value, or why the file is skipped: it is not UTF-8, not one
   * well-formed JSON value, or repeats a property name inside one object (LLD section 13.7).
   * Numbers are kept exactly; none passes through floating point (LLD section 13.6).
   */
  JsonRead read(byte[] content);
}
