package com.changegraph.backend.workflow;

import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.GraphEdge;
import com.changegraph.backend.graph.GraphNode;
import com.changegraph.backend.graph.NodeType;
import com.changegraph.backend.parser.JavaSourceParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ReviewFiles {

    private ReviewFiles() {
    }

    static Set<String> allowed(Set<String> changedPaths, CodeGraph graph, String commitSha) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        if (changedPaths != null) {
            for (String path : changedPaths) {
                if (safe(path)) {
                    allowed.add(path);
                }
            }
        }
        List<String> testFiles = new ArrayList<>();
        if (graph != null) {
            Set<String> changedFunctions = new LinkedHashSet<>();
            for (GraphEdge edge : graph.edgesOf(EdgeType.CHANGES_FUNCTION)) {
                if (commitSha != null && commitSha.equals(edge.getSourceId())) {
                    changedFunctions.add(edge.getTargetId());
                }
            }
            for (GraphEdge edge : graph.edgesOf(EdgeType.TESTS)) {
                if (!changedFunctions.contains(edge.getTargetId())) {
                    continue;
                }
                graph.node(edge.getSourceId())
                        .filter(node -> node.getType() == NodeType.TEST)
                        .map(node -> node.property("filePath"))
                        .filter(ReviewFiles::safe)
                        .ifPresent(path -> {
                            testFiles.add(path);
                            allowed.add(path);
                        });
            }
            if (testFiles.isEmpty()) {
                for (GraphNode node : graph.nodesOf(NodeType.TEST)) {
                    String path = node.property("filePath");
                    if (safe(path)) {
                        testFiles.add(path);
                    }
                }
            }
        }
        if (allowed.size() < 12) {
            for (String path : List.copyOf(allowed)) {
                String proposed = proposedTest(path, testFiles);
                if (proposed != null) {
                    allowed.add(proposed);
                }
                if (allowed.size() == 12) {
                    break;
                }
            }
        }
        return Set.copyOf(allowed);
    }

    static String proposedTest(String source, List<String> existingTests) {
        if (!safe(source) || existingTests == null || existingTests.isEmpty() || isTestPath(source)) {
            return null;
        }
        String sample = existingTests.getFirst();
        int slash = sample.lastIndexOf('/');
        String directory = slash < 0 ? "" : sample.substring(0, slash + 1);
        String file = source.substring(source.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String name = file.substring(0, dot);
        String extension = file.substring(dot);
        String proposed;
        if (sample.endsWith("Test.java")) {
            proposed = directory + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Test.java";
        } else if (sample.endsWith("_test.py") || sample.substring(sample.lastIndexOf('/') + 1).startsWith("test_")) {
            proposed = directory + "test_" + name + ".py";
        } else if (sample.contains(".test.")) {
            proposed = directory + name + ".test" + extension;
        } else if (sample.contains(".spec.")) {
            proposed = directory + name + ".spec" + extension;
        } else {
            return null;
        }
        if (!safe(proposed) || proposed.equals(source) || existingTests.contains(proposed)) {
            return null;
        }
        return proposed;
    }

    static boolean isTestPath(String path) {
        String lower = path.toLowerCase();
        return lower.contains(".test.")
                || lower.contains(".spec.")
                || lower.endsWith("test.java")
                || lower.contains("/test/")
                || lower.contains("/tests/")
                || lower.endsWith("_test.py")
                || lower.substring(lower.lastIndexOf('/') + 1).startsWith("test_");
    }

    private static boolean safe(String path) {
        return path != null && !path.isBlank() && JavaSourceParser.isSafePath(path);
    }
}
