package com.bondplatform.dataprocessing.review.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** U-REV-02. */
class RawValuePreviewTest {

  @Test
  void shortTextIsShownWhole() {
    assertThat(RawValuePreview.of("string", "32-13-2026"))
        .isEqualTo(new RawValuePreview("32-13-2026", false));
  }

  @Test
  void textOfExactlyTheLimitIsNotCut() {
    String text = "a".repeat(RawValuePreview.MAX_CHARACTERS);

    assertThat(RawValuePreview.of("string", text)).isEqualTo(new RawValuePreview(text, false));
  }

  @Test
  void longerTextIsCutToTheLimit() {
    RawValuePreview preview = RawValuePreview.of("string", "a".repeat(1_001));

    assertThat(preview.value()).isEqualTo("a".repeat(1_000));
    assertThat(preview.truncated()).isTrue();
  }

  // A character outside the BMP is two UTF-16 units but one Unicode character
  @Test
  void cutCountsCharactersAndNeverSplitsOne() {
    String clef = Character.toString(0x1D11E);
    String text = clef.repeat(1_001);

    RawValuePreview preview = RawValuePreview.of("string", text);

    assertThat(preview.value()).isEqualTo(clef.repeat(1_000));
    assertThat(preview.truncated()).isTrue();
    assertThat(RawValuePreview.of("string", clef.repeat(1_000)).truncated()).isFalse();
  }

  @Test
  void objectOrArrayIsItsJsonTextCutTheSameWay() {
    assertThat(RawValuePreview.of("object", "{\"a\": [1, \"x\"]}"))
        .isEqualTo(new RawValuePreview("{\"a\": [1, \"x\"]}", false));
    assertThat(RawValuePreview.of("array", "[" + "1,".repeat(600) + "1]").truncated()).isTrue();
  }

  @Test
  void numberKeepsItsScaleAndBooleanItsType() {
    assertThat(RawValuePreview.of("number", "89400.00").value())
        .isEqualTo(new BigDecimal("89400.00"));
    assertThat(RawValuePreview.of("boolean", "true")).isEqualTo(new RawValuePreview(true, false));
  }

  @Test
  void noStoredValueOrJsonNullIsOmitted() {
    assertThat(RawValuePreview.of(null, null)).isEqualTo(RawValuePreview.none());
    assertThat(RawValuePreview.of("null", null)).isEqualTo(RawValuePreview.none());
    assertThat(RawValuePreview.none().value()).isNull();
    assertThat(RawValuePreview.none().truncated()).isFalse();
  }

  @Test
  void typeWithoutTextOrUnknownTypeIsDefect() {
    assertThatThrownBy(() -> RawValuePreview.of("string", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RawValuePreview.of("date", "2026-01-01"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("date");
  }
}
