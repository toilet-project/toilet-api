-- Deployment candidate only: requires migration V23 and cache contract V2.
-- Manual opt-in, NOT executed by Flyway. Validate on MySQL before enabling hiding.
-- Additive installation: keep every existing trigger and avoid a DROP/CREATE capture gap.
-- The V2 trigger queues the event first, in the same transaction. Only its visibility
-- metadata is refined here; event identity and monotonic revision remain unchanged.
CREATE TRIGGER cache_toilet_visibility_update AFTER UPDATE ON toilet
FOR EACH ROW FOLLOWS cache_toilet_update
UPDATE web_cache_invalidation
SET action=IF(NEW.visibility_status='VISIBLE','UPSERT','PRIVATE'),
    catalog_changed=catalog_changed OR NOT (OLD.visibility_status <=> NEW.visibility_status)
WHERE toilet_id=NEW.toilet_id;
