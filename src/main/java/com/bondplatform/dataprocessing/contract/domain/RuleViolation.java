package com.bondplatform.dataprocessing.contract.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * One rule that a parsed value breaks.
 *
 * @param code the stable code shown in the error-review API
 * @param message an explanation for a person reviewing the data
 */
public record RuleViolation(ErrorCode code, String message) {}
