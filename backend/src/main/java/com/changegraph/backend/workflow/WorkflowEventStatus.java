package com.changegraph.backend.workflow;

public enum WorkflowEventStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    RETRYING
}
