package com.changegraph.backend.github;

import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GitHubCommitPoller {

    private static final Logger log = LoggerFactory.getLogger(GitHubCommitPoller.class);
    private static final int COMMIT_LIMIT = 20;

    private final ProjectRepositoryLinkRepository links;
    private final GitHubClient github;
    private final Duration pollInterval;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration githubPollInterval) {
        this(links, github, githubPollInterval, Clock.systemUTC());
    }

    GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration pollInterval,
            Clock clock) {
        this.links = links;
        this.github = github;
        this.pollInterval = pollInterval;
        this.clock = clock;
    }

    public static Duration interval(long minutes) {
        long bounded = Math.max(1, Math.min(minutes, 24 * 60));
        return Duration.ofMinutes(bounded);
    }

    public CommitPollResponse status(ProjectRepositoryLink link) {
        Snapshot snapshot = snapshots.get(link.getProject().getId());
        if (snapshot == null) {
            return new CommitPollResponse(List.of(), null, pollInterval.toSeconds());
        }
        return response(snapshot);
    }

    public List<GitHubModels.CommitSummary> cachedCommits(ProjectRepositoryLink link) {
        return status(link).commits();
    }

    public CommitPollResponse pollNow(ProjectRepositoryLink link) {
        refresh(link, true);
        return status(link);
    }

    public void evict(UUID projectId) {
        snapshots.remove(projectId);
        locks.remove(projectId);
    }

    @Scheduled(
            fixedDelayString = "#{@githubPollInterval.toMillis()}",
            initialDelayString = "#{@githubPollInterval.toMillis()}")
    @Transactional(readOnly = true)
    public void pollDue() {
        for (ProjectRepositoryLink link : links.findAll()) {
            try {
                refresh(link, false);
            } catch (RuntimeException ex) {
                log.warn("GitHub commit poll failed for project {}: {}",
                        link.getProject().getId(), ex.getClass().getSimpleName());
            }
        }
    }

    private void refresh(ProjectRepositoryLink link, boolean force) {
        UUID projectId = link.getProject().getId();
        synchronized (locks.computeIfAbsent(projectId, ignored -> new Object())) {
            Snapshot current = snapshots.get(projectId);
            Instant now = clock.instant();
            if (!force && current != null && current.polledAt().plus(pollInterval).isAfter(now)) {
                return;
            }
            GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
            List<GitHubModels.CommitSummary> commits = List.copyOf(github.listCommits(
                    link.getOwnerName(),
                    link.getRepositoryName(),
                    repository.defaultBranch(),
                    COMMIT_LIMIT));
            snapshots.put(projectId, new Snapshot(commits, now));
        }
    }

    private CommitPollResponse response(Snapshot snapshot) {
        return new CommitPollResponse(snapshot.commits(), snapshot.polledAt(), pollInterval.toSeconds());
    }

    private record Snapshot(List<GitHubModels.CommitSummary> commits, Instant polledAt) {
    }
}
