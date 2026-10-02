package com.bondplatform.dataprocessing.admission.domain;

import com.bondplatform.dataprocessing.admission.domain.Submission.Inputs;
import com.bondplatform.dataprocessing.admission.domain.SubmissionError.Code;
import com.bondplatform.dataprocessing.shared.domain.ExchangeName;
import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The admission rules that differ by dataset: the fetch event type, the shape of the inputs, and
 * the subject that must agree with them.
 */
enum InputRules {

  /** A day's bhavcopy from BSE. */
  BSE_DEBT_TRADES("urn:bond-platform:dataset:bse-debt-trades", "daily-bhavcopy") {

    private static final String EXCHANGE = "BSE";
    private static final String SUBJECT_PREFIX = "exchange/BSE/trade-date/";

    @Override
    @Nullable Inputs read(Map<String, Object> inputs, @Nullable String subject, Faults faults) {
      faults.onlyProperties(inputs, INPUTS, Set.of("exchangeName", "tradeDate"));
      faults.constant(inputs, INPUTS, "exchangeName", EXCHANGE);
      String dateText = faults.text(inputs, INPUTS, "tradeDate");
      if (dateText == null) {
        return null;
      }
      TradeDate tradeDate;
      try {
        tradeDate = TradeDate.parseIso(dateText);
      } catch (IllegalArgumentException e) {
        faults.inBody(INPUTS + "/tradeDate", Code.INVALID_VALUE, "Expected a YYYY-MM-DD date.");
        return null;
      }
      if (subject != null && !subject.equals(SUBJECT_PREFIX + tradeDate)) {
        faults.inBody(
            SUBJECT, Code.MISMATCH, "Expected " + SUBJECT_PREFIX + " and the trade date.");
      }
      return new Inputs.Bse(ExchangeName.of(EXCHANGE), tradeDate);
    }
  },

  /** The details of one security from NSDL. */
  NSDL_SECURITY("urn:bond-platform:dataset:nsdl-security", "nsdl-bond-data") {

    private static final String SUBJECT_PREFIX = "isin/";

    @Override
    @Nullable Inputs read(Map<String, Object> inputs, @Nullable String subject, Faults faults) {
      faults.onlyProperties(inputs, INPUTS, Set.of("isin_code"));
      String isinText = faults.text(inputs, INPUTS, "isin_code");
      if (isinText == null) {
        return null;
      }
      if (!hasVisibleCharacter(isinText)) {
        faults.inBody(INPUTS + "/isin_code", Code.INVALID_VALUE, "Expected a non-blank ISIN.");
        return null;
      }
      Isin isin = Isin.of(isinText);
      if (subject != null) {
        boolean wellFormed = subject.startsWith(SUBJECT_PREFIX) && hasNoSpace(subject);
        boolean agrees =
            wellFormed
                && subject.length() > SUBJECT_PREFIX.length()
                && Isin.of(subject.substring(SUBJECT_PREFIX.length())).equals(isin);
        if (!agrees) {
          faults.inBody(
              SUBJECT, Code.MISMATCH, "Expected isin/ and the ISIN of the inputs, without spaces.");
        }
      }
      return new Inputs.Nsdl(isin);
    }
  };

  private static final String INPUTS = "/data/inputs";
  private static final String SUBJECT = "/subject";
  private static final Pattern VISIBLE = Pattern.compile(".*[^\\s\\p{Z}\\uFEFF].*", Pattern.DOTALL);
  private static final Pattern SPACE = Pattern.compile("[\\s\\p{Z}\\uFEFF]");

  private final String dataset;
  private final String eventType;

  InputRules(String dataset, String eventType) {
    this.dataset = dataset;
    this.eventType = eventType;
  }

  /** Returns the rules for a dataset URN, or null when this service has none for it. */
  static @Nullable InputRules forDataset(String datasetUrn) {
    for (InputRules rules : values()) {
      if (rules.dataset.equals(datasetUrn)) {
        return rules;
      }
    }
    return null;
  }

  /** Returns the fetch event type a submission for this dataset must carry. */
  String eventType() {
    return eventType;
  }

  /**
   * Reads the inputs and checks that the subject agrees with them.
   *
   * @return the typed inputs, or null after recording why they could not be read
   */
  abstract @Nullable Inputs read(
      Map<String, Object> inputs, @Nullable String subject, Faults faults);

  private static boolean hasVisibleCharacter(String text) {
    return VISIBLE.matcher(text).matches();
  }

  private static boolean hasNoSpace(String text) {
    return !SPACE.matcher(text).find();
  }
}
