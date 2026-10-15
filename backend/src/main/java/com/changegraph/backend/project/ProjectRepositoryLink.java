package com.changegraph.backend.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_repositories")
public class ProjectRepositoryLink {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Column(name = "github_repository_id", nullable = false)
    private long githubRepositoryId;

    @Column(name = "owner_name", nullable = false, length = 100)
    private String ownerName;

    @Column(name = "repository_name", nullable = false, length = 100)
    private String repositoryName;

    @Column(name = "default_branch", nullable = false, length = 255)
    private String defaultBranch;

    @Column(name = "repository_url", nullable = false, length = 300)
    private String repositoryUrl;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProjectRepositoryLink() {
    }

    public ProjectRepositoryLink(
            UUID id,
            Project project,
            long githubRepositoryId,
            String ownerName,
            String repositoryName,
            String defaultBranch,
            String repositoryUrl,
            Instant createdAt) {
        this.id = id;
        this.project = project;
        this.githubRepositoryId = githubRepositoryId;
        this.ownerName = ownerName;
        this.repositoryName = repositoryName;
        this.defaultBranch = defaultBranch;
        this.repositoryUrl = repositoryUrl;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public long getGithubRepositoryId() {
        return githubRepositoryId;
    }

    public void setGithubRepositoryId(long githubRepositoryId) {
        this.githubRepositoryId = githubRepositoryId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public void setOwnerName(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getRepositoryName() {
        return repositoryName;
    }

    public void setRepositoryName(String repositoryName) {
        this.repositoryName = repositoryName;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getRepositoryUrl() {
        return repositoryUrl;
    }

    public void setRepositoryUrl(String repositoryUrl) {
        this.repositoryUrl = repositoryUrl;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
