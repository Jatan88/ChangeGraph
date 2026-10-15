package com.changegraph.backend.parser;

import com.changegraph.backend.parser.JavaSourceParser.ParsedCall;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFile;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFunction;
import com.changegraph.backend.parser.JavaSourceParser.ParsedService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PythonSourceParser {

    private static final Pattern DEF = Pattern.compile("^(?:async\\s+)?def\\s+([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern CLASS = Pattern.compile("^class\\s+([A-Za-z_]\\w*)");
    private static final Set<String> CALL_KEYWORDS = Set.of(
            "if", "elif", "while", "for", "with", "except", "assert", "return", "def", "class", "lambda",
            "and", "or", "not", "in", "is", "else", "try", "finally", "yield", "raise", "from", "import",
            "pass", "del", "global", "nonlocal", "async", "await");
    private static final Set<String> SERVICE_DECORATORS = Set.of(
            "service", "component", "controller", "repository", "router", "injectable", "module");

    public ParsedFile parse(String path, String source) {
        String normalized = SourcePaths.normalizePath(path);
        if (source == null) {
            return new ParsedFile(normalized, "", List.of(), List.of(), List.of(), List.of("Source was not available."));
        }
        String module = moduleName(normalized);
        List<Stmt> statements = statements(source);
        List<ParsedFunction> functions = new ArrayList<>();
        List<ParsedService> services = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        Deque<ClassFrame> classes = new ArrayDeque<>();
        Deque<FunctionFrame> frames = new ArrayDeque<>();
        List<String> pendingDecorators = new ArrayList<>();

        for (Stmt statement : statements) {
            String trimmed = statement.text().strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            while (!frames.isEmpty() && statement.indent() <= frames.peek().indent) {
                functions.add(frames.pop().toFunction(normalized));
            }
            while (!classes.isEmpty() && statement.indent() <= classes.peek().indent) {
                classes.pop();
            }
            if (trimmed.startsWith("@")) {
                pendingDecorators.add(decoratorName(trimmed));
                continue;
            }
            if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                if (imports.size() < 40) {
                    imports.add(firstLine(trimmed));
                }
                pendingDecorators.clear();
                if (!frames.isEmpty()) {
                    frames.peek().absorb(statement);
                }
                continue;
            }
            Matcher classMatch = CLASS.matcher(trimmed);
            if (classMatch.find() && classMatch.start() == 0) {
                String simple = classMatch.group(1);
                String qualified = classes.isEmpty() ? module + "." + simple : classes.peek().qualified + "." + simple;
                String basis = serviceBasis(pendingDecorators);
                if (basis != null) {
                    services.add(new ParsedService(qualified, simple, basis));
                }
                classes.push(new ClassFrame(statement.indent(), simple, qualified));
                pendingDecorators.clear();
                continue;
            }
            Matcher defMatch = DEF.matcher(trimmed);
            if (defMatch.find() && defMatch.start() == 0) {
                String name = defMatch.group(1);
                int open = defMatch.end() - 1;
                int close = matchingClose(trimmed, open);
                String params = close > open ? trimmed.substring(open + 1, close) : "";
                boolean directMethod = frames.isEmpty() && classes.stream().anyMatch(type -> type.indent < statement.indent());
                boolean staticMethod = pendingDecorators.stream().anyMatch(decorator -> decorator.equalsIgnoreCase("staticmethod"));
                List<String> names = parameterNames(params, directMethod && !staticMethod);
                boolean constructor = directMethod && "__init__".equals(name);
                ClassFrame owner = enclosingClass(classes, statement.indent());
                String className = classNameFor(module, owner, frames, directMethod);
                String signatureName = constructor && owner != null ? owner.simple : name;
                boolean test = isTest(normalized, owner == null ? "" : owner.simple, name);
                frames.push(new FunctionFrame(
                        statement.indent(),
                        statement.startLine(),
                        statement.endLine(),
                        constructor ? "<init>" : name,
                        className,
                        signature(signatureName, names),
                        test,
                        constructor,
                        List.copyOf(pendingDecorators)));
                pendingDecorators.clear();
                continue;
            }
            pendingDecorators.clear();
            if (!frames.isEmpty()) {
                frames.peek().absorb(statement);
            }
        }
        while (!frames.isEmpty()) {
            functions.add(frames.pop().toFunction(normalized));
        }
        return new ParsedFile(normalized, module, imports, functions, services, List.of());
    }

    private static String classNameFor(String module, ClassFrame owner, Deque<FunctionFrame> frames, boolean directMethod) {
        if (owner == null) {
            return module;
        }
        if (directMethod || frames.isEmpty()) {
            return owner.qualified;
        }
        return owner.qualified + "." + frames.peek().name;
    }

    private static ClassFrame enclosingClass(Deque<ClassFrame> classes, int indent) {
        for (ClassFrame type : classes) {
            if (type.indent < indent) {
                return type;
            }
        }
        return null;
    }

    private static boolean isTest(String path, String className, String functionName) {
        String normalized = path.toLowerCase(Locale.ROOT);
        String file = normalized.substring(normalized.lastIndexOf('/') + 1);
        boolean testFile = normalized.contains("/test/") || normalized.contains("/tests/")
                || file.startsWith("test_") || file.endsWith("_test.py");
        boolean testClass = className.startsWith("Test") || className.endsWith("Test") || className.endsWith("Tests");
        return functionName.startsWith("test_") || testClass || (testFile && functionName.startsWith("test"));
    }

    private static String serviceBasis(List<String> decorators) {
        for (String decorator : decorators) {
            if (SERVICE_DECORATORS.contains(decorator.toLowerCase(Locale.ROOT))) {
                return decorator;
            }
        }
        return null;
    }

    private static String decoratorName(String text) {
        String name = text.substring(1).stripLeading();
        int end = 0;
        while (end < name.length() && (Character.isLetterOrDigit(name.charAt(end)) || name.charAt(end) == '_' || name.charAt(end) == '.')) {
            end++;
        }
        String dotted = name.substring(0, end);
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    private static List<String> parameterNames(String params, boolean dropReceiver) {
        List<String> names = new ArrayList<>();
        for (String piece : splitTopLevel(maskStrings(params))) {
            String raw = piece.strip();
            if (raw.isEmpty() || "/".equals(raw) || "*".equals(raw)) {
                continue;
            }
            String name = parameterName(raw);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (dropReceiver && !names.isEmpty() && (names.get(0).equals("self") || names.get(0).equals("cls"))) {
            names.remove(0);
        }
        return names;
    }

    private static String parameterName(String raw) {
        String masked = maskStrings(raw);
        int cut = masked.length();
        int depth = 0;
        for (int i = 0; i < masked.length(); i++) {
            char current = masked.charAt(i);
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && (current == ':' || current == '=')) {
                cut = i;
                break;
            }
        }
        String left = raw.substring(0, cut).strip();
        while (left.startsWith("*")) {
            left = left.substring(1).strip();
        }
        int start = left.length();
        while (start > 0 && (Character.isLetterOrDigit(left.charAt(start - 1)) || left.charAt(start - 1) == '_')) {
            start--;
        }
        return left.substring(start);
    }

    private static List<ParsedCall> callsIn(String text) {
        String masked = maskStrings(text);
        List<ParsedCall> calls = new ArrayList<>();
        for (int i = 0; i < masked.length(); i++) {
            if (masked.charAt(i) != '(') {
                continue;
            }
            int end = skipBack(masked, i);
            int start = end;
            while (start > 0 && isIdent(masked.charAt(start - 1))) {
                start--;
            }
            if (start == end) {
                continue;
            }
            String name = masked.substring(start, end);
            if (CALL_KEYWORDS.contains(name) || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
                continue;
            }
            int args = argumentCount(masked, i);
            String kind = Character.isUpperCase(name.charAt(0)) ? "CONSTRUCTOR" : "METHOD";
            calls.add(new ParsedCall(name, args, kind));
        }
        return calls;
    }

    private static int argumentCount(String masked, int openParen) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = openParen; i < masked.length(); i++) {
            char current = masked.charAt(i);
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            } else if (depth == 1 && current == ',') {
                commas++;
            } else if (depth == 1 && !Character.isWhitespace(current)) {
                any = true;
            }
        }
        return any ? commas + 1 : 0;
    }

    private static int matchingClose(String text, int openParen) {
        String masked = maskStrings(text);
        int depth = 0;
        for (int i = openParen; i < masked.length(); i++) {
            char current = masked.charAt(i);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTopLevel(String masked) {
        List<String> pieces = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < masked.length(); i++) {
            char current = masked.charAt(i);
            if (current == '(' || current == '[' || current == '{') {
                depth++;
            } else if (current == ')' || current == ']' || current == '}') {
                depth = Math.max(0, depth - 1);
            } else if (current == ',' && depth == 0) {
                pieces.add(masked.substring(start, i));
                start = i + 1;
            }
        }
        pieces.add(masked.substring(start));
        return pieces;
    }

    private static String maskStrings(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        boolean inString = false;
        boolean triple = false;
        boolean escaped = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (!inString) {
                if (current == '#' ) {
                    while (i < text.length() && text.charAt(i) != '\n') {
                        masked.append(' ');
                        i++;
                    }
                    if (i < text.length()) {
                        masked.append('\n');
                    }
                    continue;
                }
                int prefix = stringPrefix(text, i);
                if (prefix >= 0 && quoteAt(text, prefix)) {
                    quote = text.charAt(prefix);
                    triple = prefix + 2 < text.length() && text.charAt(prefix + 1) == quote && text.charAt(prefix + 2) == quote;
                    for (int j = i; j <= prefix + (triple ? 2 : 0); j++) {
                        masked.append(text.charAt(j) == '\n' ? '\n' : 'x');
                    }
                    i = prefix + (triple ? 2 : 0);
                    inString = true;
                    escaped = false;
                    continue;
                }
                masked.append(current);
                continue;
            }
                masked.append(current == '\n' ? '\n' : 'x');
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && !triple) {
                escaped = true;
                continue;
            }
            if (current == quote) {
                if (!triple) {
                    inString = false;
                } else if (i + 2 < text.length() && text.charAt(i + 1) == quote && text.charAt(i + 2) == quote) {
                    masked.append("  ");
                    i += 2;
                    inString = false;
                }
            }
        }
        return masked.toString();
    }

    private static int stringPrefix(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && "rRbBuUfF".indexOf(text.charAt(cursor)) >= 0 && cursor - index < 3) {
            cursor++;
        }
        if (cursor < text.length() && (text.charAt(cursor) == '\'' || text.charAt(cursor) == '"')) {
            return cursor;
        }
        return -1;
    }

    private static boolean quoteAt(String text, int index) {
        return index < text.length() && (text.charAt(index) == '\'' || text.charAt(index) == '"');
    }

    private static List<Stmt> statements(String source) {
        List<Stmt> statements = new ArrayList<>();
        int length = source.length();
        int index = 0;
        int line = 1;
        while (index < length) {
            int indent = 0;
            int cursor = index;
            int startLine = line;
            while (cursor < length) {
                char current = source.charAt(cursor);
                if (current == ' ') {
                    indent++;
                    cursor++;
                } else if (current == '\t') {
                    indent += 8;
                    cursor++;
                } else {
                    break;
                }
            }
            int content = cursor;
            boolean inString = false;
            boolean triple = false;
            boolean escaped = false;
            char quote = 0;
            int paren = 0;
            int bracket = 0;
            int brace = 0;
            while (cursor < length) {
                char current = source.charAt(cursor);
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\' && !triple) {
                        escaped = true;
                    } else if (current == quote) {
                        if (!triple) {
                            inString = false;
                        } else if (cursor + 2 < length && source.charAt(cursor + 1) == quote && source.charAt(cursor + 2) == quote) {
                            cursor += 2;
                            inString = false;
                        }
                    }
                    if (current == '\n') {
                        line++;
                    }
                    cursor++;
                    continue;
                }
                int prefix = stringPrefix(source, cursor);
                if (prefix >= 0 && (source.charAt(cursor) == '\'' || source.charAt(cursor) == '"' || prefix != cursor)) {
                    if (quoteAt(source, prefix)) {
                        quote = source.charAt(prefix);
                        triple = prefix + 2 < length && source.charAt(prefix + 1) == quote && source.charAt(prefix + 2) == quote;
                        cursor = prefix + (triple ? 3 : 1);
                        inString = true;
                        escaped = false;
                        continue;
                    }
                }
                if (current == '#') {
                    while (cursor < length && source.charAt(cursor) != '\n') {
                        cursor++;
                    }
                    continue;
                }
                if (current == '(') {
                    paren++;
                } else if (current == ')') {
                    paren = Math.max(0, paren - 1);
                } else if (current == '[') {
                    bracket++;
                } else if (current == ']') {
                    bracket = Math.max(0, bracket - 1);
                } else if (current == '{') {
                    brace++;
                } else if (current == '}') {
                    brace = Math.max(0, brace - 1);
                }
                if (current == '\n') {
                    line++;
                    boolean continued = cursor > content && source.charAt(cursor - 1) == '\\';
                    cursor++;
                    if (!continued && paren == 0 && bracket == 0 && brace == 0) {
                        break;
                    }
                    continue;
                }
                cursor++;
            }
            String text = source.substring(content, Math.min(cursor, length));
            if (!text.isBlank()) {
                int endLine = line - (cursor < length || source.endsWith("\n") ? 1 : 0);
                if (endLine < startLine) {
                    endLine = startLine;
                }
                statements.add(new Stmt(startLine, endLine, indent, text));
            }
            index = cursor;
        }
        return statements;
    }

    private static String moduleName(String path) {
        String without = path.endsWith(".py") ? path.substring(0, path.length() - 3) : path;
        if (without.endsWith("/__init__")) {
            without = without.substring(0, without.length() - "/__init__".length());
        }
        return without.replace('/', '.');
    }

    private static String signature(String name, List<String> parameters) {
        return name + "(" + String.join(",", parameters) + ")";
    }

    private static String firstLine(String text) {
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        return line.length() <= 200 ? line : line.substring(0, 200);
    }

    private static int skipBack(String text, int index) {
        int cursor = index;
        while (cursor > 0 && Character.isWhitespace(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private static boolean isIdent(char current) {
        return Character.isLetterOrDigit(current) || current == '_';
    }

    private record Stmt(int startLine, int endLine, int indent, String text) {
    }

    private record ClassFrame(int indent, String simple, String qualified) {
    }

    private static final class FunctionFrame {
        private final int indent;
        private final int startLine;
        private int endLine;
        private final String name;
        private final String className;
        private final String signature;
        private final boolean test;
        private final boolean constructor;
        private final List<String> annotations;
        private final List<ParsedCall> calls = new ArrayList<>();

        private FunctionFrame(
                int indent,
                int startLine,
                int endLine,
                String name,
                String className,
                String signature,
                boolean test,
                boolean constructor,
                List<String> annotations) {
            this.indent = indent;
            this.startLine = startLine;
            this.endLine = endLine;
            this.name = name;
            this.className = className;
            this.signature = signature;
            this.test = test;
            this.constructor = constructor;
            this.annotations = annotations;
        }

        private void absorb(Stmt statement) {
            endLine = Math.max(endLine, statement.endLine());
            calls.addAll(callsIn(statement.text()));
        }

        private ParsedFunction toFunction(String path) {
            String qualifiedSignature = className + "#" + signature;
            String id = path + "#" + qualifiedSignature;
            return new ParsedFunction(
                    id, name, qualifiedSignature, signature, className, test, constructor,
                    startLine, endLine, annotations, List.copyOf(calls));
        }
    }
}
