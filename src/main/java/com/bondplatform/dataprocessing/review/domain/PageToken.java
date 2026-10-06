package com.bondplatform.dataprocessing.review.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/**
 * An opaque cursor for a paginated list (LLD sections 16 and 20.2): where the previous page ended,
 * bound to the list it came from.
 *
 * <p>The token is the URL-safe Base64 of {@code v1}, the scope's length, the scope and the
 * position, followed by a check value: the first 12 bytes of their SHA-256. A changed token fails
 * the check and is rejected. It is not signed with a secret: a forged token could only point
 * elsewhere in a list whose every page the caller may already read.
 *
 * @param scope what the list is, such as a job and its filter; a token is only valid for its scope
 * @param position the sort key of the last item returned, as text; it may hold any characters
 */
public record PageToken(String scope, String position) {

  private static final String VERSION = "v1";
  private static final int CHECK_BYTES = 12;

  /** Far longer than any token this service issues; a longer one is refused undecoded. */
  static final int MAX_LENGTH = 1024;

  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  /** Returns the token's text. */
  public String encode() {
    byte[] payload = (prefix(scope) + position).getBytes(StandardCharsets.UTF_8);
    return ENCODER.encodeToString(payload) + "." + ENCODER.encodeToString(check(payload));
  }

  /**
   * Returns the position a token holds, or empty if the token is not one this service issued for
   * the scope: malformed, changed, or from another list.
   */
  public static Optional<String> decode(String token, String scope) {
    if (token.length() > MAX_LENGTH) {
      return Optional.empty();
    }
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
    // The length makes the prefix unique: no scope's prefix starts another scope's prefix
    String text = new String(payload, StandardCharsets.UTF_8);
    String prefix = prefix(scope);
    return text.startsWith(prefix)
        ? Optional.of(text.substring(prefix.length()))
        : Optional.empty();
  }

  private static String prefix(String scope) {
    return VERSION + "\n" + scope.length() + "\n" + scope + "\n";
  }

  private static byte[] check(byte[] payload) {
    try {
      return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(payload), CHECK_BYTES);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java runtime has SHA-256", e);
    }
  }
}
