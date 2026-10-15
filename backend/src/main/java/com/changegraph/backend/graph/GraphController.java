package com.changegraph.backend.graph;

import com.changegraph.backend.auth.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/graph")
public class GraphController {

    private final GraphQueryService graphs;
    private final CurrentUser currentUser;

    public GraphController(GraphQueryService graphs, CurrentUser currentUser) {
        this.graphs = graphs;
        this.currentUser = currentUser;
    }

    @GetMapping
    public GraphView view(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "200") int limit) {
        return graphs.view(currentUser.require(), projectId, type, limit);
    }
}
