package com.bondplatform.dataprocessing.review.domain;

import org.jspecify.annotations.Nullable;

/**
 * One reviewable error of a job's final attempt (LLD section 16). Locations are public ones: a
 * file's base name and a CSV record number or JSONPath, never a bucket or object key.
 *
 * @param errorId the persistent ID, the same in the preview and the full list
 * @param code the stable error code
 * @param isin the ISIN the error is about, if known
 * @param sourceFile the source file's base name, if the error belongs to one file
 * @param recordNumber the one-based CSV record number, the header being 1
 * @param field the source field
 * @param path the JSONPath, for JSON sources
 * @param rawValue the source value before normalization
 * @param message the explanation
 * @param actionTaken what the service did, if more than rejecting the value
 */
public record JobErrorView(
    String errorId,
    String code,
    @Nullable String isin,
    @Nullable String sourceFile,
    @Nullable Integer recordNumber,
    @Nullable String field,
    @Nullable String path,
    RawValuePreview rawValue,
    String message,
    @Nullable String actionTaken) {}
