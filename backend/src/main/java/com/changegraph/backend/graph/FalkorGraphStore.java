package com.changegraph.backend.graph;

import com.changegraph.backend.common.ApiException;
import com.falkordb.Driver;
import com.falkordb.Graph;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FalkorGraphStore implements GraphStore {

    private static final Logger log = LoggerFactory.getLogger(FalkorGraphStore.class);

    private final Driver driver;

    public FalkorGraphStore(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void initialize(String graphName) {
        requireWellFormed(graphName);
        try {
            graph(graphName).query("RETURN 1 AS ok");
        } catch (RuntimeException ex) {
            log.warn("FalkorDB graph initialization failed ({})", ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GRAPH_INIT_FAILED", "The project graph could not be initialized.");
        }
    }

    @Override
    public void deleteOwnedGraph(UUID projectId, String graphName) {
        if (!GraphNames.matchesProject(projectId, graphName)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "GRAPH_DELETE_REFUSED",
                    "Refusing to delete a graph that does not belong to this project.");
        }
        try {
            graph(graphName).deleteGraph();
        } catch (RuntimeException ex) {
            if (isMissingGraph(ex)) {
                return;
            }
            log.warn("FalkorDB graph delete failed for project {} ({})", projectId, ex.getClass().getSimpleName());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "GRAPH_DELETE_FAILED",
                    "The project graph could not be deleted. The project was kept so deletion can be retried.");
        }
    }

    @Override
    public void deleteEdge(UUID projectId, String graphName, EdgeType type, String sourceId, String targetId) {
        if (!GraphNames.matchesProject(projectId, graphName)) {
            throw new IllegalArgumentException("Unexpected graph name");
        }
        requireWellFormed(sourceId);
        requireWellFormed(targetId);
        String query = switch (type) {
            case REPORTS_ON -> "MATCH (a:Evidence {id: $from})-[r:REPORTS_ON]->(b:Incident {id: $to}) DELETE r";
            case SUPPORTS_CAUSE -> "MATCH (a:Evidence {id: $from})-[r:SUPPORTS_CAUSE]->(b:Incident {id: $to}) DELETE r";
            case SUPPORTS_RESOLUTION -> "MATCH (a:Evidence {id: $from})-[r:SUPPORTS_RESOLUTION]->(b:Incident {id: $to}) DELETE r";
            case VALIDATES_FIX -> "MATCH (a:Evidence {id: $from})-[r:VALIDATES_FIX]->(b:Incident {id: $to}) DELETE r";
            default -> throw new IllegalArgumentException("Unsupported edge delete: " + type);
        };
        try {
            graph(graphName).query(query, Map.of("from", sourceId, "to", targetId));
        } catch (RuntimeException ex) {
            log.warn("FalkorDB edge delete failed for project {} ({})", projectId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GRAPH_DELETE_FAILED", "The graph edge could not be deleted.");
        }
    }

    @Override
    public void deleteNode(UUID projectId, String graphName, NodeType type, String id) {
        if (!GraphNames.matchesProject(projectId, graphName)) {
            throw new IllegalArgumentException("Unexpected graph name");
        }
        requireWellFormed(id);
        try {
            graph(graphName).query(
                    "MATCH (n:" + type.label() + " {id: $id}) DETACH DELETE n",
                    Map.of("id", id));
        } catch (RuntimeException ex) {
            log.warn("FalkorDB node delete failed for project {} ({})", projectId, ex.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GRAPH_DELETE_FAILED", "The graph node could not be deleted.");
        }
    }

    @Override
    public void merge(UUID projectId, String graphName, CodeGraph codeGraph) {
        if (!GraphNames.matchesProject(projectId, graphName)) {
            throw new IllegalArgumentException("Unexpected graph name");
        }
        Graph graph = graph(graphName);
        for (GraphNode node : codeGraph.nodes()) {
            mergeNode(graph, node);
        }
        for (GraphEdge edge : codeGraph.edges()) {
            mergeEdge(graph, edge);
        }
    }

    @Override
    public CodeGraph load(UUID projectId, String graphName, int limit) {
        if (!GraphNames.matchesProject(projectId, graphName)) {
            throw new IllegalArgumentException("Unexpected graph name");
        }
        int bounded = Math.max(1, Math.min(limit, 500));
        Graph graph = graph(graphName);
        CodeGraph codeGraph = new CodeGraph();
        loadNodes(graph, codeGraph, NodeType.REPOSITORY, bounded, "n.owner AS owner, n.name AS name, n.url AS url");
        loadNodes(graph, codeGraph, NodeType.FILE, bounded, "n.path AS path, n.packageName AS packageName, n.parseError AS parseError, n.importSummary AS importSummary");
        loadNodes(graph, codeGraph, NodeType.FUNCTION, bounded,
                "n.name AS name, n.signature AS signature, n.qualifiedSignature AS qualifiedSignature, n.filePath AS filePath, n.className AS className, n.isTest AS isTest, n.startLine AS startLine, n.endLine AS endLine, n.unresolvedCallCount AS unresolvedCallCount");
        loadNodes(graph, codeGraph, NodeType.SERVICE, bounded, "n.name AS name, n.basis AS basis");
        loadNodes(graph, codeGraph, NodeType.TEST, bounded, "n.name AS name, n.filePath AS filePath, n.signature AS signature");
        loadNodes(graph, codeGraph, NodeType.COMMIT, bounded, "n.sha AS sha, n.message AS message, n.committedAt AS committedAt, n.limitations AS limitations");
        loadNodes(graph, codeGraph, NodeType.INCIDENT, bounded, "n.title AS title, n.summary AS summary, n.sourceType AS sourceType, n.isSynthetic AS isSynthetic, n.scenarioKey AS scenarioKey");
        loadNodes(graph, codeGraph, NodeType.EVIDENCE, bounded,
                "n.evidenceType AS evidenceType, n.verificationStatus AS verificationStatus, n.confidence AS confidence, n.summary AS summary, n.sourceType AS sourceType, n.isSynthetic AS isSynthetic, n.functionId AS functionId");
        for (EdgeType type : EdgeType.values()) {
            loadEdges(graph, codeGraph, type, bounded);
        }
        return codeGraph;
    }

    @Override
    public String loadScript(String graphName, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        StringBuilder script = new StringBuilder();
        script.append("GRAPH ").append(graphName).append('\n');
        script.append(nodeRead(NodeType.REPOSITORY, bounded, "n.owner AS owner, n.name AS name, n.url AS url")).append('\n');
        script.append(nodeRead(NodeType.FILE, bounded, "n.path AS path, n.packageName AS packageName, n.parseError AS parseError, n.importSummary AS importSummary")).append('\n');
        script.append(nodeRead(NodeType.FUNCTION, bounded, "n.name AS name, n.signature AS signature, n.qualifiedSignature AS qualifiedSignature, n.filePath AS filePath, n.className AS className, n.isTest AS isTest, n.startLine AS startLine, n.endLine AS endLine, n.unresolvedCallCount AS unresolvedCallCount")).append('\n');
        script.append(nodeRead(NodeType.SERVICE, bounded, "n.name AS name, n.basis AS basis")).append('\n');
        script.append(nodeRead(NodeType.TEST, bounded, "n.name AS name, n.filePath AS filePath, n.signature AS signature")).append('\n');
        script.append(nodeRead(NodeType.COMMIT, bounded, "n.sha AS sha, n.message AS message, n.committedAt AS committedAt, n.limitations AS limitations")).append('\n');
        script.append(nodeRead(NodeType.INCIDENT, bounded, "n.title AS title, n.summary AS summary, n.sourceType AS sourceType, n.isSynthetic AS isSynthetic, n.scenarioKey AS scenarioKey")).append('\n');
        script.append(nodeRead(NodeType.EVIDENCE, bounded, "n.evidenceType AS evidenceType, n.verificationStatus AS verificationStatus, n.confidence AS confidence, n.summary AS summary, n.sourceType AS sourceType, n.isSynthetic AS isSynthetic, n.functionId AS functionId")).append('\n');
        for (EdgeType type : EdgeType.values()) {
            script.append(edgeRead(type, bounded)).append('\n');
        }
        return script.toString().strip();
    }

    private void mergeNode(Graph graph, GraphNode node) {
        String label = node.getType().label();
        Map<String, Object> params = new HashMap<>();
        params.put("id", node.getId());
        StringBuilder sets = new StringBuilder();
        int index = 0;
        for (Map.Entry<String, Object> entry : node.getProperties().entrySet()) {
            String key = entry.getKey();
            if (!key.matches("[A-Za-z][A-Za-z0-9]*")) {
                continue;
            }
            String param = "p" + index++;
            params.put(param, entry.getValue());
            if (!sets.isEmpty()) {
                sets.append(", ");
            }
            sets.append("n.").append(key).append(" = $").append(param);
        }
        String query = sets.isEmpty()
                ? "MERGE (n:" + label + " {id: $id})"
                : "MERGE (n:" + label + " {id: $id}) SET " + sets;
        graph.query(query, params);
    }

    private void mergeEdge(Graph graph, GraphEdge edge) {
        String query = switch (edge.getType()) {
            case CONTAINS -> "MATCH (a:File {id: $from}), (b:Function {id: $to}) MERGE (a)-[:CONTAINS]->(b)";
            case BELONGS_TO -> "MATCH (a:File {id: $from}), (b:Service {id: $to}) MERGE (a)-[:BELONGS_TO]->(b)";
            case CALLS -> "MATCH (a:Function {id: $from}), (b:Function {id: $to}) MERGE (a)-[:CALLS]->(b)";
            case TESTS -> "MATCH (a:Test {id: $from}), (b:Function {id: $to}) MERGE (a)-[:TESTS]->(b)";
            case DEPENDS_ON -> "MATCH (a:Service {id: $from}), (b:Service {id: $to}) MERGE (a)-[:DEPENDS_ON]->(b)";
            case CHANGES_FILE -> "MATCH (a:Commit {id: $from}), (b:File {id: $to}) MERGE (a)-[r:CHANGES_FILE]->(b) SET r.changeType = $changeType";
            case CHANGES_FUNCTION -> "MATCH (a:Commit {id: $from}), (b:Function {id: $to}) MERGE (a)-[r:CHANGES_FUNCTION]->(b) SET r.changeType = $changeType";
            case REPORTS_ON -> "MATCH (a:Evidence {id: $from}), (b:Incident {id: $to}) MERGE (a)-[:REPORTS_ON]->(b)";
            case SUPPORTS_CAUSE -> "MATCH (a:Evidence {id: $from}), (b:Incident {id: $to}) MERGE (a)-[:SUPPORTS_CAUSE]->(b)";
            case SUPPORTS_RESOLUTION -> "MATCH (a:Evidence {id: $from}), (b:Incident {id: $to}) MERGE (a)-[:SUPPORTS_RESOLUTION]->(b)";
            case VALIDATES_FIX -> "MATCH (a:Evidence {id: $from}), (b:Incident {id: $to}) MERGE (a)-[:VALIDATES_FIX]->(b)";
        };
        Map<String, Object> params = new HashMap<>();
        params.put("from", edge.getSourceId());
        params.put("to", edge.getTargetId());
        if (edge.getType() == EdgeType.CHANGES_FILE || edge.getType() == EdgeType.CHANGES_FUNCTION) {
            params.put("changeType", edge.property("changeType"));
        }
        graph.query(query, params);
    }

    private void loadNodes(Graph graph, CodeGraph codeGraph, NodeType type, int limit, String returns) {
        String query = nodeRead(type, limit, returns);
        ResultSet result = graph.readOnlyQuery(query);
        if (result == null) {
            return;
        }
        for (Record record : result) {
            String id = text(record, "id");
            if (id.isBlank()) {
                continue;
            }
            Map<String, Object> properties = new HashMap<>();
            for (String key : record.keys()) {
                if (!"id".equals(key)) {
                    Object value = record.getValue(key);
                    if (value != null) {
                        properties.put(key, value);
                    }
                }
            }
            codeGraph.mergeNode(type, id, properties);
        }
    }

    private void loadEdges(Graph graph, CodeGraph codeGraph, EdgeType type, int limit) {
        String query = edgeRead(type, limit);
        ResultSet result = graph.readOnlyQuery(query);
        if (result == null) {
            return;
        }
        for (Record record : result) {
            String source = text(record, "source");
            String target = text(record, "target");
            if (source.isBlank() || target.isBlank()) {
                continue;
            }
            Map<String, Object> properties = new HashMap<>();
            String changeType = text(record, "changeType");
            if (!changeType.isBlank()) {
                properties.put("changeType", changeType);
            }
            codeGraph.mergeEdge(type, source, target, properties);
        }
    }

    private static String nodeRead(NodeType type, int limit, String returns) {
        return "MATCH (n:" + type.label() + ") RETURN n.id AS id, " + returns + " LIMIT " + limit;
    }

    private static String edgeRead(EdgeType type, int limit) {
        return "MATCH (a)-[r:" + type.name() + "]->(b) RETURN a.id AS source, b.id AS target, r.changeType AS changeType LIMIT " + limit;
    }

    private Graph graph(String graphName) {
        return driver.graph(graphName);
    }

    private static String text(Record record, String key) {
        if (record == null || !record.containsKey(key)) {
            return "";
        }
        // Assign to Object first. String.valueOf on generic getValue() is inferred as char[] and throws ClassCastException for strings.
        Object value = record.getValue(key);
        return value == null ? "" : value.toString();
    }

    private static void requireWellFormed(String graphName) {
        if (!GraphNames.isWellFormed(graphName)) {
            throw new IllegalArgumentException("Invalid graph name");
        }
    }

    private static boolean isMissingGraph(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Invalid graph") || message.contains("empty key"))) {
                return true;
            }
        }
        return false;
    }
}
