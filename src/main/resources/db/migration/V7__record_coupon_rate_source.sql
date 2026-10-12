-- The coupon rate comes from the BSE bhavcopy first and from NSDL security details only while no
-- bhavcopy has supplied one (decision 0004). Each security therefore records which dataset its rate
-- came from and, for a bhavcopy, its trade date, so a bhavcopy for an older trade date never
-- replaces a rate from a newer one.
--
-- Additive only: the previous release keeps writing rates without a source while this migration
-- runs, so no constraint ties the columns together. A rate without a source is treated as NSDL's.
ALTER TABLE securities_data.securities
  ADD COLUMN coupon_rate_source TEXT CHECK (coupon_rate_source IN ('BSE_BHAVCOPY', 'NSDL')),
  ADD COLUMN coupon_rate_trade_date DATE;

-- Every rate stored before this release came from NSDL.
UPDATE securities_data.securities
SET coupon_rate_source = 'NSDL'
WHERE coupon_rate_value IS NOT NULL;
