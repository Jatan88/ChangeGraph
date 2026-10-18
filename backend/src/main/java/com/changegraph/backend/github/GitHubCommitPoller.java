package com.changegraph.backend.github;

import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private final ObjectProvider<NewCommitListener> newCommits;
    private final RepositoryCommitCursor cursor;
    private final boolean pollOpenPullRequests;
    private final ObjectProvider<NewPullRequestListener> newPullRequests;
    private final ConcurrentHashMap<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    @Autowired
    public GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration githubPollInterval,
            ObjectProvider<NewCommitListener> newCommits,
            RepositoryCommitCursor cursor,
            @Qualifier("githubPollOpenPullRequests") boolean pollOpenPullRequests,
            ObjectProvider<NewPullRequestListener> newPullRequests) {
        this(links, github, githubPollInterval, Clock.systemUTC(), newCommits, cursor, pollOpenPullRequests, newPullRequests);
    }

    GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration pollInterval,
            Clock clock) {
        this(links, github, pollInterval, clock, null, null);
    }

    GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration pollInterval,
            Clock clock,
            ObjectProvider<NewCommitListener> newCommits) {
        this(links, github, pollInterval, clock, newCommits, null);
    }

    GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration pollInterval,
            Clock clock,
            ObjectProvider<NewCommitListener> newCommits,
            RepositoryCommitCursor cursor) {
        this(links, github, pollInterval, clock, newCommits, cursor, false, null);
    }

    GitHubCommitPoller(
            ProjectRepositoryLinkRepository links,
            GitHubClient github,
            Duration pollInterval,
            Clock clock,
            ObjectProvider<NewCommitListener> newCommits,
            RepositoryCommitCursor cursor,
            boolean pollOpenPullRequests,
            ObjectProvider<NewPullRequestListener> newPullRequests) {
        this.links = links;
        this.github = github;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.newCommits = newCommits;
        this.cursor = cursor;
        this.pollOpenPullRequests = pollOpenPullRequests;
        this.newPullRequests = newPullRequests;
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
            initialDelayString = "${changegraph.github.poll-initial-delay-ms:15000}")
    @Transactional(readOnly = true)
    public void pollDue() {
        for (ProjectRepositoryLink link : links.findAll()) {
            try {
                refresh(link, false);
            } catch (RuntimeException ex) {
                log.warn("GitHub commit poll failed for project {}", link.getProject().getId(), ex);
            }
        }
    }

    private void refresh(ProjectRepositoryLink link, boolean force) {
        UUID projectId = link.getProject().getId();
        synchronized (locks.computeIfAbsent(projectId, ignored -> new Object())) {
            Snapshot current = snapshots.get(projectId);
            Instant now = clock.instant();
            if (!force && current != null && current.polledAt().plus(pollInterval).isAfter(now)) {
                log.debug("Skipping GitHub poll for project {} until {}", projectId, current.polledAt().plus(pollInterval));
                return;
            }
            GitHubModels.Repository repository = github.getPublicRepository(link.getOwnerName(), link.getRepositoryName());
            String branch = link.getSelectedBranch();
            if (branch == null || branch.isBlank()) {
                branch = repository.defaultBranch();
            }
            List<GitHubModels.CommitSummary> commits = List.copyOf(github.listCommits(
                    link.getOwnerName(),
                    link.getRepositoryName(),
                    branch,
                    COMMIT_LIMIT));
            Snapshot previous = snapshots.get(projectId);
            List<GitHubModels.CommitSummary> fresh = freshCommits(link, previous, commits, branch, projectId);
            if (!notifyNewCommits(projectId, branch, fresh)) {
                log.warn("Left the commit cursor for project {} on {} unchanged so the next poll can retry", projectId, branch);
                return;
            }
            List<GitHubModels.PullRequest> pulls = openPullRequests(link);
            List<GitHubModels.PullRequest> freshPulls = previous == null
                    ? List.of()
                    : unseenPulls(previous.pullKeys(), pulls);
            if (!notifyNewPulls(projectId, freshPulls)) {
                log.warn("Left the pull request snapshot for project {} unchanged so the next poll can retry", projectId);
                return;
            }
            snapshots.put(projectId, new Snapshot(commits, now, pullKeys(pulls)));
            String tip = commits.isEmpty() ? "" : commits.getFirst().sha();
            if (cursor != null && tip != null && !tip.isBlank()) {
                try {
                    cursor.remember(projectId, tip);
                } catch (RuntimeException ex) {
                    log.warn("Could not store the last seen commit for project {}", projectId, ex);
                }
            }
            log.info("Polled {}/{} branch {} for project {}: {} commits, tip {}, {} new, {} open pull requests, {} new",
                    link.getOwnerName(),
                    link.getRepositoryName(),
                    branch,
                    projectId,
                    commits.size(),
                    shortSha(tip),
                    fresh.size(),
                    pulls.size(),
                    freshPulls.size());
        }
    }

    private List<GitHubModels.CommitSummary> freshCommits(
            ProjectRepositoryLink link,
            Snapshot previous,
            List<GitHubModels.CommitSummary> commits,
            String branch,
            UUID projectId) {
        String seen = link.getLastSeenCommitSha();
        if (seen != null && !seen.isBlank()) {
            return afterCursor(commits, seen, projectId);
        }
        if (previous != null) {
            return notInSnapshot(previous, commits);
        }
        Instant linkedAt = link.getCreatedAt();
        if (linkedAt != null) {
            List<GitHubModels.CommitSummary> fresh = committedAfter(commits, linkedAt);
            String newest = commits.isEmpty() ? "none" : commits.getFirst().committedAt();
            log.info("Project {} has no stored commit cursor. {} commit(s) on {} are newer than the link time {}. Newest commit time is {}.",
                    projectId, fresh.size(), branch, linkedAt, newest == null || newest.isBlank() ? "unknown" : newest);
            return fresh;
        }
        log.info("Recorded the current commits on {} for project {} as the baseline. History already on the branch was not queued.",
                branch, projectId);
        return List.of();
    }

    private List<GitHubModels.CommitSummary> afterCursor(
            List<GitHubModels.CommitSummary> commits,
            String seen,
            UUID projectId) {
        List<GitHubModels.CommitSummary> fresh = new ArrayList<>();
        for (GitHubModels.CommitSummary commit : commits) {
            if (seen.equals(commit.sha())) {
                return fresh;
            }
            if (commit.sha() != null && !commit.sha().isBlank()) {
                fresh.add(commit);
            }
            if (fresh.size() == 3) {
                log.info("Stopped after 3 new commits for project {}. Older commits on this page wait for a later poll.", projectId);
                return fresh;
            }
        }
        if (!fresh.isEmpty()) {
            log.info("Stored commit {} for project {} was not in the latest {} commits. Queuing the newest ones.",
                    shortSha(seen), projectId, commits.size());
        }
        return fresh;
    }

    private static List<GitHubModels.CommitSummary> notInSnapshot(
            Snapshot previous,
            List<GitHubModels.CommitSummary> commits) {
        Set<String> seen = new HashSet<>();
        for (GitHubModels.CommitSummary commit : previous.commits()) {
            seen.add(commit.sha());
        }
        List<GitHubModels.CommitSummary> fresh = new ArrayList<>();
        for (GitHubModels.CommitSummary commit : commits) {
            if (commit.sha() != null && !commit.sha().isBlank() && seen.add(commit.sha())) {
                fresh.add(commit);
            }
            if (fresh.size() == 3) {
                break;
            }
        }
        return fresh;
    }

    private static List<GitHubModels.CommitSummary> committedAfter(
            List<GitHubModels.CommitSummary> commits,
            Instant linkedAt) {
        List<GitHubModels.CommitSummary> fresh = new ArrayList<>();
        for (GitHubModels.CommitSummary commit : commits) {
            if (commit.sha() == null || commit.sha().isBlank()) {
                continue;
            }
            Instant committed = parseInstant(commit.committedAt());
            if (committed == null) {
                continue;
            }
            if (!committed.isAfter(linkedAt)) {
                break;
            }
            fresh.add(commit);
            if (fresh.size() == 3) {
                break;
            }
        }
        return fresh;
    }

    private List<GitHubModels.PullRequest> openPullRequests(ProjectRepositoryLink link) {
        if (!pollOpenPullRequests) {
            return List.of();
        }
        return List.copyOf(github.listPullRequests(link.getOwnerName(), link.getRepositoryName(), COMMIT_LIMIT));
    }

    private static Set<String> pullKeys(List<GitHubModels.PullRequest> pulls) {
        Set<String> keys = new HashSet<>();
        for (GitHubModels.PullRequest pull : pulls) {
            String key = pullKey(pull);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static List<GitHubModels.PullRequest> unseenPulls(Set<String> seen, List<GitHubModels.PullRequest> pulls) {
        List<GitHubModels.PullRequest> fresh = new ArrayList<>();
        Set<String> known = seen == null ? Set.of() : seen;
        for (GitHubModels.PullRequest pull : pulls) {
            String key = pullKey(pull);
            if (key != null && !known.contains(key)) {
                fresh.add(pull);
            }
        }
        return fresh;
    }

    private static String pullKey(GitHubModels.PullRequest pull) {
        if (pull == null || pull.number() <= 0 || pull.headSha() == null || !pull.headSha().matches("[0-9a-fA-F]{7,40}")) {
            return null;
        }
        return pull.number() + ":" + pull.headSha();
    }

    private boolean notifyNewPulls(UUID projectId, List<GitHubModels.PullRequest> fresh) {
        if (fresh.isEmpty()) {
            return true;
        }
        if (newPullRequests == null) {
            log.warn("Open pull requests on project {} were not queued because no review listener is available", projectId);
            return false;
        }
        NewPullRequestListener listener = newPullRequests.getIfAvailable();
        if (listener == null) {
            log.warn("Open pull requests on project {} were not queued because no review listener is available", projectId);
            return false;
        }
        try {
            listener.onOpenPullRequests(projectId, List.copyOf(fresh));
            log.info("Queued {} open pull request(s) for project {}", fresh.size(), projectId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("Pull request notification failed for project {}", projectId, ex);
            return false;
        }
    }

    private boolean notifyNewCommits(UUID projectId, String branch, List<GitHubModels.CommitSummary> fresh) {
        if (fresh.isEmpty()) {
            return true;
        }
        if (newCommits == null) {
            log.warn("New commits on project {} were not queued because no review listener is available", projectId);
            return false;
        }
        NewCommitListener listener = newCommits.getIfAvailable();
        if (listener == null) {
            log.warn("New commits on project {} were not queued because no review listener is available", projectId);
            return false;
        }
        try {
            listener.onNewCommits(projectId, branch, List.copyOf(fresh));
            log.info("Queued {} new commit(s) on {} for project {}: {}",
                    fresh.size(),
                    branch,
                    projectId,
                    fresh.stream().map(commit -> shortSha(commit.sha())).toList());
            return true;
        } catch (RuntimeException ex) {
            log.warn("New commit notification failed for project {}", projectId, ex);
            return false;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) {
            return "none";
        }
        return sha.length() <= 7 ? sha : sha.substring(0, 7);
    }

    private CommitPollResponse response(Snapshot snapshot) {
        return new CommitPollResponse(snapshot.commits(), snapshot.polledAt(), pollInterval.toSeconds());
    }

    private record Snapshot(List<GitHubModels.CommitSummary> commits, Instant polledAt, Set<String> pullKeys) {
    }
}
