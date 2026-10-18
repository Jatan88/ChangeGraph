package com.changegraph.backend.workflow;

import com.changegraph.backend.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "workflow_runs",
        uniqueConstraints = @UniqueConstraint(name = "workflow_runs_idempotency_uk", columnNames = {"project_id", "idempotency_key"}))
public class WorkflowRun {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "delivery_id", length = 80)
    private String deliveryId;

    @Column(name = "idempotency_key", nullable = false, length = 180)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private WorkflowKind eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowStatus status;

    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "pull_request_number")
    private Integer pullRequestNumber;

    @Column(name = "source_branch", length = 255)
    private String sourceBranch;

    @Column(name = "target_branch", length = 255)
    private String targetBranch;

    @Column(length = 300)
    private String title;

    @Column(name = "current_task", length = 300)
    private String currentTask;

    @Column(name = "review_json", length = 20000)
    private String reviewJson;

    @Column(name = "developer_note", length = 1000)
    private String developerNote;

    @Column(name = "validation_note", length = 500)
    private String validationNote;

    @Column(name = "fix_branch", length = 255)
    private String fixBranch;

    @Column(name = "fix_pull_request_url", length = 300)
    private String fixPullRequestUrl;

    @Column(name = "fix_pull_request_number")
    private Integer fixPullRequestNumber;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "propose_fixes", nullable = false)
    private boolean proposeFixes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected WorkflowRun() {
    }

    public WorkflowRun(
            UUID id,
            Project project,
            String deliveryId,
            String idempotencyKey,
            WorkflowKind eventType,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String title,
            boolean proposeFixes,
            Instant createdAt) {
        this.id = id;
        this.project = project;
        this.deliveryId = deliveryId;
        this.idempotencyKey = idempotencyKey;
        this.eventType = eventType;
        this.status = WorkflowStatus.QUEUED;
        this.commitSha = commitSha;
        this.pullRequestNumber = pullRequestNumber;
        this.sourceBranch = sourceBranch;
        this.targetBranch = targetBranch;
        this.title = title;
        this.currentTask = "Queued";
        this.attempt = 1;
        this.proposeFixes = proposeFixes;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public WorkflowKind getEventType() {
        return eventType;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowStatus status) {
        this.status = status;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public Integer getPullRequestNumber() {
        return pullRequestNumber;
    }

    public String getSourceBranch() {
        return sourceBranch;
    }

    public String getTargetBranch() {
        return targetBranch;
    }

    public String getTitle() {
        return title;
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public void setCurrentTask(String currentTask) {
        this.currentTask = currentTask;
    }

    public String getReviewJson() {
        return reviewJson;
    }

    public void setReviewJson(String reviewJson) {
        this.reviewJson = reviewJson;
    }

    public String getDeveloperNote() {
        return developerNote;
    }

    public void setDeveloperNote(String developerNote) {
        this.developerNote = developerNote;
    }

    public String getValidationNote() {
        return validationNote;
    }

    public void setValidationNote(String validationNote) {
        this.validationNote = validationNote;
    }

    public String getFixBranch() {
        return fixBranch;
    }

    public void setFixBranch(String fixBranch) {
        this.fixBranch = fixBranch;
    }

    public String getFixPullRequestUrl() {
        return fixPullRequestUrl;
    }

    public void setFixPullRequestUrl(String fixPullRequestUrl) {
        this.fixPullRequestUrl = fixPullRequestUrl;
    }

    public Integer getFixPullRequestNumber() {
        return fixPullRequestNumber;
    }

    public void setFixPullRequestNumber(Integer fixPullRequestNumber) {
        this.fixPullRequestNumber = fixPullRequestNumber;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttempt() {
        return attempt;
    }

    public void setAttempt(int attempt) {
        this.attempt = attempt;
    }

    public boolean isProposeFixes() {
        return proposeFixes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
