package com.bondplatform.dataprocessing.canonical.domain;

import com.bondplatform.dataprocessing.shared.domain.ErrorCode;

/**
 * Why a whole source file is rejected: the job fails and nothing from the file is published.
 *
 * @param detail an explanation for the job's error record; names headers and record numbers, never
 *     cell values
 */
public record FileRejection(ErrorCode code, String detail) {}
