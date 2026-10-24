-- Global newest-first keyset pagination. Existing per-author/per-toilet indexes start with another key.
-- Deployment approval is required before Flyway applies this additive index to the operational DB.
CREATE INDEX idx_review_created ON toilet_review(created_at,review_id);
