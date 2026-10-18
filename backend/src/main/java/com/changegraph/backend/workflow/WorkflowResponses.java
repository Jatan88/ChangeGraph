package com.changegraph.backend.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WorkflowResponses {

    private WorkflowResponses() {
    }

    public record WorkflowEventResponse(
            UUID id,
            int sequence,
            String agent,
            String phase,
            String status,
            String summary,
            String detail,
            Instant createdAt) {
    }

    public record WorkflowSummary(
            UUID id,
            String eventType,
            String status,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String title,
            String currentTask,
            String fixBranch,
            String fixPullRequestUrl,
            Integer fixPullRequestNumber,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt) {
    }

    public record WorkflowDetail(
            UUID id,
            String eventType,
            String status,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String defaultBranch,
            String selectedBranch,
            String title,
            String currentTask,
            String developerNote,
            String validationNote,
            String fixBranch,
            String fixPullRequestUrl,
            Integer fixPullRequestNumber,
            String errorMessage,
            int attempt,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            List<WorkflowEventResponse> events,
            CodeReview review) {
    }

    public record WorkflowStreamPayload(
            UUID runId,
            int sequence,
            String agent,
            String phase,
            String status,
            String summary,
            String detail,
            String runStatus,
            String currentTask,
            Instant createdAt) {
    }

    public record WorkflowStatusPayload(
            UUID runId,
            String status,
            String currentTask,
            String developerNote,
            String validationNote,
            String fixBranch,
            String fixPullRequestUrl,
            Integer fixPullRequestNumber,
            String errorMessage,
            Instant startedAt,
            Instant completedAt) {
    }

    public record WorkflowProjectPayload(
            String change,
            WorkflowSummary summary) {
    }

    public static WorkflowSummary summary(WorkflowRun run) {
        return new WorkflowSummary(
                run.getId(),
                run.getEventType().name(),
                run.getStatus().name(),
                run.getCommitSha(),
                run.getPullRequestNumber(),
                run.getSourceBranch(),
                run.getTargetBranch(),
                run.getTitle(),
                run.getCurrentTask(),
                run.getFixBranch(),
                run.getFixPullRequestUrl(),
                run.getFixPullRequestNumber(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    public static WorkflowStatusPayload status(WorkflowRun run) {
        return new WorkflowStatusPayload(
                run.getId(),
                run.getStatus().name(),
                run.getCurrentTask(),
                run.getDeveloperNote(),
                run.getValidationNote(),
                run.getFixBranch(),
                run.getFixPullRequestUrl(),
                run.getFixPullRequestNumber(),
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getCompletedAt());
    }

    public static WorkflowEventResponse event(WorkflowEvent event) {
        return new WorkflowEventResponse(
                event.getId(),
                event.getSequenceNumber(),
                event.getAgent().name(),
                event.getPhase().name(),
                event.getStatus().name(),
                event.getSummary(),
                event.getDetail(),
                event.getCreatedAt());
    }
}
