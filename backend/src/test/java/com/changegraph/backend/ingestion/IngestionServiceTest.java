package com.changegraph.backend.ingestion;

import com.changegraph.backend.github.GitHubClient;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.GraphNames;
import com.changegraph.backend.graph.GraphStore;
import com.changegraph.backend.graph.NodeType;
import com.changegraph.backend.project.ProjectAccess;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    @Test
    void readmeOnlyCommitStillLoadsJavaFilesFromTheRepository() {
        IngestionJobService jobService = mock(IngestionJobService.class);
        GitHubClient github = mock(GitHubClient.class);
        GraphStore store = mock(GraphStore.class);
        IngestionService service = new IngestionService(
                mock(IngestionJobRepository.class),
                jobService,
                mock(ProjectAccess.class),
                github,
                store,
                40,
                200_000);

        UUID jobId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        String sha = "33e81a79e0907467f54364fcd90e0ff30adc4605";
        when(jobService.loadQueued(jobId)).thenReturn(new IngestionService.JobContext(
                projectId,
                GraphNames.forProject(projectId),
                803389482L,
                "Jatan88",
                "HealthyRoute",
                sha,
                "main"));
        when(github.getPublicRepository("Jatan88", "HealthyRoute")).thenReturn(new GitHubModels.Repository(
                803389482L, "Jatan88", "HealthyRoute", "main", "https://github.com/Jatan88/HealthyRoute", true));
        when(github.getCommit("Jatan88", "HealthyRoute", sha)).thenReturn(new GitHubModels.CommitDetail(
                sha,
                "Update README.md",
                "2024-05-21T16:42:04Z",
                List.of("parent"),
                List.of(new GitHubModels.CommitFile("README.md", null, "modified", "@@ -1 +1 @@", 1, 1))));
        when(github.listSourceFiles(anyString(), anyString(), anyString(), anyString())).thenReturn(List.of());
        when(github.listSourceFiles("Jatan88", "HealthyRoute", "", sha)).thenReturn(List.of("LoadBalancer.java"));
        when(github.listRepositorySourceFiles("Jatan88", "HealthyRoute", sha))
                .thenReturn(List.of("LoadBalancer.java", "src/App.java"));
        when(github.getFile(eq("Jatan88"), eq("HealthyRoute"), eq("LoadBalancer.java"), eq(sha)))
                .thenReturn("class LoadBalancer { void route() {} }");
        when(github.getFile(eq("Jatan88"), eq("HealthyRoute"), eq("src/App.java"), eq(sha)))
                .thenReturn("class App { void start() {} }");

        service.execute(jobId);

        ArgumentCaptor<CodeGraph> graph = ArgumentCaptor.forClass(CodeGraph.class);
        verify(store).merge(eq(projectId), eq(GraphNames.forProject(projectId)), graph.capture());
        assertTrue(graph.getValue().node("README.md").isPresent());
        assertTrue(graph.getValue().node("LoadBalancer.java").isPresent());
        assertTrue(graph.getValue().node("src/App.java").isPresent());
        assertTrue(graph.getValue().nodesOf(NodeType.FUNCTION).stream().anyMatch(node -> "route".equals(node.property("name"))));
        verify(jobService).mark(eq(jobId), eq(JobStatus.SUCCEEDED), any(), isNull(), eq(true));
    }
}
