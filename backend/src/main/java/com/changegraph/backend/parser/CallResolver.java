package com.changegraph.backend.parser;

import com.changegraph.backend.parser.JavaSourceParser.ParsedCall;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFile;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFunction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CallResolver {

    public record ResolvedCall(ParsedCall call, boolean resolved, String targetFunctionId) {
    }

    public record ResolvedFunction(ParsedFunction function, List<ResolvedCall> calls, int unresolvedCallCount) {
    }

    public record ResolvedFile(ParsedFile file, List<ResolvedFunction> functions) {
    }

    private CallResolver() {
    }

    public static List<ResolvedFile> resolve(List<ParsedFile> files) {
        Map<String, List<ParsedFunction>> methodsByName = new HashMap<>();
        Map<String, List<ParsedFunction>> constructorsBySimpleName = new HashMap<>();
        for (ParsedFile file : files) {
            for (ParsedFunction function : file.functions()) {
                if (function.constructor()) {
                    String simple = simpleClassName(function.className());
                    constructorsBySimpleName.computeIfAbsent(simple, ignored -> new ArrayList<>()).add(function);
                } else {
                    methodsByName.computeIfAbsent(function.name(), ignored -> new ArrayList<>()).add(function);
                }
            }
        }
        List<ResolvedFile> resolvedFiles = new ArrayList<>();
        for (ParsedFile file : files) {
            List<ResolvedFunction> functions = new ArrayList<>();
            for (ParsedFunction function : file.functions()) {
                List<ResolvedCall> calls = new ArrayList<>();
                int unresolved = 0;
                for (ParsedCall call : function.calls()) {
                    List<ParsedFunction> candidates = "CONSTRUCTOR".equals(call.kind())
                            ? constructorsBySimpleName.getOrDefault(call.calleeName(), List.of())
                            : methodsByName.getOrDefault(call.calleeName(), List.of());
                    List<ParsedFunction> matches = candidates.stream()
                            .filter(candidate -> parameterCount(candidate) == call.argumentCount())
                            .toList();
                    if (matches.size() == 1) {
                        calls.add(new ResolvedCall(call, true, matches.get(0).id()));
                    } else {
                        unresolved++;
                        calls.add(new ResolvedCall(call, false, null));
                    }
                }
                functions.add(new ResolvedFunction(function, calls, unresolved));
            }
            resolvedFiles.add(new ResolvedFile(file, functions));
        }
        return resolvedFiles;
    }

    private static int parameterCount(ParsedFunction function) {
        String signature = function.signature();
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1) {
            return 0;
        }
        String inside = signature.substring(open + 1, close).trim();
        if (inside.isEmpty()) {
            return 0;
        }
        return inside.split(",").length;
    }

    private static String simpleClassName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }
}
