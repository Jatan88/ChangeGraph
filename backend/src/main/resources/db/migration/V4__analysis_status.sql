-- Records whether a saved run was graph analysis or an agent investigation, and how that run finished.
-- Existing rows are graph analyses that completed. Null kind or status in older databases means the same thing.

ALTER TABLE analyses ADD COLUMN kind VARCHAR(16);
ALTER TABLE analyses ADD COLUMN status VARCHAR(32);

UPDATE analyses SET kind = 'GRAPH' WHERE kind IS NULL;
UPDATE analyses SET status = 'COMPLETED' WHERE status IS NULL;
