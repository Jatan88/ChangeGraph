package com.changegraph.backend.graph;

import com.falkordb.Driver;
import com.falkordb.GraphContextGenerator;
import com.falkordb.Record;
import com.falkordb.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FalkorGraphStoreTest {

    @Test
    void mergeUsesParameterizedMergeAndDeleteRefusesForeignGraphs() {
        Driver driver = mock(Driver.class);
        GraphContextGenerator graph = mock(GraphContextGenerator.class);
        UUID projectId = UUID.randomUUID();
        String graphName = GraphNames.forProject(projectId);
        when(driver.graph(graphName)).thenReturn(graph);
        FalkorGraphStore store = new FalkorGraphStore(driver);

        CodeGraph codeGraph = new CodeGraph();
        codeGraph.mergeNode(NodeType.FUNCTION, "fn", Map.of("name", "charge"));
        store.merge(projectId, graphName, codeGraph);

        verify(graph).query(contains("MERGE (n:Function {id: $id})"), anyMap());
        assertThrows(com.changegraph.backend.common.ApiException.class,
                () -> store.deleteOwnedGraph(projectId, "changegraph"));
        verify(graph, org.mockito.Mockito.never()).deleteGraph();
    }

    @Test
    void loadReadsStringIdentifiers() {
        Driver driver = mock(Driver.class);
        GraphContextGenerator graph = mock(GraphContextGenerator.class);
        UUID projectId = UUID.randomUUID();
        String graphName = GraphNames.forProject(projectId);
        when(driver.graph(graphName)).thenReturn(graph);
        Record record = mock(Record.class);
        when(record.containsKey("id")).thenReturn(true);
        when(record.getValue("id")).thenReturn("repo-1");
        when(record.keys()).thenReturn(List.of("id", "name"));
        when(record.getValue("name")).thenReturn("billing");
        ResultSet result = mock(ResultSet.class);
        when(result.iterator()).thenReturn(List.<Record>of(record).iterator());
        when(graph.readOnlyQuery(contains("(n:Repository)"))).thenReturn(result);

        CodeGraph loaded = new FalkorGraphStore(driver).load(projectId, graphName, 10);

        GraphNode node = loaded.node("repo-1").orElseThrow();
        assertEquals(NodeType.REPOSITORY, node.getType());
        assertEquals("billing", node.property("name"));
    }
}
