-- Off-limits: while the firm works for a client, and for a year after its last placement
-- starts, the client's people aren't approached as candidates. This date, set by hand,
-- replaces that year when a contract says otherwise.
ALTER TABLE company ADD COLUMN off_limits_until DATE;
