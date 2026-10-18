package com.changegraph.backend.ingestion;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.PageResponse;
import com.changegraph.backend.github.GitHubClient;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.parser.JavaSourceParser;
import com.changegraph.backend.parser.SourcePaths;
import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.user.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final IngestionJobRepository jobs;
    private final IngestionJobService jobService;
    private final ProjectAccess access;
    private final GitHubClient github;
    private final GraphStore graphStore;
    private final IngestionGraphBuilder builder = new IngestionGraphBuilder();
    private final int maxFiles;
    private final int maxFileBytes;

    public IngestionService(
            IngestionJobRepository jobs,
            IngestionJobService jobService,
            ProjectAccess access,
            GitHubClient github,
            GraphStore graphStore,
            @Value("${changegraph.ingestion.max-files:40}") int maxFiles,
            @Value("${changegraph.ingestion.max-file-bytes:200000}") int maxFileBytes) {
        this.jobs = jobs;
        this.jobService = jobService;
        this.access = access;
        this.github = github;
        this.graphStore = graphStore;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileBytes;
    }

    @Transactional
    public IngestionJobResponse queue(UserAccount owner, UUID projectId, String commitSha) {
        Project project = access.requireProject(owner, projectId);
        ProjectGraph graph = access.requireGraph(project.getId());
        if (graph.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before ingestion.");
        }
        access.requireRepository(project.getId());
        if (jobs.existsByProjectIdAndStatusIn(project.getId(), List.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "INGESTION_IN_PROGRESS", "An ingestion is already running for this project.");
        }
        String sha = normalizeSha(commitSha);
        IngestionJob job = new IngestionJob(UUID.randomUUID(), project, sha, Instant.now());
        jobs.save(job);
        return IngestionJobResponse.from(job);
    }

    @Transactional
    public IngestionJobResponse queueForProject(UUID projectId, String commitSha) {
        ProjectGraph graph = access.requireGraph(projectId);
        if (graph.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before ingestion.");
        }
        access.requireRepository(projectId);
        if (jobs.existsByProjectIdAndStatusIn(projectId, List.of(JobStatus.QUEUED, JobStatus.RUNNING))) {
            throw new ApiException(HttpStatus.CONFLICT, "INGESTION_IN_PROGRESS", "An ingestion is already running for this project.");
        }
        String sha = normalizeSha(commitSha);
        IngestionJob job = new IngestionJob(UUID.randomUUID(), graph.getProject(), sha, Instant.now());
        jobs.save(job);
        return IngestionJobResponse.from(job);
    }

    @Transactional(readOnly = true)
    public PageResponse<IngestionJobResponse> list(UserAccount owner, UUID projectId, int page, int size) {
        Project project = access.requireProject(owner, projectId);
        int bounded = Math.max(1, Math.min(size, 50));
        int safePage = Math.max(0, page);
        var result = jobs.findByProjectId(project.getId(), PageRequest.of(safePage, bounded, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(result.getContent().stream().map(IngestionJobResponse::from).toList(), safePage, bounded, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public IngestionJobResponse get(UserAccount owner, UUID projectId, UUID jobId) {
        access.requireProject(owner, projectId);
        IngestionJob job = jobs.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ingestion job not found."));
        return IngestionJobResponse.from(job);
    }

    public void execute(UUID jobId) {
        JobContext context = jobService.loadQueued(jobId);
        if (context == null) {
            return;
        }
        jobService.mark(jobId, JobStatus.RUNNING, "Checking that the repository is still public", null, false);
        try {
            GitHubModels.Repository repository = github.getPublicRepository(context.owner(), context.name());
            if (repository.id() != context.githubRepositoryId()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "REPOSITORY_IDENTITY_CHANGED",
                        "The GitHub repository id no longer matches the linked repository. Link it again.");
            }
            jobService.touchRepository(context.projectId(), repository);
            String sha = context.requestedSha();
            if (sha == null) {
                jobService.mark(jobId, JobStatus.RUNNING, "Fetching the latest public commit", null, false);
                String branch = context.selectedBranch() == null || context.selectedBranch().isBlank()
                        ? repository.defaultBranch()
                        : context.selectedBranch();
                List<GitHubModels.CommitSummary> commits = github.listCommits(context.owner(), context.name(), branch, 1);
                if (commits.isEmpty() || commits.get(0).sha().isBlank()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "COMMIT_NOT_FOUND", "No public commits were found on the selected branch.");
                }
                sha = commits.get(0).sha();
                jobService.rememberSha(jobId, sha);
            }
            jobService.mark(jobId, JobStatus.RUNNING, "Fetching the public commit and changed files", null, false);
            GitHubModels.CommitDetail commit = github.getCommit(context.owner(), context.name(), sha);
            Map<String, String> contents = new LinkedHashMap<>();
            Map<String, String> parentContents = new LinkedHashMap<>();
            String parentSha = commit.parentShas().isEmpty() ? null : commit.parentShas().get(0);
            Set<String> directories = new LinkedHashSet<>();
            int fetched = 0;
            for (GitHubModels.CommitFile file : commit.files()) {
                String path = JavaSourceParser.normalizePath(file.filename());
                if (!JavaSourceParser.isSafePath(path)) {
                    continue;
                }
                int slash = path.lastIndexOf('/');
                directories.add(slash < 0 ? "" : path.substring(0, slash));
                if (!SourcePaths.isSupported(path) || fetched >= maxFiles) {
                    continue;
                }
                if (!"removed".equalsIgnoreCase(file.status())) {
                    String body = readBounded(context, path, sha);
                    if (body != null) {
                        contents.put(path, body);
                        fetched++;
                    }
                }
                String previous = file.previousFilename() == null ? path : JavaSourceParser.normalizePath(file.previousFilename());
                if (parentSha != null && ("modified".equalsIgnoreCase(file.status()) || "removed".equalsIgnoreCase(file.status()) || "renamed".equalsIgnoreCase(file.status()))) {
                    String parentBody = readBounded(context, previous, parentSha);
                    if (parentBody != null) {
                        parentContents.put(previous, parentBody);
                    }
                }
            }
            jobService.mark(jobId, JobStatus.RUNNING, "Parsing public source files", null, false);
            String commitSha = sha;
            fetched = fetchSources(context, commitSha, contents, fetched, directories.stream()
                    .flatMap(directory -> github.listSourceFiles(context.owner(), context.name(), directory, commitSha).stream())
                    .toList());
            if (fetched < maxFiles) {
                fetchSources(context, commitSha, contents, fetched, github.listRepositorySourceFiles(context.owner(), context.name(), commitSha));
            }
            IngestionGraphBuilder.Built built = builder.build(new IngestionGraphBuilder.Input(
                    repository, commit, contents, parentContents, maxFiles));
            jobService.mark(jobId, JobStatus.RUNNING, "Writing the project graph", null, false);
            graphStore.merge(context.projectId(), context.graphName(), built.graph());
            String message = built.limitations().isEmpty()
                    ? "Ingestion finished"
                    : "Ingestion finished with limitations";
            jobService.mark(jobId, JobStatus.SUCCEEDED, message, null, true);
        } catch (ApiException ex) {
            log.warn("Ingestion {} failed with {}", jobId, ex.getCode());
            jobService.mark(jobId, JobStatus.FAILED, safeMessage(ex.getMessage()), ex.getCode(), true);
        } catch (RuntimeException ex) {
            log.warn("Ingestion {} failed ({})", jobId, ex.getClass().getSimpleName());
            jobService.mark(jobId, JobStatus.FAILED, "Ingestion failed before the graph could be updated.", "INGESTION_FAILED", true);
        }
    }

    private int fetchSources(JobContext context, String sha, Map<String, String> contents, int fetched, List<String> paths) {
        for (String path : paths) {
            if (fetched >= maxFiles || contents.containsKey(path) || !JavaSourceParser.isSafePath(path) || !SourcePaths.isContextFile(path)) {
                continue;
            }
            String body = readBounded(context, path, sha);
            if (body != null) {
                contents.put(path, body);
                fetched++;
            }
        }
        return fetched;
    }

    private String readBounded(JobContext context, String path, String ref) {
        String body = github.getFile(context.owner(), context.name(), path, ref);
        if (body == null) {
            return null;
        }
        if (body.length() > maxFileBytes) {
            return null;
        }
        return body;
    }

    private static String normalizeSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return null;
        }
        String trimmed = sha.trim();
        if (!trimmed.matches("[0-9a-fA-F]{7,40}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_COMMIT", "Commit SHA must be 7 to 40 hexadecimal characters.");
        }
        return trimmed;
    }

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Ingestion failed.";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    public record JobContext(
            UUID projectId,
            String graphName,
            long githubRepositoryId,
            String owner,
            String name,
            String requestedSha,
            String selectedBranch) {
    }
}
