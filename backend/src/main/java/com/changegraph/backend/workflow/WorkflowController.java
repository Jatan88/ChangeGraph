package com.changegraph.backend.workflow;

import com.changegraph.backend.auth.CurrentUser;
import com.changegraph.backend.common.PageResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/workflows")
public class WorkflowController {

    private final WorkflowService workflows;
    private final CurrentUser currentUser;

    public WorkflowController(WorkflowService workflows, CurrentUser currentUser) {
        this.workflows = workflows;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<WorkflowResponses.WorkflowSummary> list(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return workflows.list(currentUser.require(), projectId, page, size);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter projectStream(@PathVariable UUID projectId) {
        return workflows.projectStream(currentUser.require(), projectId);
    }

    @GetMapping("/{runId}")
    public WorkflowResponses.WorkflowDetail detail(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return workflows.detail(currentUser.require(), projectId, runId);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int after) {
        return workflows.stream(currentUser.require(), projectId, runId, after);
    }

    @PostMapping("/{runId}/retry")
    public Map<String, UUID> retry(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return Map.of("id", workflows.retry(currentUser.require(), projectId, runId));
    }
}
