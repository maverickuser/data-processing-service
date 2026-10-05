package com.bondplatform.dataprocessing.review.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * An opaque cursor for a paginated list (LLD section 16): where the previous page ended, bound to
 * the list it came from.
 *
 * <p>The token is the URL-safe Base64 of {@code v1}, the scope and the position, followed by a
 * check value: the first 12 bytes of their SHA-256. A changed token fails the check and is
 * rejected. It is not signed with a secret: a forged token could only point elsewhere in a list
 * whose every page the caller may already read.
 *
 * @param scope what the list is, such as a job and its filter; a token is only valid for its scope
 * @param position the sort key of the last item returned
 */
public record PageToken(String scope, long position) {

  private static final String VERSION = "v1";
  private static final int CHECK_BYTES = 12;
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** Returns the token's text. */
  public String encode() {
    byte[] payload = payload(scope, position);
    return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(check(payload));
  }

  /**
   * Returns the position a token holds, or empty if the token is not one this service issued for
   * the scope: malformed, changed, or from another list.
   */
  public static Optional<Long> decode(String token, String scope) {
    int dot = token.indexOf('.');
    if (dot < 0) {
      return Optional.empty();
    }
    byte[] payload;
    byte[] check;
    try {
      payload = DECODER.decode(token.substring(0, dot));
      check = DECODER.decode(token.substring(dot + 1));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (!MessageDigest.isEqual(check, check(payload))) {
      return Optional.empty();
    }
    // The scope may hold line breaks: the version is the first line, the position the last.
    String text = new String(payload, StandardCharsets.UTF_8);
    int first = text.indexOf('\n');
    int last = text.lastIndexOf('\n');
    if (first < 0
        || first == last
        || !text.substring(0, first).equals(VERSION)
        || !text.substring(first + 1, last).equals(scope)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(text.substring(last + 1)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static byte[] payload(String scope, long position) {
    return (VERSION + "\n" + scope + "\n" + position).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] check(byte[] payload) {
    try {
      return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(payload), CHECK_BYTES);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has SHA-256", e);
    }
  }
}
