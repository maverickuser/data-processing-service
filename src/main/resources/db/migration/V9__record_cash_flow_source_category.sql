-- NSDL publishes two cash-flow lists, cashFlowSchedule and interestCashFlowSchedule; both are now
-- stored as cash flows, each labelled with its list (decision 0007). Existing rows came from the
-- scheduled list.
--
-- Additive only: the unique index over the business columns is unchanged, so the category is not
-- part of a cash flow's identity and the previous release keeps inserting with its conflict target
-- while this migration runs.
ALTER TABLE securities_data.security_cash_flows
  ADD COLUMN source_category TEXT NOT NULL DEFAULT 'SCHEDULE'
    CHECK (source_category IN ('SCHEDULE', 'INTEREST'));
