-- V27 intentionally used a narrow bootstrap allow-list before the Java parser
-- was available. Repair only pure, unambiguous 24-hour source values that the
-- v1 parser already classifies as ALWAYS/open 24 hours. Manual confirmations
-- remain authoritative and are never changed here.

DELETE schedule
  FROM toilet_opening_schedule schedule
  JOIN toilet t ON t.toilet_id = schedule.toilet_id
  JOIN toilet_opening_hours hours ON hours.toilet_id = t.toilet_id
 WHERE hours.manual_override = FALSE
   AND COALESCE(hours.is_open_24h, FALSE) = FALSE
   AND REGEXP_LIKE(
       COALESCE(NULLIF(TRIM(t.open_time_detail), ''), TRIM(t.open_time), ''),
       '^(24[[:space:]]*시간([[:space:]]*개방)?|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))$',
       'c'
   );

UPDATE toilet_opening_hours hours
JOIN toilet t ON t.toilet_id = hours.toilet_id
   SET hours.opening_policy = 'ALWAYS',
       hours.is_open_24h = TRUE,
       hours.normalization_status = 'PARSED',
       hours.confidence = 1.0000,
       hours.parser_version = 'v1',
       hours.source_changed = FALSE,
       hours.confirmed_by_user_id = NULL,
       hours.confirmed_at = NULL,
       hours.updated_at = CURRENT_TIMESTAMP
 WHERE hours.manual_override = FALSE
   AND COALESCE(hours.is_open_24h, FALSE) = FALSE
   AND REGEXP_LIKE(
       COALESCE(NULLIF(TRIM(t.open_time_detail), ''), TRIM(t.open_time), ''),
       '^(24[[:space:]]*시간([[:space:]]*개방)?|00(:00)?[[:space:]]*(~|〜|～|–|—|-)[[:space:]]*(24(:00)?|23:59))$',
       'c'
   );
