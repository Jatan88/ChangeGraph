package com.changegraph.backend.github;

import com.changegraph.backend.common.ApiException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.regex.Pattern;

public final class GitHubUrlParser {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private GitHubUrlParser() {
    }

    public static RepositoryCoordinates parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > 300) {
            reject();
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException ex) {
            reject();
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            reject();
        }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            reject();
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            reject();
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        String[] parts = path.split("/");
        if (parts.length != 3 || !parts[0].isEmpty()) {
            reject();
        }
        String owner = parts[1];
        String name = parts[2];
        if (!NAME.matcher(owner).matches() || !NAME.matcher(name).matches()
                || owner.startsWith(".") || name.startsWith(".") || owner.equals(".") || name.equals(".")) {
            reject();
        }
        return new RepositoryCoordinates(owner, name);
    }

    private static void reject() {
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_REPOSITORY_URL",
                "Enter an https://github.com/owner/repository URL. Only public repositories are supported.");
    }
}
