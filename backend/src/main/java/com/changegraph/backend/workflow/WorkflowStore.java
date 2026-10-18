package com.changegraph.backend.workflow;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.PageResponse;
import com.changegraph.backend.common.Text;
import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.project.ProjectGraphRepository;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowStore {

    private final WorkflowRunRepository runs;
    private final WorkflowEventRepository events;
    private final ProjectGraphRepository graphs;
    private final ProjectRepositoryLinkRepository links;
    private final WorkflowEventPublisher publisher;
    private final JsonMapper jsonMapper;
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    public WorkflowStore(
            WorkflowRunRepository runs,
            WorkflowEventRepository events,
            ProjectGraphRepository graphs,
            ProjectRepositoryLinkRepository links,
            WorkflowEventPublisher publisher,
            JsonMapper jsonMapper) {
        this.runs = runs;
        this.events = events;
        this.graphs = graphs;
        this.links = links;
        this.publisher = publisher;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcceptResult accept(WorkflowRun draft) {
        UUID projectId = draft.getProject().getId();
        if (draft.getDeliveryId() != null) {
            Optional<WorkflowRun> delivered = runs.findByProjectIdAndDeliveryId(projectId, draft.getDeliveryId());
            if (delivered.isPresent()) {
                return new AcceptResult(delivered.get(), false);
            }
        }
        Optional<WorkflowRun> existing = runs.findByProjectIdAndIdempotencyKey(projectId, draft.getIdempotencyKey());
        if (existing.isPresent()) {
            return new AcceptResult(existing.get(), false);
        }
        try {
            WorkflowRun saved = runs.saveAndFlush(draft);
            publisher.publishProject(projectId, "CREATED", saved);
            return new AcceptResult(saved, true);
        } catch (DataIntegrityViolationException ex) {
            return runs.findByProjectIdAndIdempotencyKey(projectId, draft.getIdempotencyKey())
                    .map(run -> new AcceptResult(run, false))
                    .orElseThrow(() -> ex);
        }
    }

    @Transactional
    public RunSnapshot claim(UUID runId) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != WorkflowStatus.QUEUED) {
            return null;
        }
        Project project = run.getProject();
        ProjectGraph graph = graphs.findByProjectId(project.getId()).orElse(null);
        ProjectRepositoryLink link = links.findByProjectId(project.getId()).orElse(null);
        run.setStatus(WorkflowStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setCompletedAt(null);
        run.setErrorMessage(null);
        notifyRun(run, "UPDATED");
        return new RunSnapshot(
                run.getId(),
                project.getId(),
                run.getEventType(),
                run.getCommitSha(),
                run.getPullRequestNumber(),
                run.getSourceBranch(),
                run.getTargetBranch(),
                run.getTitle(),
                run.isProposeFixes(),
                run.getAttempt(),
                graph == null ? null : graph.getGraphName(),
                graph != null && graph.getStatus() == com.changegraph.backend.project.GraphLifecycle.READY,
                link == null ? null : link.getOwnerName(),
                link == null ? null : link.getRepositoryName(),
                link == null ? 0 : link.getGithubRepositoryId(),
                link == null ? null : link.getDefaultBranch(),
                link == null ? null : link.branchForSync());
    }

    @Transactional
    public void event(
            UUID runId,
            WorkflowAgent agent,
            WorkflowPhase phase,
            WorkflowEventStatus status,
            String summary,
            String detail) {
        synchronized (lock(runId)) {
            WorkflowRun run = runs.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            String safeSummary = Text.clamp(summary, 300);
            String safeDetail = detail == null || detail.isBlank() ? null : Text.clamp(detail, 1000);
            int sequence = events.maxSequence(runId) + 1;
            WorkflowEvent event = new WorkflowEvent(
                    UUID.randomUUID(),
                    runId,
                    run.getProject().getId(),
                    sequence,
                    agent,
                    phase,
                    status,
                    safeSummary.isBlank() ? "Update" : safeSummary,
                    safeDetail,
                    Instant.now());
            events.save(event);
            run.setCurrentTask(event.getSummary());
            publisher.publish(payload(run, event));
        }
    }

    @Transactional
    public void saveReview(UUID runId, CodeReview review) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        try {
            String json = jsonMapper.writeValueAsString(review);
            run.setReviewJson(json.length() <= 20000 ? json : json.substring(0, 20000));
        } catch (RuntimeException ex) {
            run.setReviewJson(null);
        }
    }

    @Transactional
    public void finish(
            UUID runId,
            WorkflowStatus status,
            String developerNote,
            String validationNote,
            String fixBranch,
            String pullRequestUrl,
            Integer pullRequestNumber) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        run.setStatus(status);
        run.setDeveloperNote(note(developerNote, 1000));
        run.setValidationNote(note(validationNote, 500));
        run.setFixBranch(fixBranch);
        run.setFixPullRequestUrl(pullRequestUrl);
        run.setFixPullRequestNumber(pullRequestNumber);
        run.setErrorMessage(null);
        run.setCompletedAt(Instant.now());
        run.setCurrentTask(status == WorkflowStatus.AWAITING_HUMAN_REVIEW ? "Waiting for human review" : "Completed");
        notifyRun(run, "UPDATED");
    }

    @Transactional
    public void fail(UUID runId, String message, String code) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() == WorkflowStatus.COMPLETED || run.getStatus() == WorkflowStatus.AWAITING_HUMAN_REVIEW) {
            return;
        }
        run.setStatus(WorkflowStatus.FAILED);
        String text = message == null || message.isBlank() ? "The workflow failed." : message;
        if (code != null && !code.isBlank()) {
            text = text + " (" + code + ")";
        }
        run.setErrorMessage(Text.clamp(text, 500));
        run.setCurrentTask("Failed");
        run.setCompletedAt(Instant.now());
        notifyRun(run, "UPDATED");
    }

    @Transactional
    public UUID retry(UUID projectId, UUID runId) {
        WorkflowRun run = runs.findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workflow not found."));
        boolean failed = run.getStatus() == WorkflowStatus.FAILED;
        boolean missedPullRequest = run.getStatus() == WorkflowStatus.COMPLETED
                && run.isProposeFixes()
                && run.getFixPullRequestNumber() == null
                && run.getDeveloperNote() != null
                && run.getDeveloperNote().contains("No pull request was opened");
        if (!failed && !missedPullRequest) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKFLOW_NOT_FAILED", "Only a failed workflow, or a finished review that did not open a pull request, can be run again.");
        }
        run.setStatus(WorkflowStatus.QUEUED);
        run.setAttempt(run.getAttempt() + 1);
        run.setErrorMessage(null);
        run.setCompletedAt(null);
        run.setCurrentTask("Retry queued");
        notifyRun(run, "UPDATED");
        event(runId, WorkflowAgent.SYSTEM, WorkflowPhase.DETECTED, WorkflowEventStatus.RETRYING, "Retry requested", null);
        return run.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkflowResponses.WorkflowSummary> list(UUID projectId, int page, int size) {
        int bounded = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        var result = runs.findByProjectId(projectId, PageRequest.of(safePage, bounded, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(
                result.getContent().stream().map(WorkflowResponses::summary).toList(),
                safePage,
                bounded,
                result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public WorkflowResponses.WorkflowDetail detail(UUID projectId, UUID runId) {
        WorkflowRun run = require(projectId, runId);
        ProjectRepositoryLink link = links.findByProjectId(projectId).orElse(null);
        return new WorkflowResponses.WorkflowDetail(
                run.getId(),
                run.getEventType().name(),
                run.getStatus().name(),
                run.getCommitSha(),
                run.getPullRequestNumber(),
                run.getSourceBranch(),
                run.getTargetBranch(),
                link == null ? null : link.getDefaultBranch(),
                link == null ? null : link.branchForSync(),
                run.getTitle(),
                run.getCurrentTask(),
                run.getDeveloperNote(),
                run.getValidationNote(),
                run.getFixBranch(),
                run.getFixPullRequestUrl(),
                run.getFixPullRequestNumber(),
                run.getErrorMessage(),
                run.getAttempt(),
                run.getCreatedAt(),
                run.getStartedAt(),
                run.getCompletedAt(),
                events.findByRunIdOrderBySequenceNumberAsc(runId).stream().map(WorkflowResponses::event).toList(),
                readReview(run.getReviewJson()));
    }

    public SseEmitter stream(UUID projectId, UUID runId, int after) {
        synchronized (lock(runId)) {
            WorkflowRun run = require(projectId, runId);
            List<WorkflowResponses.WorkflowStreamPayload> replay = events.findByRunIdOrderBySequenceNumberAsc(runId).stream()
                    .filter(event -> event.getSequenceNumber() > after)
                    .map(event -> payload(run, event))
                    .toList();
            return publisher.subscribeRun(runId, replay);
        }
    }

    public SseEmitter projectStream(UUID projectId) {
        return publisher.subscribeProject(projectId);
    }

    private void notifyRun(WorkflowRun run, String change) {
        publisher.publishStatus(run);
        publisher.publishProject(run.getProject().getId(), change, run);
    }

    private WorkflowRun require(UUID projectId, UUID runId) {
        return runs.findByIdAndProjectId(runId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Workflow not found."));
    }

    private CodeReview readReview(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(json, CodeReview.class);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private WorkflowResponses.WorkflowStreamPayload payload(WorkflowRun run, WorkflowEvent event) {
        return new WorkflowResponses.WorkflowStreamPayload(
                run.getId(),
                event.getSequenceNumber(),
                event.getAgent().name(),
                event.getPhase().name(),
                event.getStatus().name(),
                event.getSummary(),
                event.getDetail(),
                run.getStatus().name(),
                run.getCurrentTask(),
                event.getCreatedAt());
    }

    private Object lock(UUID runId) {
        return locks.computeIfAbsent(runId, ignored -> new Object());
    }

    private static String note(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Text.clamp(value, max);
    }

    public record AcceptResult(WorkflowRun run, boolean created) {
    }

    public record RunSnapshot(
            UUID id,
            UUID projectId,
            WorkflowKind kind,
            String commitSha,
            Integer pullRequestNumber,
            String sourceBranch,
            String targetBranch,
            String title,
            boolean proposeFixes,
            int attempt,
            String graphName,
            boolean graphReady,
            String owner,
            String repository,
            long githubRepositoryId,
            String defaultBranch,
            String selectedBranch) {
    }
}
