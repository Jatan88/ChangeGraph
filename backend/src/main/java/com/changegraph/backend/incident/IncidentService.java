package com.changegraph.backend.incident;

import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.common.Text;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.graph.NodeType;
import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.user.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IncidentService {

    private static final int GRAPH_READ_LIMIT = 5000;

    private final ProjectAccess access;
    private final GraphStore graphs;

    public IncidentService(ProjectAccess access, GraphStore graphStore) {
        this.access = access;
        this.graphs = graphStore;
    }

    public List<IncidentResponses.IncidentSummary> list(UserAccount owner, UUID projectId) {
        CodeGraph graph = loadReadyGraph(owner, projectId);
        return IncidentGraphSupport.summaries(graph);
    }

    public IncidentResponses.IncidentDetail get(UserAccount owner, UUID projectId, String incidentId) {
        CodeGraph graph = loadReadyGraph(owner, projectId);
        return IncidentGraphSupport.detail(graph, incidentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found."));
    }

    public IncidentResponses.IncidentDetail create(UserAccount owner, UUID projectId, IncidentRequests.CreateIncident request) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        String incidentId = IncidentIds.incidentId(request.id());
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        if (existing.node(incidentId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "INCIDENT_EXISTS", "An incident with this id already exists.");
        }
        boolean synthetic = Boolean.TRUE.equals(request.synthetic());
        String sourceType = IncidentCatalog.sourceType(request.sourceType(), synthetic ? "SYNTHETIC" : "MANUAL");
        Instant now = Instant.now();
        CodeGraph delta = new CodeGraph();
        delta.mergeNode(NodeType.INCIDENT, incidentId, Map.of(
                "title", Text.clamp(request.title(), 200),
                "summary", Text.clamp(request.summary(), 2000),
                "sourceType", Text.clamp(sourceType, 40),
                "isSynthetic", synthetic,
                "updatedAt", now.toString()));
        graphs.merge(projectId, mapping.getGraphName(), delta);
        return new IncidentResponses.IncidentDetail(
                incidentId,
                Text.clamp(request.title(), 200),
                Text.clamp(request.summary(), 2000),
                Text.clamp(sourceType, 40),
                synthetic,
                List.of(),
                now);
    }

    public IncidentResponses.IncidentDetail update(
            UserAccount owner,
            UUID projectId,
            String incidentId,
            IncidentRequests.UpdateIncident request) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        var incident = existing.node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT);
        if (incident.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found.");
        }
        String title = request.title() == null ? incident.get().property("title") : Text.clamp(request.title(), 200);
        String summary = request.summary() == null ? incident.get().property("summary") : Text.clamp(request.summary(), 2000);
        String sourceType = request.sourceType() == null
                ? incident.get().property("sourceType")
                : Text.clamp(request.sourceType(), 40);
        boolean synthetic = request.synthetic() == null ? incident.get().flag("isSynthetic") : request.synthetic();
        Instant now = Instant.now();
        CodeGraph delta = new CodeGraph();
        delta.mergeNode(NodeType.INCIDENT, incidentId, Map.of(
                "title", title,
                "summary", summary,
                "sourceType", sourceType,
                "isSynthetic", synthetic,
                "updatedAt", now.toString()));
        graphs.merge(projectId, mapping.getGraphName(), delta);
        return get(owner, projectId, incidentId);
    }

    public void delete(UserAccount owner, UUID projectId, String incidentId) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        if (existing.node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found.");
        }
        for (String evidenceId : IncidentGraphSupport.evidenceIdsForIncident(existing, incidentId)) {
            graphs.deleteNode(projectId, mapping.getGraphName(), NodeType.EVIDENCE, evidenceId);
        }
        graphs.deleteNode(projectId, mapping.getGraphName(), NodeType.INCIDENT, incidentId);
    }

    public IncidentResponses.IncidentDetail addEvidence(
            UserAccount owner,
            UUID projectId,
            String incidentId,
            IncidentRequests.CreateEvidence request) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        if (existing.node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found.");
        }
        String evidenceId = IncidentIds.evidenceId(request.id());
        if (existing.node(evidenceId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "EVIDENCE_EXISTS", "Evidence with this id already exists.");
        }
        String functionId = Text.blankToNull(request.functionId());
        if (functionId != null) {
            requireFunction(existing, functionId);
        } else {
            functionId = "";
        }
        String evidenceType = IncidentCatalog.evidenceType(request.evidenceType());
        EdgeType relationship = IncidentCatalog.relationship(request.relationship());
        String verificationStatus = IncidentCatalog.verificationStatus(request.verificationStatus());
        String confidence = IncidentCatalog.confidence(request.confidence());
        boolean synthetic = Boolean.TRUE.equals(request.synthetic());
        String sourceType = IncidentCatalog.sourceType(request.sourceType(), synthetic ? "SYNTHETIC" : "MANUAL");
        Instant now = Instant.now();
        CodeGraph delta = new CodeGraph();
        delta.mergeNode(NodeType.EVIDENCE, evidenceId, Map.of(
                "evidenceType", evidenceType,
                "verificationStatus", verificationStatus,
                "confidence", confidence,
                "summary", Text.clamp(request.summary(), 2000),
                "sourceType", Text.clamp(sourceType, 40),
                "isSynthetic", synthetic,
                "functionId", functionId,
                "updatedAt", now.toString()));
        delta.mergeEdge(relationship, evidenceId, incidentId, Map.of());
        delta.mergeNode(NodeType.INCIDENT, incidentId, Map.of("updatedAt", now.toString()));
        graphs.merge(projectId, mapping.getGraphName(), delta);
        return get(owner, projectId, incidentId);
    }

    public IncidentResponses.IncidentDetail updateEvidence(
            UserAccount owner,
            UUID projectId,
            String incidentId,
            String evidenceId,
            IncidentRequests.UpdateEvidence request) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        if (existing.node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found.");
        }
        var evidence = existing.node(evidenceId).filter(node -> node.getType() == NodeType.EVIDENCE);
        if (evidence.isEmpty() || !IncidentGraphSupport.evidenceIdsForIncident(existing, incidentId).contains(evidenceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Evidence not found for this incident.");
        }
        String functionId = request.functionId() == null
                ? evidence.get().property("functionId")
                : Text.blankToNull(request.functionId());
        if (functionId != null) {
            requireFunction(existing, functionId);
        } else {
            functionId = "";
        }
        EdgeType previousRelationship = currentRelationship(existing, evidenceId, incidentId);
        EdgeType relationship = request.relationship() == null
                ? previousRelationship
                : IncidentCatalog.relationship(request.relationship());
        if (relationship != previousRelationship) {
            graphs.deleteEdge(projectId, mapping.getGraphName(), previousRelationship, evidenceId, incidentId);
        }
        String evidenceType = request.evidenceType() == null
                ? evidence.get().property("evidenceType")
                : IncidentCatalog.evidenceType(request.evidenceType());
        String verificationStatus = request.verificationStatus() == null
                ? evidence.get().property("verificationStatus")
                : IncidentCatalog.verificationStatus(request.verificationStatus());
        String confidence = request.confidence() == null
                ? evidence.get().property("confidence")
                : IncidentCatalog.confidence(request.confidence());
        String summary = request.summary() == null
                ? evidence.get().property("summary")
                : Text.clamp(request.summary(), 2000);
        String sourceType = request.sourceType() == null
                ? evidence.get().property("sourceType")
                : Text.clamp(request.sourceType(), 40);
        boolean synthetic = request.synthetic() == null ? evidence.get().flag("isSynthetic") : request.synthetic();
        Instant now = Instant.now();
        CodeGraph delta = new CodeGraph();
        delta.mergeNode(NodeType.EVIDENCE, evidenceId, Map.of(
                "evidenceType", evidenceType,
                "verificationStatus", verificationStatus,
                "confidence", confidence,
                "summary", summary,
                "sourceType", sourceType,
                "isSynthetic", synthetic,
                "functionId", functionId,
                "updatedAt", now.toString()));
        delta.mergeEdge(relationship, evidenceId, incidentId, Map.of());
        delta.mergeNode(NodeType.INCIDENT, incidentId, Map.of("updatedAt", now.toString()));
        graphs.merge(projectId, mapping.getGraphName(), delta);
        return get(owner, projectId, incidentId);
    }

    public IncidentResponses.IncidentDetail deleteEvidence(
            UserAccount owner,
            UUID projectId,
            String incidentId,
            String evidenceId) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        CodeGraph existing = graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
        if (existing.node(incidentId).filter(node -> node.getType() == NodeType.INCIDENT).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Incident not found.");
        }
        if (!IncidentGraphSupport.evidenceIdsForIncident(existing, incidentId).contains(evidenceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Evidence not found for this incident.");
        }
        graphs.deleteNode(projectId, mapping.getGraphName(), NodeType.EVIDENCE, evidenceId);
        CodeGraph touch = new CodeGraph();
        touch.mergeNode(NodeType.INCIDENT, incidentId, Map.of("updatedAt", Instant.now().toString()));
        graphs.merge(projectId, mapping.getGraphName(), touch);
        return get(owner, projectId, incidentId);
    }

    private CodeGraph loadReadyGraph(UserAccount owner, UUID projectId) {
        ProjectGraph mapping = requireReadyGraph(owner, projectId);
        return graphs.load(projectId, mapping.getGraphName(), GRAPH_READ_LIMIT);
    }

    private ProjectGraph requireReadyGraph(UserAccount owner, UUID projectId) {
        access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(projectId);
        if (mapping.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "Initialize the project graph before managing incidents.");
        }
        return mapping;
    }

    private static void requireFunction(CodeGraph graph, String functionId) {
        if (graph.node(functionId).filter(node -> node.getType() == NodeType.FUNCTION).isEmpty()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "UNKNOWN_FUNCTION",
                    "functionId must match a function already present in this project's graph.");
        }
    }

    private static EdgeType currentRelationship(CodeGraph graph, String evidenceId, String incidentId) {
        for (EdgeType type : IncidentCatalog.EVIDENCE_RELATIONSHIPS) {
            for (var edge : graph.edgesOf(type)) {
                if (evidenceId.equals(edge.getSourceId()) && incidentId.equals(edge.getTargetId())) {
                    return type;
                }
            }
        }
        return EdgeType.REPORTS_ON;
    }
}
