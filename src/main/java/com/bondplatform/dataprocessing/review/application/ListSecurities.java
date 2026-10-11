package com.bondplatform.dataprocessing.review.application;

import com.bondplatform.dataprocessing.review.domain.IsinPage;
import com.bondplatform.dataprocessing.review.domain.PageToken;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Answers {@code GET /v1/securities} (LLD section 20.4): every known ISIN in ascending order,
 * {@value IsinPage#SIZE} per page with the same opaque cursor as the other lists.
 */
public class ListSecurities {

  private static final String SCOPE = "securities";

  private final SecurityReviewRepository securities;

  /** Creates the use case. */
  public ListSecurities(SecurityReviewRepository securities) {
    this.securities = securities;
  }

  /**
   * Returns one page of ISINs.
   *
   * @param pageToken the previous page's {@code nextToken}, or {@code null} for the first page
   * @throws InvalidQueryException if the token was not issued for this list
   */
  public IsinPage list(@Nullable String pageToken) {
    // The position is the whole remainder of the token, so a stored ISIN of any text fits
    String after =
        pageToken == null
            ? null
            : PageToken.decode(pageToken, SCOPE)
                .orElseThrow(() -> new InvalidQueryException("The pageToken is not valid."));
    List<String> listed = securities.isins(after, IsinPage.SIZE + 1);
    boolean more = listed.size() > IsinPage.SIZE;
    List<String> shown = more ? listed.subList(0, IsinPage.SIZE) : listed;
    String next = more ? new PageToken(SCOPE, shown.getLast()).encode() : null;
    return new IsinPage(shown, next);
  }
}
