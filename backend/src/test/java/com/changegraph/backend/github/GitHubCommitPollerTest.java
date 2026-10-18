package com.changegraph.backend.github;

import com.changegraph.backend.project.Project;
import com.changegraph.backend.project.ProjectRepositoryLink;
import com.changegraph.backend.project.ProjectRepositoryLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubCommitPollerTest {

    private final ProjectRepositoryLinkRepository links = mock(ProjectRepositoryLinkRepository.class);
    private final GitHubClient github = mock(GitHubClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
    private final UUID projectId = UUID.randomUUID();
    private final ProjectRepositoryLink link = mock(ProjectRepositoryLink.class);
    private GitHubCommitPoller poller;

    @BeforeEach
    void setUp() {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(projectId);
        when(link.getProject()).thenReturn(project);
        when(link.getOwnerName()).thenReturn("Jatan88");
        when(link.getRepositoryName()).thenReturn("FalkorDB");
        when(link.getSelectedBranch()).thenReturn(null);
        when(links.findAll()).thenReturn(List.of(link));
        when(github.getPublicRepository("Jatan88", "FalkorDB")).thenReturn(
                new GitHubModels.Repository(1, "Jatan88", "FalkorDB", "main", "https://github.com/Jatan88/FalkorDB", true));
        when(github.listCommits("Jatan88", "FalkorDB", "main", 20)).thenReturn(List.of(
                new GitHubModels.CommitSummary("abc1234", "Update README", "2026-10-15T11:00:00Z")));
        poller = new GitHubCommitPoller(links, github, Duration.ofMinutes(30), clock);
    }

    @Test
    void readingTheCacheDoesNotCallGitHub() {
        CommitPollResponse status = poller.status(link);

        assertEquals(List.of(), status.commits());
        assertNull(status.lastPolledAt());
        assertEquals(1800, status.pollIntervalSeconds());
        verify(github, never()).getPublicRepository("Jatan88", "FalkorDB");
        verify(github, never()).listCommits("Jatan88", "FalkorDB", "main", 20);
    }

    @Test
    void automaticPollRunsOncePerInterval() {
        poller.pollDue();
        clock.advance(Duration.ofMinutes(29));
        poller.pollDue();

        verify(github, times(1)).listCommits("Jatan88", "FalkorDB", "main", 20);
        assertEquals("abc1234", poller.cachedCommits(link).getFirst().sha());

        clock.advance(Duration.ofMinutes(2));
        poller.pollDue();
        verify(github, times(2)).listCommits("Jatan88", "FalkorDB", "main", 20);
    }

    @Test
    void manualPollCallsGitHubEvenWhenTheCacheIsFresh() {
        poller.pollDue();
        poller.pollNow(link);

        verify(github, times(2)).listCommits("Jatan88", "FalkorDB", "main", 20);
        assertEquals(clock.instant(), poller.status(link).lastPolledAt());
    }

    @Test
    void pollUsesTheSelectedBranchAndReportsOnlyCommitsThatArriveAfterTheFirstPoll() {
        NewCommitListener listener = mock(NewCommitListener.class);
        when(link.getSelectedBranch()).thenReturn("develop");
        when(github.listCommits("Jatan88", "FalkorDB", "develop", 20)).thenReturn(List.of(
                new GitHubModels.CommitSummary("abc1234", "Update README", "2026-10-15T11:00:00Z")));
        GitHubCommitPoller selected = new GitHubCommitPoller(links, github, Duration.ofMinutes(30), clock, provider(listener));

        selected.pollNow(link);
        verify(listener, never()).onNewCommits(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(github.listCommits("Jatan88", "FalkorDB", "develop", 20)).thenReturn(List.of(
                new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z"),
                new GitHubModels.CommitSummary("abc1234", "Update README", "2026-10-15T11:00:00Z")));
        clock.advance(Duration.ofMinutes(31));
        selected.pollDue();

        verify(listener).onNewCommits(projectId, "develop", List.of(new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z")));
    }

    @Test
    void firstPollQueuesCommitsMadeAfterTheRepositoryWasLinked() {
        NewCommitListener listener = mock(NewCommitListener.class);
        when(link.getCreatedAt()).thenReturn(Instant.parse("2026-10-15T11:30:00Z"));
        when(link.getLastSeenCommitSha()).thenReturn(null);
        when(github.listCommits("Jatan88", "FalkorDB", "main", 20)).thenReturn(List.of(
                new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z"),
                new GitHubModels.CommitSummary("abc1234", "Update README", "2026-10-15T11:00:00Z")));
        GitHubCommitPoller selected = new GitHubCommitPoller(links, github, Duration.ofMinutes(30), clock, provider(listener));

        selected.pollNow(link);

        verify(listener).onNewCommits(projectId, "main", List.of(
                new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z")));

        clock.advance(Duration.ofMinutes(31));
        selected.pollNow(link);
        verify(listener, times(1)).onNewCommits(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void storedCursorQueuesOnlyCommitsAfterThatSha() {
        NewCommitListener listener = mock(NewCommitListener.class);
        when(link.getLastSeenCommitSha()).thenReturn("abc1234");
        when(github.listCommits("Jatan88", "FalkorDB", "main", 20)).thenReturn(List.of(
                new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z"),
                new GitHubModels.CommitSummary("abc1234", "Update README", "2026-10-15T11:00:00Z")));
        GitHubCommitPoller selected = new GitHubCommitPoller(links, github, Duration.ofMinutes(30), clock, provider(listener));

        selected.pollNow(link);

        verify(listener).onNewCommits(projectId, "main", List.of(
                new GitHubModels.CommitSummary("def5678", "Fix checkout", "2026-10-15T12:00:00Z")));
    }

    @Test
    void whenPullRequestPollingIsOnTheFirstPollIsABaselineAndTheNextPollQueuesANewOne() {
        NewPullRequestListener listener = mock(NewPullRequestListener.class);
        org.springframework.beans.factory.ObjectProvider<NewPullRequestListener> pullProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(pullProvider.getIfAvailable()).thenReturn(listener);
        GitHubModels.PullRequest existing = new GitHubModels.PullRequest(
                4, "Existing", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "base", "https://github.com/Jatan88/FalkorDB/pull/4", "feature", "main");
        GitHubModels.PullRequest opened = new GitHubModels.PullRequest(
                5, "Opened", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "base", "https://github.com/Jatan88/FalkorDB/pull/5", "retry", "main");
        when(github.listPullRequests("Jatan88", "FalkorDB", 20)).thenReturn(List.of(existing));
        GitHubCommitPoller watching = new GitHubCommitPoller(
                links, github, Duration.ofMinutes(1), clock, null, null, true, pullProvider);

        watching.pollNow(link);
        verify(listener, never()).onOpenPullRequests(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        when(github.listPullRequests("Jatan88", "FalkorDB", 20)).thenReturn(List.of(opened, existing));
        clock.advance(Duration.ofMinutes(1));
        watching.pollDue();

        verify(listener).onOpenPullRequests(projectId, List.of(opened));
        assertEquals(60, watching.status(link).pollIntervalSeconds());
    }

    private static org.springframework.beans.factory.ObjectProvider<NewCommitListener> provider(NewCommitListener listener) {
        org.springframework.beans.factory.ObjectProvider<NewCommitListener> provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(listener);
        return provider;
    }

    @Test
    void intervalIsConfigurableBetweenOneMinuteAndOneDay() {
        assertEquals(Duration.ofMinutes(1), GitHubCommitPoller.interval(0));
        assertEquals(Duration.ofMinutes(30), GitHubCommitPoller.interval(30));
        assertEquals(Duration.ofHours(24), GitHubCommitPoller.interval(10_000));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
