package com.changegraph.backend.workflow;

public enum WorkflowStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    AWAITING_HUMAN_REVIEW
}
