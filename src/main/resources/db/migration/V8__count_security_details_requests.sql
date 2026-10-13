-- A traded security whose coupon rate is still unknown has its details requested again, at most
-- three times in all and at least a day apart (decision 0005). Each security therefore counts the
-- requests made for it and when the latest was made. Requests are not changes to the security, so
-- they leave updated_at alone.
--
-- Additive only: the previous release keeps inserting securities while this migration runs, and
-- they get the defaults, as if never requested.
ALTER TABLE securities_data.securities
  ADD COLUMN details_request_count INTEGER NOT NULL DEFAULT 0
    CHECK (details_request_count >= 0),
  ADD COLUMN details_requested_at TIMESTAMPTZ;

-- Every security stored before this release was created by a trade, which requested its details
-- once when the security was created, or by an NSDL request, which is those details.
UPDATE securities_data.securities
SET details_request_count = 1, details_requested_at = created_at;
