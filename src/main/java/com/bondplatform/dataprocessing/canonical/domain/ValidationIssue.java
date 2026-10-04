package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * One problem found in a canonical row, either in a single field or across fields.
 *
 * @param code the stable code shown in the error-review API
 * @param message an explanation for a person reviewing the data
 */
public record ValidationIssue(ErrorCode code, String message) {}
