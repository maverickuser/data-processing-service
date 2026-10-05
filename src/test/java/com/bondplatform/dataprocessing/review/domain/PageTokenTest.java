package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** U-REV-03. */
class PageTokenTest {

  private static final String SCOPE = "job-errors\njob-1\n";

  @Test
  void tokenRoundTripsItsPosition() {
    String token = new PageToken(SCOPE, "50").encode();

    assertThat(PageToken.decode(token, SCOPE)).contains("50");
    assertThat(token).doesNotContain("job-1").matches("[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+");
  }

  // AN-6: a scope may hold line breaks and any text; only the exact scope matches
  @Test
  void scopeWithLineBreaksRoundTripsExactly() {
    String scope = "job-errors\n\nline\nINE831R08076";
    String token = new PageToken(scope, "7").encode();

    assertThat(PageToken.decode(token, scope)).contains("7");
    assertThat(PageToken.decode(token, "job-errors\n\nline")).isEmpty();
  }

  @Test
  void tokenOfAnotherScopeIsRejected() {
    String token = new PageToken(SCOPE, "50").encode();

    assertThat(PageToken.decode(token, "job-errors\njob-1\nINE831R08076")).isEmpty();
  }

  @Test
  void changedTokenIsRejected() {
    String token = new PageToken(SCOPE, "50").encode();
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    ("v1\n" + SCOPE.length() + "\n" + SCOPE + "\n9000")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            + token.substring(token.indexOf('.'));
    char last = token.charAt(token.length() - 1);
    String flipped = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

    assertThat(PageToken.decode(forged, SCOPE)).isEmpty();
    assertThat(PageToken.decode(flipped, SCOPE)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "no-dot", "!!!.???", "dGVzdA.dGVzdA"})
  void malformedTokenIsRejected(String token) {
    assertThat(PageToken.decode(token, SCOPE)).isEmpty();
  }

  @Test
  void wellCheckedTokenWithBadContentIsRejected() {
    String prefix = SCOPE.length() + "\n" + SCOPE + "\n";
    assertThat(PageToken.decode(signed("v2\n" + prefix + "1"), SCOPE)).isEmpty();
    assertThat(PageToken.decode(signed("v1\n" + prefix), SCOPE)).contains("");
    assertThat(PageToken.decode(signed("v1\nonly-two"), "only-two")).isEmpty();
  }

  // A position may hold any text, and a scope that starts another scope never matches it
  @Test
  void positionWithLineBreaksRoundTripsAndScopesNeverOverlap() {
    String token = new PageToken("a\nb", "c\nd").encode();

    assertThat(PageToken.decode(token, "a\nb")).contains("c\nd");
    assertThat(PageToken.decode(token, "a")).isEmpty();
  }

  /** Builds a token with a correct check over arbitrary content, as the service would. */
  private static String signed(String payload) {
    String real = new PageToken("x", "0").encode();
    byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] check;
    try {
      check =
          java.util.Arrays.copyOf(
              java.security.MessageDigest.getInstance("SHA-256").digest(bytes), 12);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    assertThat(real).contains(".");
    return encoder.encodeToString(bytes) + "." + encoder.encodeToString(check);
  }
}
