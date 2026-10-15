-- Until nsdl-security-json-v4, interest cash flows were stored without their amount: NSDL leaves
-- amountPayable null in interestCashFlowSchedule and puts the interest paid in amountRedeem
-- (decision 0009). The amount is part of a cash flow's identity, so the corrected entries would
-- be stored beside these. This one-off repair removes them; rerunning the affected NSDL requests
-- stores them again with their amounts. Every interest entry NSDL had published by 2026-10-14
-- carried an amountRedeem, so no row removed here is the correct one.
DELETE FROM securities_data.security_cash_flows
WHERE source_category = 'INTEREST'
  AND amount_payable IS NULL;
