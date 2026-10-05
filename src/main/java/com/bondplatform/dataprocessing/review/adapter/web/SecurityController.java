package com.bondplatform.dataprocessing.review.adapter.web;

import com.bondplatform.dataprocessing.review.application.GetSecurity;
import com.bondplatform.dataprocessing.shared.adapter.web.ApiProblemException;
import com.bondplatform.dataprocessing.shared.adapter.web.ProblemType;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves the combined view of one security (LLD section 20.1). */
@RestController
public class SecurityController {

  static final String NOT_FOUND = "No security has this ISIN.";

  private final GetSecurity getSecurity;

  /** Creates the controller. */
  public SecurityController(GetSecurity getSecurity) {
    this.getSecurity = getSecurity;
  }

  /**
   * Returns the security. The ISIN is trimmed and uppercased first; a blank one, or one holding a
   * control character, names no security.
   *
   * @throws ApiProblemException {@code 404} if no security has the ISIN
   */
  @GetMapping(path = "/v1/securities/{isin}", produces = MediaType.APPLICATION_JSON_VALUE)
  public SecurityResponse get(@PathVariable String isin) {
    String normalized = isin.strip().toUpperCase(Locale.ROOT);
    // No ISIN is blank or holds a control character, which PostgreSQL text cannot always store
    if (normalized.isEmpty() || normalized.chars().anyMatch(Character::isISOControl)) {
      throw notFound();
    }
    return getSecurity
        .find(Isin.of(normalized))
        .map(SecurityResponse::of)
        .orElseThrow(SecurityController::notFound);
  }

  private static ApiProblemException notFound() {
    return new ApiProblemException(ProblemType.NOT_FOUND, NOT_FOUND);
  }
}
