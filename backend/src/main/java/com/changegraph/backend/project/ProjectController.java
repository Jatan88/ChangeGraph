package com.changegraph.backend.project;

import com.changegraph.backend.auth.CurrentUser;
import com.changegraph.backend.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;
    private final CurrentUser currentUser;

    public ProjectController(ProjectService projects, CurrentUser currentUser) {
        this.projects = projects;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<ProjectResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return projects.list(currentUser.require(), page, size);
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(currentUser.require(), request));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId) {
        return projects.get(currentUser.require(), projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectResponse update(@PathVariable UUID projectId, @Valid @RequestBody UpdateProjectRequest request) {
        return projects.update(currentUser.require(), projectId, request);
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean confirm) {
        projects.delete(currentUser.require(), projectId, confirm);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{projectId}/graph")
    public ProjectResponse retryGraph(@PathVariable UUID projectId) {
        return projects.retryGraph(currentUser.require(), projectId);
    }
}
