-- Finding the oldest pending event of an ordering group (LLD section 23.4): the dispatcher asks
-- whether an older event of the same destination and group is still pending.
CREATE INDEX outbox_events_pending_by_group
  ON data_processing.outbox_events (destination, message_group, ordering_key)
  WHERE status = 'PENDING';
