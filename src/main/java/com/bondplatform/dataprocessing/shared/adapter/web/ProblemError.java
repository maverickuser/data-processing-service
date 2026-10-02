package com.bondplatform.dataprocessing.shared.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * One entry of a problem response's {@code errors} list: what is wrong with one part of the
 * request.
 *
 * @param location {@code body} or {@code header}
 * @param pointer a JSON Pointer into the request body, for a body error
 * @param header the header name, for a header error
 * @param code a stable code for the kind of error
 * @param message an explanation that is safe to show to the caller
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemError(
    String location,
    @Nullable String pointer,
    @Nullable String header,
    String code,
    String message) {}
