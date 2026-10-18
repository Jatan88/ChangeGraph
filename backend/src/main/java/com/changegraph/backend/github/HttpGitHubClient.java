package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class HttpGitHubClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(HttpGitHubClient.class);
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "ChangeGraph/0.1 (public-repository-analysis)";

    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;
    private final URI apiBase;
    private final Duration requestTimeout;
    private final int maxRetries;
    private final String accessToken;

    public HttpGitHubClient(
            JsonMapper jsonMapper,
            @Value("${changegraph.github.api-base-url:https://api.github.com}") String apiBaseUrl,
            @Value("${changegraph.github.connect-timeout-ms:5000}") long connectTimeoutMs,
            @Value("${changegraph.github.request-timeout-ms:15000}") long requestTimeoutMs,
            @Value("${changegraph.github.max-retries:1}") int maxRetries,
            @Value("${changegraph.github.token:${GITHUB_TOKEN:}}") String accessToken) {
        this.jsonMapper = jsonMapper;
        this.apiBase = validateBase(apiBaseUrl);
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.maxRetries = Math.max(0, Math.min(maxRetries, 2));
        this.accessToken = usableToken(accessToken);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        if (this.accessToken.isEmpty() && accessToken != null && !accessToken.isBlank()) {
            log.warn("GitHub access token was ignored because it is not a single token value");
        } else if (this.accessToken.isEmpty()) {
            log.info("GitHub API requests are unauthenticated and use the 60 requests per hour limit");
        } else {
            log.info("GitHub API requests will use the configured access token");
        }
    }

    @Override
    public GitHubModels.Repository getPublicRepository(String owner, String name) {
        JsonNode json = getJson("/repos/" + encode(owner) + "/" + encode(name), ACCEPT);
        GitHubResponses.requirePublic(json.path("private").asBoolean(true), json.path("visibility").asString(""));
        long id = json.path("id").asLong(0);
        if (id <= 0) {
            throw inaccessible();
        }
        String defaultBranch = json.path("default_branch").asString("main");
        String htmlUrl = "https://github.com/" + owner + "/" + name;
        return new GitHubModels.Repository(id, owner, name, defaultBranch, htmlUrl, true);
    }

    @Override
    public List<GitHubModels.CommitSummary> listCommits(String owner, String name, String branch, int limit) {
        int bounded = Math.max(1, Math.min(limit, 30));
        JsonNode json = getJson(
                "/repos/" + encode(owner) + "/" + encode(name) + "/commits?per_page=" + bounded + "&sha=" + encode(branch),
                ACCEPT);
        List<GitHubModels.CommitSummary> commits = new ArrayList<>();
        if (!json.isArray()) {
            return commits;
        }
        for (JsonNode node : json) {
            commits.add(new GitHubModels.CommitSummary(
                    node.path("sha").asString(""),
                    Text.clamp(node.path("commit").path("message").asString(""), 240),
                    node.path("commit").path("committer").path("date").asString("")));
        }
        return commits;
    }

    @Override
    public GitHubModels.CommitDetail getCommit(String owner, String name, String sha) {
        requireSha(sha);
        JsonNode json = getJson("/repos/" + encode(owner) + "/" + encode(name) + "/commits/" + encode(sha), ACCEPT);
        List<String> parents = new ArrayList<>();
        for (JsonNode parent : json.path("parents")) {
            parents.add(parent.path("sha").asString(""));
        }
        List<GitHubModels.CommitFile> files = new ArrayList<>();
        for (JsonNode file : json.path("files")) {
            files.add(new GitHubModels.CommitFile(
                    file.path("filename").asString(""),
                    file.path("previous_filename").asString(null),
                    file.path("status").asString("modified"),
                    file.path("patch").asString(null),
                    file.path("additions").asInt(0),
                    file.path("deletions").asInt(0)));
        }
        return new GitHubModels.CommitDetail(
                json.path("sha").asString(sha),
                Text.clamp(json.path("commit").path("message").asString(""), 500),
                json.path("commit").path("committer").path("date").asString(""),
                parents,
                files);
    }

    @Override
    public GitHubModels.PullRequest getPullRequest(String owner, String name, int number) {
        if (number <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PULL_REQUEST", "Pull request number must be positive.");
        }
        JsonNode json = getJson("/repos/" + encode(owner) + "/" + encode(name) + "/pulls/" + number, ACCEPT);
        return pullRequest(json, number);
    }

    @Override
    public List<GitHubModels.PullRequest> listPullRequests(String owner, String name, int limit) {
        int bounded = Math.max(1, Math.min(limit, 20));
        JsonNode json = getJson(
                "/repos/" + encode(owner) + "/" + encode(name) + "/pulls?state=open&per_page=" + bounded,
                ACCEPT);
        List<GitHubModels.PullRequest> pulls = new ArrayList<>();
        if (!json.isArray()) {
            return pulls;
        }
        for (JsonNode node : json) {
            pulls.add(pullRequest(node, node.path("number").asInt()));
        }
        return pulls;
    }

    @Override
    public String getFile(String owner, String name, String path, String ref) {
        requireSha(ref);
        if (!com.changegraph.backend.parser.JavaSourceParser.isSafePath(path)) {
            return null;
        }
        Fetched fetched = send(
                "/repos/" + encode(owner) + "/" + encode(name) + "/contents/" + encodePath(path) + "?ref=" + encode(ref),
                "application/vnd.github.raw");
        if (fetched.status() == 404) {
            return null;
        }
        if (fetched.status() != 200) {
            throw statusException(fetched);
        }
        return fetched.body();
    }

    @Override
    public List<String> listSourceFiles(String owner, String name, String directory, String ref) {
        requireSha(ref);
        String dir = directory == null ? "" : directory;
        if (!dir.isBlank() && !com.changegraph.backend.parser.JavaSourceParser.isSafePath(dir)) {
            return List.of();
        }
        String path = dir.isBlank()
                ? "/repos/" + encode(owner) + "/" + encode(name) + "/contents?ref=" + encode(ref)
                : "/repos/" + encode(owner) + "/" + encode(name) + "/contents/" + encodePath(dir) + "?ref=" + encode(ref);
        JsonNode json = getJson(path, ACCEPT);
        List<String> files = new ArrayList<>();
        if (!json.isArray()) {
            return files;
        }
        for (JsonNode node : json) {
            if (!"file".equals(node.path("type").asString())) {
                continue;
            }
            String nameValue = node.path("name").asString("");
            if (com.changegraph.backend.parser.SourcePaths.isContextFile(dir.isBlank() ? nameValue : dir + "/" + nameValue)) {
                String full = dir.isBlank() ? nameValue : dir + "/" + nameValue;
                files.add(full);
            }
        }
        return files;
    }

    @Override
    public List<String> listRepositorySourceFiles(String owner, String name, String ref) {
        requireSha(ref);
        JsonNode json = getJson(
                "/repos/" + encode(owner) + "/" + encode(name) + "/git/trees/" + encode(ref) + "?recursive=1",
                ACCEPT);
        JsonNode tree = json.path("tree");
        List<String> files = new ArrayList<>();
        if (!tree.isArray()) {
            return files;
        }
        for (JsonNode node : tree) {
            if (!"blob".equals(node.path("type").asString())) {
                continue;
            }
            String path = com.changegraph.backend.parser.JavaSourceParser.normalizePath(node.path("path").asString(""));
            if (com.changegraph.backend.parser.SourcePaths.isContextFile(path)
                    && com.changegraph.backend.parser.JavaSourceParser.isSafePath(path)) {
                files.add(path);
            }
        }
        return files;
    }

    @Override
    public GitHubModels.BranchPage listBranches(String owner, String name) {
        Fetched fetched = send(
                "/repos/" + encode(owner) + "/" + encode(name) + "/branches?per_page=100",
                ACCEPT);
        if (fetched.status() == 404) {
            throw inaccessible();
        }
        if (fetched.status() != 200) {
            throw statusException(fetched);
        }
        JsonNode json = readTree(fetched.body());
        List<String> names = new ArrayList<>();
        if (json.isArray()) {
            for (JsonNode node : json) {
                String branch = node.path("name").asString("");
                if (BranchNames.isSafe(branch)) {
                    names.add(branch);
                }
            }
        }
        boolean truncated = fetched.body() != null && linkHeaderHasNext(fetched);
        return new GitHubModels.BranchPage(names, truncated || names.size() >= 100);
    }

    @Override
    public void createBranch(String owner, String name, String branch, String fromSha) {
        requireFixBranch(branch);
        requireSha(fromSha);
        String body = json(Map.of("ref", "refs/heads/" + branch, "sha", fromSha));
        Fetched fetched = send("POST", "/repos/" + encode(owner) + "/" + encode(name) + "/git/refs", ACCEPT, body);
        if (fetched.status() != 201) {
            throw writeFailed(fetched);
        }
    }

    @Override
    public void updateFile(String owner, String name, String branch, String path, String message, String content) {
        requireFixBranch(branch);
        if (!com.changegraph.backend.parser.JavaSourceParser.isSafePath(path)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PATH", "The fix path is not safe to update.");
        }
        if (content == null || content.length() > 100_000) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PATCH", "The fix content is empty or too large.");
        }
        String existingSha = null;
        Fetched current = send(
                "GET",
                "/repos/" + encode(owner) + "/" + encode(name) + "/contents/" + encodePath(path) + "?ref=" + encode(branch),
                ACCEPT,
                null);
        if (current.status() == 200) {
            existingSha = readTree(current.body()).path("sha").asString(null);
        } else if (current.status() != 404) {
            throw writeFailed(current);
        }
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("message", Text.clamp(message, 200));
        payload.put("content", java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        payload.put("branch", branch);
        if (existingSha != null && !existingSha.isBlank()) {
            payload.put("sha", existingSha);
        }
        Fetched fetched = send(
                "PUT",
                "/repos/" + encode(owner) + "/" + encode(name) + "/contents/" + encodePath(path),
                ACCEPT,
                json(payload));
        if (fetched.status() != 200 && fetched.status() != 201) {
            throw writeFailed(fetched);
        }
    }

    @Override
    public GitHubModels.CreatedPullRequest openPullRequest(
            String owner,
            String name,
            String title,
            String head,
            String base,
            String body) {
        requireFixBranch(head);
        if (!BranchNames.isSafe(base) || head.equals(base)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BRANCH_NOT_ALLOWED",
                    "A fix pull request must target a different branch from the fix branch.");
        }
        Map<String, String> payload = new java.util.LinkedHashMap<>();
        payload.put("title", Text.clamp(title, 200));
        payload.put("head", head);
        payload.put("base", base);
        payload.put("body", body == null ? "" : body.substring(0, Math.min(body.length(), 6000)));
        Fetched fetched = send("POST", "/repos/" + encode(owner) + "/" + encode(name) + "/pulls", ACCEPT, json(payload));
        if (fetched.status() != 201) {
            throw writeFailed(fetched);
        }
        JsonNode json = readTree(fetched.body());
        return new GitHubModels.CreatedPullRequest(
                json.path("number").asInt(),
                json.path("html_url").asString(""),
                json.path("head").path("ref").asString(head),
                json.path("base").path("ref").asString(base));
    }

    private static GitHubModels.PullRequest pullRequest(JsonNode json, int fallbackNumber) {
        return new GitHubModels.PullRequest(
                json.path("number").asInt(fallbackNumber),
                Text.clamp(json.path("title").asString(""), 200),
                json.path("head").path("sha").asString(""),
                json.path("base").path("sha").asString(""),
                json.path("html_url").asString(""),
                json.path("head").path("ref").asString(""),
                json.path("base").path("ref").asString(""));
    }

    private JsonNode readTree(String body) {
        try {
            return jsonMapper.readTree(body);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub returned a response that could not be read.");
        }
    }

    private String json(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "The GitHub request could not be encoded.");
        }
    }

    private static boolean linkHeaderHasNext(Fetched fetched) {
        return false;
    }

    private static void requireFixBranch(String branch) {
        if (!BranchNames.isFixBranch(branch)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BRANCH_NOT_ALLOWED",
                    "Generated fixes can only be written to a changegraph/fix branch.");
        }
    }

    private static ApiException writeFailed(Fetched fetched) {
        if (fetched.status() == 401 || fetched.status() == 403) {
            return new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "GITHUB_WRITE_FORBIDDEN",
                    "The configured GitHub token cannot create a branch or pull request on this public repository.");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_WRITE_FAILED", "GitHub did not accept the fix branch or pull request.");
    }

    private JsonNode getJson(String path, String accept) {
        Fetched fetched = send(path, accept);
        if (fetched.status() == 404) {
            throw inaccessible();
        }
        if (fetched.status() != 200) {
            throw statusException(fetched);
        }
        try {
            return jsonMapper.readTree(fetched.body());
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub returned a response that could not be read.");
        }
    }

    private Fetched send(String pathAndQuery, String accept) {
        return send("GET", pathAndQuery, accept, null);
    }

    private Fetched send(String method, String pathAndQuery, String accept, String jsonBody) {
        URI uri = apiBase.resolve(pathAndQuery);
        if (!apiBase.getHost().equalsIgnoreCase(uri.getHost()) || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REPOSITORY_URL", "Only the configured GitHub API host is allowed.");
        }
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .header("X-GitHub-Api-Version", "2022-11-28");
        if (!accessToken.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
        }
        if (jsonBody == null) {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            requestBuilder.header("Content-Type", "application/json");
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
        }
        HttpRequest request = requestBuilder.build();
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if ((status == 502 || status == 503 || status == 504) && attempts <= maxRetries) {
                    continue;
                }
                String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse("");
                if (status == 401) {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "GITHUB_TOKEN_REJECTED",
                            "GitHub rejected the configured access token.");
                }
                if (GitHubResponses.rateLimited(status, remaining, response.body())) {
                    throw new ApiException(
                            HttpStatus.TOO_MANY_REQUESTS,
                            "GITHUB_RATE_LIMITED",
                            accessToken.isEmpty()
                                    ? "GitHub rate limit was reached. Add a personal access token on the server and try again."
                                    : "GitHub rate limit was reached for the configured token. Wait and try again.");
                }
                return new Fetched(status, response.body() == null ? "" : response.body());
            } catch (ApiException ex) {
                throw ex;
            } catch (IOException ex) {
                if (attempts <= maxRetries) {
                    continue;
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub could not be reached.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub could not be reached.");
            }
        }
    }

    private static ApiException statusException(Fetched fetched) {
        if (fetched.status() == 404) {
            return inaccessible();
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "GitHub returned an unexpected response.");
    }

    private static ApiException inaccessible() {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "REPOSITORY_NOT_PUBLIC",
                "The repository is missing, private, or not accessible. This version only supports public GitHub repositories.");
    }

    private static String usableToken(String accessToken) {
        if (accessToken == null) {
            return "";
        }
        String trimmed = accessToken.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.length() < 20 || trimmed.length() > 255 || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return "";
        }
        return trimmed;
    }

    private static void requireSha(String sha) {
        if (sha == null || !sha.matches("[0-9a-fA-F]{7,40}")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_COMMIT", "Commit SHA must be 7 to 40 hexadecimal characters.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(encode(part));
        }
        return builder.toString();
    }

    private static URI validateBase(String apiBaseUrl) {
        URI uri;
        try {
            uri = URI.create(apiBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("GitHub API base URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalStateException("GitHub API base URL must be https without credentials or a port");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("api.github.com")) {
            throw new IllegalStateException("GitHub API base URL host must be api.github.com");
        }
        return URI.create("https://api.github.com");
    }

    private record Fetched(int status, String body) {
    }
}
