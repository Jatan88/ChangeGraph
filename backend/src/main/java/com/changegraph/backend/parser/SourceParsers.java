package com.changegraph.backend.parser;

import java.util.List;

public final class SourceParsers {

    private static final JavaSourceParser JAVA = new JavaSourceParser();
    private static final PythonSourceParser PYTHON = new PythonSourceParser();
    private static final ScriptSourceParser SCRIPT = new ScriptSourceParser();

    private SourceParsers() {
    }

    public static JavaSourceParser.ParsedFile parse(String path, String source) {
        SourcePaths.Language language = SourcePaths.language(path);
        if (language == null) {
            return new JavaSourceParser.ParsedFile(
                    SourcePaths.normalizePath(path), "", List.of(), List.of(), List.of(),
                    List.of("This file is not in a supported language."));
        }
        return switch (language) {
            case JAVA -> JAVA.parse(path, source);
            case PYTHON -> PYTHON.parse(path, source);
            case JAVASCRIPT, TYPESCRIPT -> SCRIPT.parse(path, source);
        };
    }
}
