package com.changegraph.backend.ingestion;

import com.changegraph.backend.common.Text;
import com.changegraph.backend.github.GitHubModels;
import com.changegraph.backend.graph.CodeGraph;
import com.changegraph.backend.graph.EdgeType;
import com.changegraph.backend.graph.NodeType;
import com.changegraph.backend.parser.CallResolver;
import com.changegraph.backend.parser.DiffHunks;
import com.changegraph.backend.parser.JavaSourceParser;
import com.changegraph.backend.parser.SourceParsers;
import com.changegraph.backend.parser.SourcePaths;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IngestionGraphBuilder {

    public record Input(
            GitHubModels.Repository repository,
            GitHubModels.CommitDetail commit,
            Map<String, String> contentsByPath,
            Map<String, String> parentContentsByPath,
            int maxFiles) {
    }

    public record Built(CodeGraph graph, List<String> limitations) {
    }

    private static JavaSourceParser.ParsedFile parse(String path, String source) {
        return SourceParsers.parse(path, source);
    }

    public Built build(Input input) {
        CodeGraph graph = new CodeGraph();
        List<String> limitations = new ArrayList<>();
        GitHubModels.Repository repository = input.repository();
        graph.mergeNode(NodeType.REPOSITORY, String.valueOf(repository.id()), Map.of(
                "owner", repository.owner(),
                "name", repository.name(),
                "url", repository.url()));

        GitHubModels.CommitDetail commit = input.commit();
        graph.mergeNode(NodeType.COMMIT, commit.sha(), Map.of(
                "sha", commit.sha(),
                "message", Text.clamp(commit.message(), 500),
                "committedAt", commit.committedAt() == null ? "" : commit.committedAt()));

        Map<String, JavaSourceParser.ParsedFile> parsed = new LinkedHashMap<>();
        int sourceFiles = 0;
        boolean unsupported = false;
        boolean missingPatch = false;
        boolean renameNoted = false;
        boolean parentMissing = false;

        List<GitHubModels.CommitFile> files = commit.files() == null ? List.of() : commit.files();
        for (GitHubModels.CommitFile file : files) {
            if (sourceFiles >= input.maxFiles()) {
                limitations.add("Some changed files were skipped because the ingestion file limit was reached.");
                break;
            }
            String path = JavaSourceParser.normalizePath(file.filename());
            if (!JavaSourceParser.isSafePath(path)) {
                limitations.add("A changed path was skipped because it was not a safe repository-relative path.");
                continue;
            }
            String fileChange = fileChangeType(file.status());
            graph.mergeNode(NodeType.FILE, path, Map.of("path", path));
            graph.mergeEdge(EdgeType.CHANGES_FILE, commit.sha(), path, Map.of("changeType", fileChange));
            if (!SourcePaths.isSupported(path)) {
                unsupported = true;
                continue;
            }
            sourceFiles++;
            if ("renamed".equalsIgnoreCase(file.status())) {
                renameNoted = true;
            }
            if (file.patch() == null || file.patch().isBlank()) {
                missingPatch = true;
            }
            String current = input.contentsByPath().get(path);
            String previousPath = file.previousFilename() == null || file.previousFilename().isBlank()
                    ? path
                    : JavaSourceParser.normalizePath(file.previousFilename());
            String parent = input.parentContentsByPath().get(previousPath);
            if (("modified".equalsIgnoreCase(file.status()) || "removed".equalsIgnoreCase(file.status())
                    || "renamed".equalsIgnoreCase(file.status())) && (parent == null || parent.isBlank())) {
                parentMissing = true;
            }
            if ("removed".equalsIgnoreCase(file.status())) {
                if (parent != null) {
                    parsed.put(path, parse(path, parent));
                }
                continue;
            }
            if (current != null) {
                parsed.put(path, parse(path, current));
            } else {
                limitations.add("Public source for " + path + " was not available, so its functions were not parsed.");
            }
            if (parent != null && !previousPath.equals(path)) {
                parsed.putIfAbsent(previousPath, parse(previousPath, parent));
            }
        }

        for (Map.Entry<String, String> context : input.contentsByPath().entrySet()) {
            if (parsed.containsKey(context.getKey()) || sourceFiles >= input.maxFiles()) {
                continue;
            }
            if (!SourcePaths.isSupported(context.getKey()) || context.getValue() == null) {
                continue;
            }
            parsed.put(context.getKey(), parse(context.getKey(), context.getValue()));
            sourceFiles++;
        }

        List<CallResolver.ResolvedFile> resolved = CallResolver.resolve(new ArrayList<>(parsed.values()));
        Map<String, String> classToService = new LinkedHashMap<>();
        for (CallResolver.ResolvedFile file : resolved) {
            JavaSourceParser.ParsedFile parsedFile = file.file();
            String imports = String.join(", ", parsedFile.imports());
            String parseError = parsedFile.errors().isEmpty() ? "" : parsedFile.errors().get(0);
            graph.mergeNode(NodeType.FILE, parsedFile.path(), Map.of(
                    "path", parsedFile.path(),
                    "packageName", parsedFile.packageName(),
                    "parseError", parseError,
                    "importSummary", Text.clamp(imports, 500)));
            if (!parseError.isBlank()) {
                limitations.add("Parse failed for " + parsedFile.path() + " and that file was skipped.");
            }
            for (JavaSourceParser.ParsedService service : parsedFile.services()) {
                graph.mergeNode(NodeType.SERVICE, service.id(), Map.of("name", service.name(), "basis", service.basis()));
                graph.mergeEdge(EdgeType.BELONGS_TO, parsedFile.path(), service.id(), Map.of());
                classToService.put(service.id(), service.id());
            }
            for (CallResolver.ResolvedFunction function : file.functions()) {
                addFunction(graph, function);
            }
        }

        for (CallResolver.ResolvedFile file : resolved) {
            for (CallResolver.ResolvedFunction function : file.functions()) {
                String sourceService = classToService.get(function.function().className());
                for (CallResolver.ResolvedCall call : function.calls()) {
                    if (!call.resolved() || call.targetFunctionId() == null) {
                        continue;
                    }
                    if (function.function().test() && graph.node(call.targetFunctionId()).map(node -> !node.flag("isTest")).orElse(false)) {
                        graph.mergeNode(NodeType.TEST, function.function().id(), Map.of(
                                "name", function.function().signature(),
                                "filePath", file.file().path(),
                                "signature", function.function().qualifiedSignature()));
                        graph.mergeEdge(EdgeType.TESTS, function.function().id(), call.targetFunctionId(), Map.of());
                    } else if (!function.function().test()) {
                        graph.mergeEdge(EdgeType.CALLS, function.function().id(), call.targetFunctionId(), Map.of());
                    }
                    graph.node(call.targetFunctionId()).ifPresent(target -> {
                        String targetService = classToService.get(target.property("className"));
                        if (sourceService != null && targetService != null && !sourceService.equals(targetService)) {
                            graph.mergeEdge(EdgeType.DEPENDS_ON, sourceService, targetService, Map.of());
                        }
                    });
                }
            }
        }

        for (GitHubModels.CommitFile file : files) {
            applyFunctionChanges(graph, commit.sha(), file, input, parsed);
        }

        if (unsupported) {
            limitations.add("Files outside Java, Python, JavaScript, and TypeScript are recorded as changed files and are not parsed.");
        }
        if (missingPatch) {
            limitations.add("GitHub omitted a patch for at least one file, so function-level changes in that file may be incomplete.");
        }
        if (parentMissing) {
            limitations.add("A parent revision was unavailable, so added and deleted functions may be incomplete.");
        }
        if (renameNoted) {
            limitations.add("Rename detection uses GitHub's previous filename when present and does not track deeper history.");
        }
        limitations.add("Method calls are resolved only when the name and argument count match exactly one function in the ingested files.");
        limitations.add("Java, Python, JavaScript, and TypeScript are parsed. A call can connect those languages when the name and argument count match one ingested function. Imports, HTTP routes, and build files are not used to connect them. Repository contents are never executed.");
        graph.mergeNode(NodeType.COMMIT, commit.sha(), Map.of("limitations", Text.clamp(String.join(" ", limitations), 1000)));
        return new Built(graph, limitations);
    }

    private void addFunction(CodeGraph graph, CallResolver.ResolvedFunction resolved) {
        JavaSourceParser.ParsedFunction function = resolved.function();
        graph.mergeNode(NodeType.FUNCTION, function.id(), Map.of(
                "name", function.name(),
                "signature", function.signature(),
                "qualifiedSignature", function.qualifiedSignature(),
                "filePath", function.id().substring(0, function.id().indexOf('#')),
                "className", function.className(),
                "isTest", function.test(),
                "startLine", function.startLine(),
                "endLine", function.endLine(),
                "unresolvedCallCount", resolved.unresolvedCallCount()));
        String path = function.id().substring(0, function.id().indexOf('#'));
        graph.mergeEdge(EdgeType.CONTAINS, path, function.id(), Map.of());
        if (function.test()) {
            graph.mergeNode(NodeType.TEST, function.id(), Map.of(
                    "name", function.signature(),
                    "filePath", path,
                    "signature", function.qualifiedSignature()));
        }
    }

    private void applyFunctionChanges(
            CodeGraph graph,
            String commitSha,
            GitHubModels.CommitFile file,
            Input input,
            Map<String, JavaSourceParser.ParsedFile> parsed) {
        String path = JavaSourceParser.normalizePath(file.filename());
        if (!SourcePaths.isSupported(path)) {
            return;
        }
        String previousPath = file.previousFilename() == null || file.previousFilename().isBlank()
                ? path
                : JavaSourceParser.normalizePath(file.previousFilename());
        JavaSourceParser.ParsedFile current = "removed".equalsIgnoreCase(file.status()) ? null : parsed.get(path);
        String parentSource = input.parentContentsByPath().get(previousPath);
        JavaSourceParser.ParsedFile parent = null;
        if (parentSource != null && !parentSource.isBlank()) {
            parent = previousPath.equals(path) ? parse(previousPath, parentSource) : parsed.getOrDefault(previousPath, parse(previousPath, parentSource));
        }
        if ("removed".equalsIgnoreCase(file.status())) {
            current = null;
        }
        List<DiffHunks.Hunk> hunks = DiffHunks.parse(file.patch());
        if ("added".equalsIgnoreCase(file.status()) && current != null) {
            for (JavaSourceParser.ParsedFunction function : current.functions()) {
                markChanged(graph, commitSha, function.id(), "ADDED");
            }
            return;
        }
        if ("removed".equalsIgnoreCase(file.status()) && parent != null) {
            for (JavaSourceParser.ParsedFunction function : parent.functions()) {
                ensureHistoricalFunction(graph, function);
                markChanged(graph, commitSha, function.id(), "DELETED");
            }
            return;
        }
        Map<String, JavaSourceParser.ParsedFunction> currentBySignature = index(current);
        Map<String, JavaSourceParser.ParsedFunction> parentBySignature = index(parent);
        if (current != null && parent != null && !previousPath.equals(path)) {
            for (JavaSourceParser.ParsedFunction function : parent.functions()) {
                ensureHistoricalFunction(graph, function);
                markChanged(graph, commitSha, function.id(), "DELETED");
            }
            for (JavaSourceParser.ParsedFunction function : current.functions()) {
                markChanged(graph, commitSha, function.id(), "ADDED");
            }
            return;
        }
        if (current != null && parent == null) {
            for (JavaSourceParser.ParsedFunction function : current.functions()) {
                if (DiffHunks.overlaps(function.startLine(), function.endLine(), hunks)) {
                    markChanged(graph, commitSha, function.id(), "MODIFIED");
                }
            }
            return;
        }
        for (Map.Entry<String, JavaSourceParser.ParsedFunction> entry : currentBySignature.entrySet()) {
            JavaSourceParser.ParsedFunction function = entry.getValue();
            if (!parentBySignature.containsKey(entry.getKey())) {
                markChanged(graph, commitSha, function.id(), "ADDED");
            } else if (DiffHunks.overlaps(function.startLine(), function.endLine(), hunks)) {
                markChanged(graph, commitSha, function.id(), "MODIFIED");
            }
        }
        for (Map.Entry<String, JavaSourceParser.ParsedFunction> entry : parentBySignature.entrySet()) {
            if (!currentBySignature.containsKey(entry.getKey())) {
                ensureHistoricalFunction(graph, entry.getValue());
                markChanged(graph, commitSha, entry.getValue().id(), "DELETED");
            }
        }
    }

    private void ensureHistoricalFunction(CodeGraph graph, JavaSourceParser.ParsedFunction function) {
        if (graph.node(function.id()).isEmpty()) {
            addFunction(graph, new CallResolver.ResolvedFunction(function, List.of(), 0));
        }
    }

    private static Map<String, JavaSourceParser.ParsedFunction> index(JavaSourceParser.ParsedFile file) {
        Map<String, JavaSourceParser.ParsedFunction> indexed = new LinkedHashMap<>();
        if (file == null) {
            return indexed;
        }
        for (JavaSourceParser.ParsedFunction function : file.functions()) {
            indexed.put(function.qualifiedSignature(), function);
        }
        return indexed;
    }

    private static void markChanged(CodeGraph graph, String commitSha, String functionId, String changeType) {
        if ("ADDED".equals(changeType) && graph.node(functionId).isEmpty()) {
            return;
        }
        graph.mergeEdge(EdgeType.CHANGES_FUNCTION, commitSha, functionId, Map.of("changeType", changeType));
    }

    private static String fileChangeType(String status) {
        if (status == null) {
            return "MODIFIED";
        }
        return switch (status.toLowerCase()) {
            case "added" -> "ADDED";
            case "removed" -> "DELETED";
            case "renamed" -> "RENAMED";
            default -> "MODIFIED";
        };
    }
}
