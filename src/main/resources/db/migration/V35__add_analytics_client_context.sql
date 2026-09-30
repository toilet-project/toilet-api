-- Request environment is independent of referral source and bot classification.
-- Old rows stay unknown until retained logs provide an exact match.
ALTER TABLE service_analytics_event
    ADD COLUMN client_context VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE service_analytics_event
    ADD COLUMN client_context_evidence VARCHAR(16) NOT NULL DEFAULT 'UNCLASSIFIED';
