package com.changegraph.backend.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_graphs")
public class ProjectGraph {

    public static final String PROVIDER = "FALKORDB";

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Column(name = "graph_name", nullable = false, unique = true, length = 64)
    private String graphName;

    @Column(nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GraphLifecycle status;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProjectGraph() {
    }

    public ProjectGraph(UUID id, Project project, String graphName, Instant createdAt) {
        this.id = id;
        this.project = project;
        this.graphName = graphName;
        this.provider = PROVIDER;
        this.status = GraphLifecycle.PENDING;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public String getGraphName() {
        return graphName;
    }

    public String getProvider() {
        return provider;
    }

    public GraphLifecycle getStatus() {
        return status;
    }

    public void setStatus(GraphLifecycle status) {
        this.status = status;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
