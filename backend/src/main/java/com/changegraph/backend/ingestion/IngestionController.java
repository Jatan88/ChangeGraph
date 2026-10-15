package com.changegraph.backend.ingestion;

import com.changegraph.backend.auth.CurrentUser;
import com.changegraph.backend.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class IngestionController {

    private final IngestionService ingestionService;
    private final IngestionRunner runner;
    private final SyntheticIncidentService syntheticIncidents;
    private final CurrentUser currentUser;

    public IngestionController(
            IngestionService ingestionService,
            IngestionRunner runner,
            SyntheticIncidentService syntheticIncidents,
            CurrentUser currentUser) {
        this.ingestionService = ingestionService;
        this.runner = runner;
        this.syntheticIncidents = syntheticIncidents;
        this.currentUser = currentUser;
    }

    @PostMapping("/ingestions")
    public ResponseEntity<IngestionJobResponse> start(
            @PathVariable UUID projectId,
            @RequestBody(required = false) IngestionRequest request) {
        String sha = request == null ? null : request.commitSha();
        IngestionJobResponse job = ingestionService.queue(currentUser.require(), projectId, sha);
        runner.run(job.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/ingestions")
    public PageResponse<IngestionJobResponse> list(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ingestionService.list(currentUser.require(), projectId, page, size);
    }

    @GetMapping("/ingestions/{jobId}")
    public IngestionJobResponse get(@PathVariable UUID projectId, @PathVariable UUID jobId) {
        return ingestionService.get(currentUser.require(), projectId, jobId);
    }

    @PostMapping("/synthetic-incidents")
    public SyntheticIncidentService.SyntheticSeedResponse seed(@PathVariable UUID projectId) {
        return syntheticIncidents.seed(currentUser.require(), projectId);
    }

    public record IngestionRequest(String commitSha) {
    }
}
