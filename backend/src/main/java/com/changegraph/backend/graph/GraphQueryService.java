package com.changegraph.backend.graph;

import com.changegraph.backend.project.GraphLifecycle;
import com.changegraph.backend.project.ProjectAccess;
import com.changegraph.backend.project.ProjectGraph;
import com.changegraph.backend.common.ApiException;
import com.changegraph.backend.user.UserAccount;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GraphQueryService {

    private final ProjectAccess access;
    private final GraphStore graphStore;

    public GraphQueryService(ProjectAccess access, GraphStore graphStore) {
        this.access = access;
        this.graphStore = graphStore;
    }

    public GraphView view(UserAccount owner, UUID projectId, String type, int limit) {
        var project = access.requireProject(owner, projectId);
        ProjectGraph mapping = access.requireGraph(project.getId());
        if (mapping.getStatus() != GraphLifecycle.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "GRAPH_NOT_READY", "The project graph is not ready.");
        }
        NodeType filter = type == null || type.isBlank() ? null : NodeType.fromLabel(type);
        if (type != null && !type.isBlank() && filter == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_NODE_TYPE", "Unknown node type.");
        }
        int bounded = Math.max(1, Math.min(limit, 200));
        CodeGraph graph = graphStore.load(project.getId(), mapping.getGraphName(), bounded);
        List<GraphView.NodeView> nodes = new ArrayList<>();
        for (GraphNode node : graph.nodes()) {
            if (filter != null && node.getType() != filter) {
                continue;
            }
            String label = switch (node.getType()) {
                case FUNCTION, TEST -> node.property("signature");
                case FILE -> node.property("path");
                case COMMIT -> node.property("sha");
                case SERVICE, INCIDENT -> node.property("name").isBlank() ? node.property("title") : node.property("name");
                case REPOSITORY -> node.property("name");
                case EVIDENCE -> node.property("evidenceType");
            };
            nodes.add(new GraphView.NodeView(node.getId(), node.getType().label(), label.isBlank() ? node.getId() : label, node.getProperties()));
        }
        List<GraphView.EdgeView> edges = graph.edges().stream()
                .filter(edge -> filter == null || nodes.stream().anyMatch(node -> node.id().equals(edge.getSourceId()) || node.id().equals(edge.getTargetId())))
                .map(edge -> new GraphView.EdgeView(edge.getType().name(), edge.getSourceId(), edge.getTargetId(), edge.getProperties()))
                .toList();
        boolean truncated = graph.nodeCount() >= bounded;
        return new GraphView(nodes, edges, truncated);
    }
}
