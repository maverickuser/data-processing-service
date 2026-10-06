-- The public read API's sessions are read-only and cut off long statements (LLD section 23.5), on
-- top of its SELECT-only grants: no statement it runs can write, and none can hold the shared
-- database for long. The API Gateway timeout is 29 seconds; a read query takes milliseconds.
ALTER ROLE processing_reader SET default_transaction_read_only = on;
ALTER ROLE processing_reader SET statement_timeout = '10s';
