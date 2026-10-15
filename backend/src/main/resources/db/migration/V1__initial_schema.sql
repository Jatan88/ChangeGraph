-- ChangeGraph relational schema.
-- Apply only after review: start the app with FLYWAY_ENABLED=true.
-- This migration does not store source code, tokens, or secrets.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT users_email_lowercase CHECK (email = LOWER(email))
);

CREATE UNIQUE INDEX users_email_ci_idx ON users (LOWER(email));

CREATE TABLE projects (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users (id),
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX projects_owner_idx ON projects (owner_id, created_at DESC);

CREATE TABLE project_repositories (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    github_repository_id BIGINT NOT NULL,
    owner_name VARCHAR(100) NOT NULL,
    repository_name VARCHAR(100) NOT NULL,
    default_branch VARCHAR(255) NOT NULL,
    repository_url VARCHAR(300) NOT NULL,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX project_repositories_github_id_idx ON project_repositories (github_repository_id);

CREATE TABLE project_graphs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL UNIQUE REFERENCES projects (id) ON DELETE CASCADE,
    graph_name VARCHAR(64) NOT NULL UNIQUE,
    provider VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT project_graphs_provider_chk CHECK (provider = 'FALKORDB'),
    CONSTRAINT project_graphs_status_chk CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT project_graphs_name_chk CHECK (graph_name ~ '^cg_[0-9a-f]{32}$')
);

CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    requested_commit_sha VARCHAR(40),
    progress_message VARCHAR(300),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ingestion_jobs_status_chk CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'))
);

CREATE INDEX ingestion_jobs_project_idx ON ingestion_jobs (project_id, created_at DESC);

CREATE TABLE analyses (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    commit_sha VARCHAR(40),
    pull_request_number INTEGER,
    risk_level VARCHAR(16),
    risk_score INTEGER,
    result_json TEXT,
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT analyses_risk_level_chk CHECK (
        risk_level IS NULL OR risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    )
);

CREATE INDEX analyses_project_idx ON analyses (project_id, created_at DESC);
