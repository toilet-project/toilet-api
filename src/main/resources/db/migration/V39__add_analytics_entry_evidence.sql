-- New first-entry diagnostics use bounded categories only. No historical inference.
ALTER TABLE service_analytics_event
    ADD COLUMN acquisition_evidence VARCHAR(24) NOT NULL DEFAULT 'UNRECORDED';
ALTER TABLE service_analytics_event
    ADD COLUMN entry_navigation VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';
