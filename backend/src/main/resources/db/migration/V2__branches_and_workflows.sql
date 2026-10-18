-- Additive schema for selected branches and webhook workflows.
-- Review before applying. This migration does not drop tables, delete rows, or touch FalkorDB.
-- Existing project_repositories rows keep a null selected_branch; the application uses default_branch until a branch is chosen.

ALTER TABLE project_repositories
    ADD COLUMN selected_branch VARCHAR(255);

CREATE TABLE workflow_runs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    delivery_id VARCHAR(80),
    idempotency_key VARCHAR(180) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    commit_sha VARCHAR(40),
    pull_request_number INTEGER,
    source_branch VARCHAR(255),
    target_branch VARCHAR(255),
    title VARCHAR(300),
    current_task VARCHAR(300),
    review_json VARCHAR(20000),
    developer_note VARCHAR(1000),
    validation_note VARCHAR(500),
    fix_branch VARCHAR(255),
    fix_pull_request_url VARCHAR(300),
    fix_pull_request_number INTEGER,
    error_message VARCHAR(500),
    attempt INTEGER NOT NULL,
    propose_fixes BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT workflow_runs_event_chk CHECK (event_type IN ('PUSH', 'PULL_REQUEST', 'BRANCH_SYNC')),
    CONSTRAINT workflow_runs_status_chk CHECK (status IN (
        'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'AWAITING_HUMAN_REVIEW'
    )),
    CONSTRAINT workflow_runs_attempt_chk CHECK (attempt >= 1),
    CONSTRAINT workflow_runs_idempotency_uk UNIQUE (project_id, idempotency_key)
);

CREATE UNIQUE INDEX workflow_runs_delivery_uk ON workflow_runs (project_id, delivery_id) WHERE delivery_id IS NOT NULL;
CREATE INDEX workflow_runs_project_idx ON workflow_runs (project_id, created_at DESC);

CREATE TABLE workflow_events (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES workflow_runs (id) ON DELETE CASCADE,
    project_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    agent VARCHAR(32) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    summary VARCHAR(300) NOT NULL,
    detail VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT workflow_events_agent_chk CHECK (agent IN ('SYSTEM', 'INGESTION', 'GRAPH', 'CODE_REVIEWER', 'DEVELOPER')),
    CONSTRAINT workflow_events_phase_chk CHECK (phase IN ('DETECTED', 'FETCH', 'GRAPH', 'REVIEW', 'FIX', 'PULL_REQUEST')),
    CONSTRAINT workflow_events_status_chk CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'RETRYING')),
    CONSTRAINT workflow_events_sequence_uk UNIQUE (run_id, sequence_number)
);

CREATE INDEX workflow_events_project_idx ON workflow_events (project_id, created_at DESC);
