-- Accepted securities-domain data (LLD sections 17 and 22.1).
-- The schema itself is created by Flyway from its configured schema list.
-- Append-only tables use a unique index with NULLS NOT DISTINCT so that two entries whose only
-- difference is an empty field are the same entry (LLD section 13.4).

CREATE TABLE securities_data.securities (
  isin                   TEXT PRIMARY KEY,
  issuer_name            TEXT,
  issuer_ownership_type  TEXT,
  instrument_type        TEXT,
  allotment_date         DATE,
  redemption_date        DATE,
  original_face_value    NUMERIC CHECK (original_face_value >= 0),
  collateral_status      TEXT,
  asset_coverage_basis   TEXT,
  asset_coverage_value   NUMERIC,
  asset_coverage_unit    TEXT CHECK (asset_coverage_unit = 'PERCENT'),
  coupon_rate_value      NUMERIC,
  coupon_rate_unit       TEXT CHECK (coupon_rate_unit = 'PERCENT'),
  coupon_type            TEXT,
  listing_status         TEXT,
  field_sources          JSONB NOT NULL DEFAULT '{}',
  created_at             TIMESTAMPTZ NOT NULL,
  updated_at             TIMESTAMPTZ NOT NULL
);

CREATE TABLE securities_data.security_daily_market_summaries (
  isin              TEXT NOT NULL REFERENCES securities_data.securities (isin) ON DELETE RESTRICT,
  trade_date        DATE NOT NULL,
  exchange_name     TEXT NOT NULL,
  security_code     TEXT,
  open_price        NUMERIC CHECK (open_price >= 0),
  high_price        NUMERIC CHECK (high_price >= 0),
  low_price         NUMERIC CHECK (low_price >= 0),
  close_price       NUMERIC CHECK (close_price >= 0),
  traded_volume     NUMERIC CHECK (traded_volume >= 0 AND traded_volume = trunc(traded_volume)),
  number_of_trades  NUMERIC CHECK (number_of_trades >= 0 AND number_of_trades = trunc(number_of_trades)),
  turnover          NUMERIC CHECK (turnover >= 0),
  face_value        NUMERIC CHECK (face_value >= 0),
  source_request_id UUID NOT NULL,
  source_file       TEXT NOT NULL,
  source_location   TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL,
  updated_at        TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (isin, trade_date, exchange_name)
);

CREATE INDEX security_daily_market_summaries_by_date
  ON securities_data.security_daily_market_summaries (trade_date, exchange_name, isin);

CREATE TABLE securities_data.security_cash_flows (
  id                UUID PRIMARY KEY,
  isin              TEXT NOT NULL REFERENCES securities_data.securities (isin) ON DELETE RESTRICT,
  event_type        TEXT,
  record_date       DATE,
  due_date          DATE,
  amount_payable    NUMERIC,
  payment_date      DATE,
  new_face_value    NUMERIC,
  source_request_id UUID NOT NULL,
  source_file       TEXT NOT NULL,
  source_location   TEXT NOT NULL,
  first_recorded_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX security_cash_flows_distinct_entry
  ON securities_data.security_cash_flows
    (isin, event_type, record_date, due_date, amount_payable, payment_date, new_face_value)
  NULLS NOT DISTINCT;

CREATE TABLE securities_data.security_listings (
  id                UUID PRIMARY KEY,
  isin              TEXT NOT NULL REFERENCES securities_data.securities (isin) ON DELETE RESTRICT,
  exchange_name     TEXT,
  listing_date      DATE,
  source_request_id UUID NOT NULL,
  source_file       TEXT NOT NULL,
  source_location   TEXT NOT NULL,
  first_recorded_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX security_listings_distinct_entry
  ON securities_data.security_listings (isin, exchange_name, listing_date)
  NULLS NOT DISTINCT;

CREATE TABLE securities_data.security_ratings (
  id                  UUID PRIMARY KEY,
  isin                TEXT NOT NULL REFERENCES securities_data.securities (isin) ON DELETE RESTRICT,
  source_category     TEXT NOT NULL CHECK (source_category IN ('CURRENT', 'EARLIER')),
  rating_agency_name  TEXT,
  rating              TEXT,
  outlook             TEXT,
  rating_action       TEXT,
  rating_date         DATE,
  rating_change_date  DATE,
  verification_date   DATE,
  source_request_id   UUID NOT NULL,
  source_file         TEXT NOT NULL,
  source_location     TEXT NOT NULL,
  first_recorded_at   TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX security_ratings_distinct_entry
  ON securities_data.security_ratings
    (isin, source_category, rating_agency_name, rating, outlook, rating_action, rating_date,
     rating_change_date, verification_date)
  NULLS NOT DISTINCT;

CREATE TABLE securities_data.security_collateral_assets (
  id                     UUID PRIMARY KEY,
  isin                   TEXT NOT NULL REFERENCES securities_data.securities (isin) ON DELETE RESTRICT,
  asset_type             TEXT,
  collateral_description TEXT,
  remarks                TEXT,
  source_request_id      UUID NOT NULL,
  source_file            TEXT NOT NULL,
  source_location        TEXT NOT NULL,
  first_recorded_at      TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX security_collateral_assets_distinct_entry
  ON securities_data.security_collateral_assets
    (isin, asset_type, collateral_description, remarks)
  NULLS NOT DISTINCT;
