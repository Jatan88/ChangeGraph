-- Stores the last commit already seen on the selected branch.
-- Existing rows stay null. The next poll queues commits dated after the repository was linked, then stores the tip.

ALTER TABLE project_repositories
    ADD COLUMN last_seen_commit_sha VARCHAR(40);
