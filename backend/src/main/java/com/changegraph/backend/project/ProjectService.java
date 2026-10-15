package com.changegraph.backend.project;

import com.changegraph.backend.analysis.AnalysisRecord;
import com.changegraph.backend.analysis.AnalysisRepository;
import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.PageResponse;
import com.changegraph.backend.common.Text;
import com.changegraph.backend.graph.GraphNames;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.ingestion.IngestionJob;
import com.changegraph.backend.ingestion.IngestionJobRepository;
import com.changegraph.backend.user.UserAccount;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final ProjectGraphRepository graphs;
    private final ProjectRepositoryLinkRepository repositories;
    private final IngestionJobRepository ingestions;
    private final AnalysisRepository analyses;
    private final GraphStore graphStore;
    private final ProjectAccess access;
    private final ProjectCleanup cleanup;

    public ProjectService(
            ProjectRepository projects,
            ProjectGraphRepository graphs,
            ProjectRepositoryLinkRepository repositories,
            IngestionJobRepository ingestions,
            AnalysisRepository analyses,
            GraphStore graphStore,
            ProjectAccess access,
            ProjectCleanup cleanup) {
        this.projects = projects;
        this.graphs = graphs;
        this.repositories = repositories;
        this.ingestions = ingestions;
        this.analyses = analyses;
        this.graphStore = graphStore;
        this.access = access;
        this.cleanup = cleanup;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> list(UserAccount owner, int page, int size) {
        int bounded = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        var result = projects.findByOwnerId(owner.getId(), PageRequest.of(safePage, bounded, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                safePage,
                bounded,
                result.getTotalElements());
    }

    @Transactional
    public ProjectResponse create(UserAccount owner, CreateProjectRequest request) {
        Instant now = Instant.now();
        Project project = new Project(
                UUID.randomUUID(),
                owner,
                request.name().trim(),
                Text.blankToNull(request.description()),
                now);
        projects.save(project);
        String graphName = GraphNames.forProject(project.getId());
        ProjectGraph mapping = graphs.save(new ProjectGraph(UUID.randomUUID(), project, graphName, now));
        try {
            graphStore.initialize(graphName);
            mapping.setStatus(GraphLifecycle.READY);
            mapping.setLastErrorCode(null);
        } catch (RuntimeException ex) {
            mapping.setStatus(GraphLifecycle.FAILED);
            mapping.setLastErrorCode("GRAPH_INIT_FAILED");
        }
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UserAccount owner, UUID projectId) {
        return toResponse(access.requireProject(owner, projectId));
    }

    @Transactional
    public ProjectResponse update(UserAccount owner, UUID projectId, UpdateProjectRequest request) {
        Project project = access.requireProject(owner, projectId);
        if (request.name() != null) {
            project.setName(request.name().trim());
        }
        if (request.description() != null) {
            project.setDescription(Text.blankToNull(request.description()));
        }
        project.setUpdatedAt(Instant.now());
        return toResponse(project);
    }

    public void delete(UserAccount owner, UUID projectId, boolean confirm) {
        if (!confirm) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "CONFIRMATION_REQUIRED",
                    "Delete the project only after confirming. This also deletes that project's FalkorDB graph.");
        }
        Project project = access.requireProject(owner, projectId);
        graphs.findByProjectId(project.getId()).ifPresent(mapping ->
                graphStore.deleteOwnedGraph(project.getId(), mapping.getGraphName()));
        cleanup.deleteRows(project.getId());
    }

    @Transactional
    public ProjectResponse retryGraph(UserAccount owner, UUID projectId) {
        Project project = access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(project.getId());
        if (!GraphNames.matchesProject(project.getId(), mapping.getGraphName())) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_DELETE_REFUSED", "The stored graph name does not match this project.");
        }
        try {
            graphStore.initialize(mapping.getGraphName());
            mapping.setStatus(GraphLifecycle.READY);
            mapping.setLastErrorCode(null);
        } catch (RuntimeException ex) {
            mapping.setStatus(GraphLifecycle.FAILED);
            mapping.setLastErrorCode("GRAPH_INIT_FAILED");
        }
        return toResponse(project);
    }

    private ProjectResponse toResponse(Project project) {
        ProjectGraph graph = graphs.findByProjectId(project.getId()).orElse(null);
        ProjectRepositoryLink link = repositories.findByProjectId(project.getId()).orElse(null);
        IngestionJob ingestion = ingestions.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null);
        AnalysisRecord analysis = analyses.findFirstByProjectIdOrderByCreatedAtDesc(project.getId()).orElse(null);
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                link != null,
                link == null ? null : new ProjectResponse.RepositorySummary(
                        link.getGithubRepositoryId(),
                        link.getOwnerName(),
                        link.getRepositoryName(),
                        link.getDefaultBranch(),
                        link.getRepositoryUrl(),
                        "public",
                        link.getLastSyncedAt()),
                graph == null ? GraphLifecycle.FAILED.name() : graph.getStatus().name(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                ingestion == null ? null : new ProjectResponse.ActivitySummary(
                        ingestion.getId(),
                        ingestion.getStatus().name(),
                        ingestion.getProgressMessage(),
                        ingestion.getCreatedAt()),
                analysis == null ? null : new ProjectResponse.ActivitySummary(
                        analysis.getId(),
                        analysis.getRiskLevel() == null ? "UNKNOWN" : analysis.getRiskLevel(),
                        analysis.getCommitSha(),
                        analysis.getCreatedAt()));
    }
}
