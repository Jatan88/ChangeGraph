package com.changegraph.backend.parser;

import java.util.Locale;

public final class SourcePaths {

    public enum Language {
        JAVA,
        PYTHON,
        JAVASCRIPT,
        TYPESCRIPT
    }

    private SourcePaths() {
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    public static boolean isSafePath(String path) {
        String normalized = normalizePath(path);
        return !normalized.isBlank()
                && !normalized.contains("..")
                && !normalized.contains(":")
                && !normalized.startsWith("/");
    }

    public static Language language(String path) {
        String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".d.ts")) {
            return null;
        }
        if (normalized.endsWith(".java")) {
            return Language.JAVA;
        }
        if (normalized.endsWith(".py")) {
            return Language.PYTHON;
        }
        if (normalized.endsWith(".js") || normalized.endsWith(".jsx")
                || normalized.endsWith(".mjs") || normalized.endsWith(".cjs")) {
            return Language.JAVASCRIPT;
        }
        if (normalized.endsWith(".ts") || normalized.endsWith(".tsx")
                || normalized.endsWith(".mts") || normalized.endsWith(".cts")) {
            return Language.TYPESCRIPT;
        }
        return null;
    }

    public static boolean isSupported(String path) {
        return language(path) != null;
    }

    public static boolean isContextFile(String path) {
        return isSupported(path) && !isDependencyPath(normalizePath(path).toLowerCase(Locale.ROOT));
    }

    private static boolean isDependencyPath(String normalized) {
        return hasSegment(normalized, "node_modules")
                || hasSegment(normalized, "dist")
                || hasSegment(normalized, "build")
                || hasSegment(normalized, "target")
                || hasSegment(normalized, "vendor")
                || hasSegment(normalized, "__pycache__")
                || hasSegment(normalized, ".venv")
                || hasSegment(normalized, "venv")
                || hasSegment(normalized, "site-packages")
                || hasSegment(normalized, ".next")
                || hasSegment(normalized, "coverage");
    }

    private static boolean hasSegment(String path, String segment) {
        return path.equals(segment) || path.startsWith(segment + "/") || path.contains("/" + segment + "/");
    }
}
