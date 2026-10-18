package com.changegraph.backend.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "workflow_events",
        uniqueConstraints = @UniqueConstraint(name = "workflow_events_sequence_uk", columnNames = {"run_id", "sequence_number"}))
public class WorkflowEvent {

    @Id
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowAgent agent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowPhase phase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowEventStatus status;

    @Column(nullable = false, length = 300)
    private String summary;

    @Column(length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WorkflowEvent() {
    }

    public WorkflowEvent(
            UUID id,
            UUID runId,
            UUID projectId,
            int sequenceNumber,
            WorkflowAgent agent,
            WorkflowPhase phase,
            WorkflowEventStatus status,
            String summary,
            String detail,
            Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.projectId = projectId;
        this.sequenceNumber = sequenceNumber;
        this.agent = agent;
        this.phase = phase;
        this.status = status;
        this.summary = summary;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public WorkflowAgent getAgent() {
        return agent;
    }

    public WorkflowPhase getPhase() {
        return phase;
    }

    public WorkflowEventStatus getStatus() {
        return status;
    }

    public String getSummary() {
        return summary;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
