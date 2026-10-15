package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubResponsesTest {

    @Test
    void rejectsPrivateOrUnverifiedVisibility() {
        assertThrows(ApiException.class, () -> GitHubResponses.requirePublic(true, "private"));
        assertThrows(ApiException.class, () -> GitHubResponses.requirePublic(false, ""));
        assertThrows(ApiException.class, () -> GitHubResponses.requirePublic(true, "public"));
        GitHubResponses.requirePublic(false, "public");
    }

    @Test
    void detectsRateLimitsWithoutRetryingThem() {
        assertTrue(GitHubResponses.rateLimited(403, "0", "ok"));
        assertTrue(GitHubResponses.rateLimited(429, "5", "API rate limit exceeded"));
        assertFalse(GitHubResponses.rateLimited(404, "0", "missing"));
        assertFalse(GitHubResponses.rateLimited(500, "10", "unavailable"));
    }
}
