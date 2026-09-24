-- Historical events cannot be reclassified: the original user agent is not retained.
-- This flag is not proof of human activity or verified crawler identity.
ALTER TABLE service_analytics_event
    ADD COLUMN traffic_class VARCHAR(16) NOT NULL DEFAULT 'LEGACY';
