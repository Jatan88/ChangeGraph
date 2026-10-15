package com.changegraph.backend.controller;

import com.falkordb.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class GraphHealthController {

    private final Driver driver;
    private final String falkorHost;
    private final int falkorPort;

    public GraphHealthController(
            Driver driver,
            @Value("${falkordb.host:localhost}") String falkorHost,
            @Value("${falkordb.port:6379}") int falkorPort) {
        this.driver = driver;
        this.falkorHost = falkorHost;
        this.falkorPort = falkorPort;
    }

    @GetMapping("/api/graph/health")
    public ResponseEntity<Map<String, String>> checkGraphConnection() {
        try {
            driver.graph("changegraph").query("RETURN 1 AS ok");
            return ResponseEntity.ok(
                    Map.of("status", "UP", "database", "FalkorDB", "graph", "changegraph"));
        } catch (RuntimeException ex) {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("status", "DOWN");
            body.put("database", "FalkorDB");
            body.put("target", falkorHost + ":" + falkorPort);
            body.put(
                    "error",
                    isPlainRedisWithoutGraphModule(ex)
                            ? "Connected to Redis but GRAPH.QUERY is not available — load the FalkorDB module "
                                    + "into Redis 8 (run ./scripts/install-falkordb-homebrew.sh) or use FalkorDB Cloud."
                            : isAuthRequired(ex)
                                    ? "Authentication required — set falkordb.username and falkordb.password in "
                                            + "application-local.properties or FALKORDB_USERNAME / FALKORDB_PASSWORD."
                                    : ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    private static boolean isAuthRequired(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("NOAUTH")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlainRedisWithoutGraphModule(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains("unknown command") && message.contains("graph.QUERY")) {
                return true;
            }
        }
        return false;
    }
}