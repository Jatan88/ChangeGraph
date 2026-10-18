package com.changegraph.backend.ingestion;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class IngestionJobService {

    private final IngestionJobRepository jobs;
    private final ProjectAccess access;
    private final ProjectRepositoryLinkRepository repositories;

    public IngestionJobService(IngestionJobRepository jobs, ProjectAccess access, ProjectRepositoryLinkRepository repositories) {
        this.jobs = jobs;
        this.access = access;
        this.repositories = repositories;
    }

    @Transactional(readOnly = true)
    public IngestionService.JobContext loadQueued(UUID jobId) {
        IngestionJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != JobStatus.QUEUED) {
            return null;
        }
        Project project = job.getProject();
        ProjectGraph graph = access.requireGraph(project.getId());
        if (graph.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before ingestion.");
        }
        ProjectRepositoryLink link = access.requireRepository(project.getId());
        return new IngestionService.JobContext(
                project.getId(),
                graph.getGraphName(),
                link.getGithubRepositoryId(),
                link.getOwnerName(),
                link.getRepositoryName(),
                job.getRequestedCommitSha(),
                link.branchForSync());
    }

    @Transactional
    public void mark(UUID jobId, JobStatus status, String message, String errorCode, boolean complete) {
        IngestionJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(status);
        job.setProgressMessage(message == null ? null : message.substring(0, Math.min(message.length(), 300)));
        job.setErrorCode(errorCode);
        if (status == JobStatus.RUNNING && job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        if (complete) {
            job.setCompletedAt(Instant.now());
        }
    }

    @Transactional(readOnly = true)
    public IngestionJobResponse view(UUID jobId) {
        return jobs.findById(jobId).map(IngestionJobResponse::from).orElse(null);
    }

    @Transactional
    public void rememberSha(UUID jobId, String sha) {
        jobs.findById(jobId).ifPresent(job -> job.setRequestedCommitSha(sha));
    }

    @Transactional
    public void touchRepository(UUID projectId, GitHubModels.Repository repository) {
        repositories.findByProjectId(projectId).ifPresent(link -> {
            link.setDefaultBranch(repository.defaultBranch());
            link.setLastSyncedAt(Instant.now());
        });
    }
}
