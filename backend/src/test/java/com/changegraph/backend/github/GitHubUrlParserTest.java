package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubUrlParserTest {

    @Test
    void acceptsCanonicalPublicRepositoryUrl() {
        RepositoryCoordinates coordinates = GitHubUrlParser.parse("https://github.com/octocat/Hello-World.git");
        assertEquals("octocat", coordinates.owner());
        assertEquals("Hello-World", coordinates.name());
    }

    @Test
    void rejectsNonPublicShapes() {
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("http://github.com/octocat/Hello-World"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://user:token@github.com/octocat/Hello-World"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com:443/octocat/Hello-World"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com.evil.test/octocat/Hello-World"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://github.com/octocat/Hello-World/tree/main"));
        assertThrows(ApiException.class, () -> GitHubUrlParser.parse("https://example.com/octocat/Hello-World"));
    }
}
