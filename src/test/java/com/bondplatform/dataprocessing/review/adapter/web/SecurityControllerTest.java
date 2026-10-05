package com.bondplatform.dataprocessing.review.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.review.domain.RatingObservation;
import com.bondplatform.dataprocessing.review.domain.SecurityView;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecurityControllerTest {

  private static final Instant AT = Instant.parse("2026-09-27T14:31:02Z");

  private final GetSecurity getSecurity = mock(GetSecurity.class);
  private final SecurityController controller = new SecurityController(getSecurity);

  @Test
  void isinIsTrimmedAndUppercasedAndViewIsShown() {
    RatingObservation rating =
        new RatingObservation(
            true, "ICRA", "AAA", "Stable", "Reaffirm", LocalDate.of(2019, 2, 15), null, null, AT);
    SecurityView view =
        new SecurityView(
            new SecurityView.Scalars(
                "INE831R08076",
                "ABHFL",
                "Non PSU",
                "Debentures",
                LocalDate.of(2019, 6, 10),
                LocalDate.of(2029, 6, 8),
                new BigDecimal("1000000"),
                new BigDecimal("8.94"),
                "Simple",
                "Listed",
                "Unsecured",
                null,
                null,
                AT,
                AT),
            List.of(),
            List.of(rating),
            List.of(),
            List.of(),
            List.of());
    when(getSecurity.find(Isin.of("INE831R08076"))).thenReturn(Optional.of(view));

    SecurityResponse response = controller.get("  ine831r08076 ");

    assertThat(response.isin()).isEqualTo("INE831R08076");
    assertThat(response.couponRate())
        .isEqualTo(new SecurityResponse.Percent(new BigDecimal("8.94"), "PERCENT"));
    assertThat(response.assetCoverage()).isNull();
    assertThat(response.currentRatings())
        .containsExactly(
            new SecurityResponse.Rating(
                "ICRA", "AAA", "Stable", "Reaffirm", LocalDate.of(2019, 2, 15), null, null, AT));
    assertThat(response.collateralAssets()).isEmpty();
    assertThat(response.createdAt()).isEqualTo(AT);
  }

  @Test
  void unknownIsinIsNotFound() {
    when(getSecurity.find(Isin.of("INE000000000"))).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.get("INE000000000"))
        .isInstanceOfSatisfying(
            ApiProblemException.class, e -> assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND));
  }

  // No 400 is documented: a blank ISIN, or one with a control character, names no security
  @Test
  void unusableIsinIsNotFound() {
    for (String isin : List.of(" ", "INE" + (char) 0)) {
      assertThatThrownBy(() -> controller.get(isin))
          .isInstanceOfSatisfying(
              ApiProblemException.class,
              e -> assertThat(e.type()).isEqualTo(ProblemType.NOT_FOUND));
    }
    verifyNoInteractions(getSecurity);
  }
}
