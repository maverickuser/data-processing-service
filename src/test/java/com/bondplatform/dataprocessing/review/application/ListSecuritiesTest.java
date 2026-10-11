package com.bondplatform.dataprocessing.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.domain.IsinPage;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ListSecuritiesTest {

  private final SecurityReviewRepository securities = mock(SecurityReviewRepository.class);
  private final ListSecurities list = new ListSecurities(securities);

  @Test
  void pageOfFiftyOneShowsFiftyAndPointsAtTheLastShown() {
    when(securities.isins(null, 51)).thenReturn(isins(51));

    IsinPage page = list.list(null);

    assertThat(page.items()).hasSize(50).first().isEqualTo("INE000000000");
    assertThat(PageToken.decode(Objects.requireNonNull(page.nextToken()), "securities"))
        .contains("INE000000049");
  }

  @Test
  void nextPageStartsAfterTheTokenAndLastPageHasNoToken() {
    when(securities.isins("INE000000049", 51)).thenReturn(isins(2));

    IsinPage page = list.list(new PageToken("securities", "INE000000049").encode());

    assertThat(page.items()).hasSize(2);
    assertThat(page.nextToken()).isNull();
  }

  @Test
  void noSecuritiesIsAnEmptyPage() {
    when(securities.isins(null, 51)).thenReturn(List.of());

    assertThat(list.list(null)).isEqualTo(new IsinPage(List.of(), null));
  }

  // A stored ISIN is not format-checked, so one holding a line break still pages exactly
  @Test
  void isinWithLineBreakRoundTripsThroughTheToken() {
    List<String> listed = new java.util.ArrayList<>(isins(49));
    listed.add("INE\n1");
    listed.add("INE\n2");
    when(securities.isins(null, 51)).thenReturn(listed);
    String token = Objects.requireNonNull(list.list(null).nextToken());
    when(securities.isins("INE\n1", 51)).thenReturn(List.of("INE\n2"));

    assertThat(list.list(token).items()).containsExactly("INE\n2");
  }

  @Test
  void tokenFromAnotherListOrChangedIsRejectedBeforeReading() {
    for (String token :
        List.of("changed", new PageToken("security-summaries\nX\n\n", "INE1").encode())) {
      assertThatThrownBy(() -> list.list(token))
          .isInstanceOf(InvalidQueryException.class)
          .hasMessage("The pageToken is not valid.");
    }
    verifyNoInteractions(securities);
  }

  private static List<String> isins(int count) {
    return IntStream.range(0, count).mapToObj("INE%09d"::formatted).toList();
  }
}
