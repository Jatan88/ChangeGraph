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
