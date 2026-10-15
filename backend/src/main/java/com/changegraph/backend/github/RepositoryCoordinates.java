package com.changegraph.backend.github;

public record RepositoryCoordinates(String owner, String name) {

    public String canonicalUrl() {
        return "https://github.com/" + owner + "/" + name;
    }
}
