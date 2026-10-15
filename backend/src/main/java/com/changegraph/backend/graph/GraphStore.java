package com.changegraph.backend.graph;

import java.util.UUID;

public interface GraphStore {

    void initialize(String graphName);

    void deleteOwnedGraph(UUID projectId, String graphName);

    void merge(UUID projectId, String graphName, CodeGraph graph);

    CodeGraph load(UUID projectId, String graphName, int limit);
}
