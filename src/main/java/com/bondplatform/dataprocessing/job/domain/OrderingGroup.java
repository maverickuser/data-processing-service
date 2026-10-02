package com.bondplatform.dataprocessing.job.domain;

import com.bondplatform.dataprocessing.shared.domain.Isin;
import com.bondplatform.dataprocessing.shared.domain.TradeDate;

/**
 * The set of jobs that must run one at a time, in the order they were accepted (LLD sections 8.2
 * and 13.7). Jobs in different groups may run in parallel.
 *
 * @param value the group key, also used as the queue's message group
 */
public record OrderingGroup(String value) {

  /** All files for one trading date form a group. */
  public static OrderingGroup forTradeDate(TradeDate tradeDate) {
    return new OrderingGroup("trade-date:" + tradeDate);
  }

  /** All requests about one security form a group. */
  public static OrderingGroup forIsin(Isin isin) {
    return new OrderingGroup("isin:" + isin);
  }

  @Override
  public String toString() {
    return value;
  }
}
