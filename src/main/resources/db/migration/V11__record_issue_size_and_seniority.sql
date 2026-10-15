-- NSDL's instrument details give the total issue size, in crore rupees, and the seniority in
-- repayment; both are now stored on the security (decision 0010). Additive only: existing rows
-- get them when their NSDL details are next processed.
ALTER TABLE securities_data.securities
  ADD COLUMN total_issue_size NUMERIC CHECK (total_issue_size >= 0),
  ADD COLUMN seniority_repayment TEXT;
