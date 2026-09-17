-- Existing toilet tables can use a different collation from the database default.
-- Match the canonical name column rather than assuming a particular installation.
-- No facility data, public visibility, or other tables are changed.
SET @duplicate_name_charset = (
    SELECT CHARACTER_SET_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'toilet' AND COLUMN_NAME = 'name'
);
SET @duplicate_name_collation = (
    SELECT COLLATION_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'toilet' AND COLUMN_NAME = 'name'
);
SET @duplicate_work_name_ddl = CONCAT(
    'ALTER TABLE duplicate_name_work_visibility MODIFY group_name VARCHAR(100) CHARACTER SET ',
    @duplicate_name_charset, ' COLLATE ', @duplicate_name_collation, ' NOT NULL'
);
PREPARE duplicate_work_name_statement FROM @duplicate_work_name_ddl;
EXECUTE duplicate_work_name_statement;
DEALLOCATE PREPARE duplicate_work_name_statement;
