-- Lets the daily retention cleanup find delivered events by delivery time (LLD section 21.1).
CREATE INDEX outbox_events_delivered
  ON data_processing.outbox_events (delivered_at)
  WHERE status = 'DELIVERED';
