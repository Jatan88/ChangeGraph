package com.changegraph.backend.parser;

import com.changegraph.backend.parser.JavaSourceParser.ParsedCall;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFile;
import com.changegraph.backend.parser.JavaSourceParser.ParsedFunction;
import com.changegraph.backend.parser.JavaSourceParser.ParsedService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ScriptSourceParser {

    private static final Set<String> SKIP_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "function", "import", "export", "return", "typeof",
            "instanceof", "void", "delete", "await", "new", "class", "do", "else", "try", "finally",
            "with", "case", "break", "continue", "throw", "yield", "var", "let", "const", "in", "of");
    private static final Set<String> SERVICE_DECORATORS = Set.of(
            "service", "component", "controller", "repository", "router", "injectable", "module", "restcontroller");
    private static final Set<String> JSX_KEYWORDS = Set.of(
            "return", "yield", "throw", "case", "else", "do", "await", "typeof", "void", "delete", "new");

    public ParsedFile parse(String path, String source) {
        String normalized = SourcePaths.normalizePath(path);
        if (source == null) {
            return new ParsedFile(normalized, "", List.of(), List.of(), List.of(), List.of("Source was not available."));
        }
        String module = moduleName(normalized);
        boolean[] code = mask(source);
        Scan scan = new Scan(normalized, module, source, code, looksLikeTestFile(normalized));
        scan.collectDeclarations();
        List<ParsedFunction> functions = new ArrayList<>();
        for (Span span : scan.spans) {
            functions.add(span.toFunction(normalized, source, code, scan.spans));
        }
        return new ParsedFile(normalized, module, scan.imports, functions, scan.services, List.of());
    }

    private static boolean looksLikeTestFile(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("/__tests__/")
                || normalized.contains(".test.")
                || normalized.contains(".spec.")
                || normalized.contains("/test/")
                || normalized.contains("/tests/");
    }

    private static String moduleName(String path) {
        int dot = path.lastIndexOf('.');
        String without = dot < 0 ? path : path.substring(0, dot);
        return without.replace('/', '.');
    }

    static boolean[] mask(String source) {
        int length = source.length();
        boolean[] code = new boolean[length];
        Deque<Mode> modes = new ArrayDeque<>();
        modes.push(Mode.CODE);
        int jsxDepth = 0;
        int exprBrace = 0;
        char quote = 0;
        char[] quoteHolder = new char[1];
        for (int i = 0; i < length; i++) {
            Mode mode = modes.peek();
            char current = source.charAt(i);
            if (mode == Mode.LINE_COMMENT) {
                code[i] = false;
                if (current == '\n') {
                    modes.pop();
                }
                continue;
            }
            if (mode == Mode.BLOCK_COMMENT) {
                code[i] = false;
                if (current == '*' && i + 1 < length && source.charAt(i + 1) == '/') {
                    code[i + 1] = false;
                    i++;
                    modes.pop();
                }
                continue;
            }
            if (mode == Mode.STRING || mode == Mode.TEMPLATE) {
                code[i] = false;
                if (current == '\\') {
                    if (i + 1 < length) {
                        code[i + 1] = false;
                        i++;
                    }
                    continue;
                }
                if (mode == Mode.TEMPLATE && current == '$' && i + 1 < length && source.charAt(i + 1) == '{') {
                    code[i + 1] = true;
                    i++;
                    modes.push(Mode.TEMPLATE_EXPR);
                    exprBrace = 1;
                    continue;
                }
                if (mode == Mode.STRING && current == quote) {
                    modes.pop();
                } else if (mode == Mode.TEMPLATE && current == '`') {
                    modes.pop();
                }
                continue;
            }
            if (mode == Mode.TEMPLATE_EXPR || mode == Mode.JSX_EXPR) {
                if (current == '{') {
                    exprBrace++;
                } else if (current == '}') {
                    exprBrace--;
                    if (exprBrace == 0) {
                        modes.pop();
                        code[i] = mode != Mode.JSX_EXPR;
                        continue;
                    }
                }
                if (startsLineComment(source, i) || startsBlockComment(source, i) || startsString(source, i) || startsRegex(source, code, i)) {
                    i = enterLiteral(source, code, modes, i, quoteHolder) - 1;
                    if (modes.peek() == Mode.STRING) {
                        quote = quoteHolder[0];
                    }
                    continue;
                }
                if (current == '<' && looksLikeJsx(source, code, i)) {
                    code[i] = false;
                    jsxDepth++;
                    modes.push(Mode.JSX_TAG);
                    continue;
                }
                code[i] = true;
                continue;
            }
            if (mode == Mode.JSX_TAG || mode == Mode.JSX_CHILD) {
                if (current == '{') {
                    code[i] = true;
                    modes.push(Mode.JSX_EXPR);
                    exprBrace = 1;
                    continue;
                }
                if (mode == Mode.JSX_CHILD && current == '<') {
                    code[i] = false;
                    if (!(i + 1 < length && source.charAt(i + 1) == '/')) {
                        jsxDepth++;
                    }
                    modes.pop();
                    modes.push(Mode.JSX_TAG);
                    continue;
                }
                code[i] = false;
                if (mode == Mode.JSX_TAG && current == '>') {
                    boolean closing = tagIsClosing(source, i);
                    boolean selfClosing = i > 0 && source.charAt(i - 1) == '/';
                    if (closing || selfClosing) {
                        jsxDepth = Math.max(0, jsxDepth - 1);
                    }
                    modes.pop();
                    if (jsxDepth > 0) {
                        modes.push(Mode.JSX_CHILD);
                    }
                }
                continue;
            }
            if (startsLineComment(source, i)) {
                code[i] = false;
                code[i + 1] = false;
                i++;
                modes.push(Mode.LINE_COMMENT);
                continue;
            }
            if (startsBlockComment(source, i)) {
                code[i] = false;
                code[i + 1] = false;
                i++;
                modes.push(Mode.BLOCK_COMMENT);
                continue;
            }
            if (startsString(source, i)) {
                code[i] = false;
                quote = current;
                modes.push(current == '`' ? Mode.TEMPLATE : Mode.STRING);
                continue;
            }
            if (startsRegex(source, code, i)) {
                i = consumeRegex(source, code, i) - 1;
                continue;
            }
            if (current == '<' && looksLikeJsx(source, code, i)) {
                code[i] = false;
                jsxDepth++;
                modes.push(Mode.JSX_TAG);
                continue;
            }
            code[i] = true;
        }
        return code;
    }

    private static int enterLiteral(String source, boolean[] code, Deque<Mode> modes, int index, char[] quoteHolder) {
        if (startsLineComment(source, index)) {
            code[index] = false;
            code[index + 1] = false;
            modes.push(Mode.LINE_COMMENT);
            return index + 2;
        }
        if (startsBlockComment(source, index)) {
            code[index] = false;
            code[index + 1] = false;
            modes.push(Mode.BLOCK_COMMENT);
            return index + 2;
        }
        code[index] = false;
        quoteHolder[0] = source.charAt(index);
        modes.push(quoteHolder[0] == '`' ? Mode.TEMPLATE : Mode.STRING);
        return index + 1;
    }

    private static boolean tagIsClosing(String source, int gt) {
        int cursor = gt - 1;
        while (cursor >= 0 && Character.isWhitespace(source.charAt(cursor))) {
            cursor--;
        }
        while (cursor >= 0 && source.charAt(cursor) != '<') {
            cursor--;
        }
        return cursor + 1 < source.length() && source.charAt(cursor) == '<' && source.charAt(cursor + 1) == '/';
    }

    private static boolean startsLineComment(String source, int index) {
        return index + 1 < source.length() && source.charAt(index) == '/' && source.charAt(index + 1) == '/';
    }

    private static boolean startsBlockComment(String source, int index) {
        return index + 1 < source.length() && source.charAt(index) == '/' && source.charAt(index + 1) == '*';
    }

    private static boolean startsString(String source, int index) {
        char current = source.charAt(index);
        return current == '\'' || current == '"' || current == '`';
    }

    private static boolean startsRegex(String source, boolean[] code, int index) {
        if (source.charAt(index) != '/' || startsLineComment(source, index) || startsBlockComment(source, index)) {
            return false;
        }
        String previous = previousToken(source, code, index);
        if (previous.isEmpty()) {
            return true;
        }
        if (previous.length() == 1 && "(=[{,;?:&|!~".indexOf(previous.charAt(0)) >= 0) {
            return true;
        }
        return JSX_KEYWORDS.contains(previous);
    }

    private static int consumeRegex(String source, boolean[] code, int index) {
        code[index] = false;
        boolean escaped = false;
        boolean inClass = false;
        int cursor = index + 1;
        while (cursor < source.length()) {
            char current = source.charAt(cursor);
            code[cursor] = false;
            if (!escaped && current == '\\') {
                escaped = true;
                cursor++;
                continue;
            }
            if (!escaped && current == '[') {
                inClass = true;
            } else if (!escaped && current == ']') {
                inClass = false;
            } else if (!escaped && current == '/' && !inClass) {
                cursor++;
                while (cursor < source.length() && Character.isLetter(source.charAt(cursor))) {
                    code[cursor] = false;
                    cursor++;
                }
                return cursor;
            }
            if (current == '\n') {
                return cursor;
            }
            escaped = false;
            cursor++;
        }
        return cursor;
    }

    private static boolean looksLikeJsx(String source, boolean[] code, int index) {
        String previous = previousToken(source, code, index);
        boolean allowed = previous.isEmpty()
                || (previous.length() == 1 && "(=[{,;?:&|!~)".indexOf(previous.charAt(0)) >= 0)
                || JSX_KEYWORDS.contains(previous);
        if (!allowed) {
            return false;
        }
        int cursor = index + 1;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= source.length()) {
            return false;
        }
        char next = source.charAt(cursor);
        if (next != '>' && next != '/' && !Character.isLetter(next) && next != '_') {
            return false;
        }
        int depth = 0;
        for (int i = index; i < source.length() && i < index + 300; i++) {
            char current = source.charAt(i);
            if (current == '<') {
                depth++;
            } else if (current == '>') {
                depth--;
                if (depth == 0) {
                    int after = i + 1;
                    while (after < source.length() && Character.isWhitespace(source.charAt(after))) {
                        after++;
                    }
                    return after >= source.length() || source.charAt(after) != '(';
                }
            } else if (depth == 1 && current == ',') {
                return false;
            } else if (depth == 1 && source.startsWith("extends", i)) {
                return false;
            }
        }
        return true;
    }

    private static String previousToken(String source, boolean[] code, int index) {
        int cursor = index - 1;
        while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
            cursor--;
        }
        if (cursor < 0) {
            return "";
        }
        char current = source.charAt(cursor);
        if (isIdent(current)) {
            int end = cursor + 1;
            while (cursor >= 0 && code[cursor] && isIdent(source.charAt(cursor))) {
                cursor--;
            }
            return source.substring(cursor + 1, end);
        }
        return String.valueOf(current);
    }

    private static boolean isIdent(char current) {
        return Character.isLetterOrDigit(current) || current == '_' || current == '$';
    }

    private enum Mode {
        CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, TEMPLATE, TEMPLATE_EXPR, JSX_TAG, JSX_CHILD, JSX_EXPR
    }

    private static final class Scan {
        private final String path;
        private final String module;
        private final String source;
        private final boolean[] code;
        private final boolean testFile;
        private final List<Span> spans = new ArrayList<>();
        private final List<ParsedService> services = new ArrayList<>();
        private final List<String> imports = new ArrayList<>();
        private final Set<Integer> paramParens = new HashSet<>();
        private final Deque<ClassScope> classes = new ArrayDeque<>();
        private final List<String> decorators = new ArrayList<>();
        private int index;
        private int brace;
        private boolean atStatement = true;

        private Scan(String path, String module, String source, boolean[] code, boolean testFile) {
            this.path = path;
            this.module = module;
            this.source = source;
            this.code = code;
            this.testFile = testFile;
        }

        private void collectDeclarations() {
            int length = source.length();
            while (index < length) {
                if (!code[index]) {
                    index++;
                    continue;
                }
                char current = source.charAt(index);
                if (Character.isWhitespace(current)) {
                    index++;
                    continue;
                }
                if (current == '{') {
                    brace++;
                    atStatement = true;
                    index++;
                    continue;
                }
                if (current == '}') {
                    brace = Math.max(0, brace - 1);
                    while (!classes.isEmpty() && classes.peek().memberDepth > brace) {
                        classes.pop();
                    }
                    atStatement = true;
                    index++;
                    continue;
                }
                if (current == ';') {
                    atStatement = true;
                    index++;
                    continue;
                }
                if (!atStatement) {
                    index++;
                    continue;
                }
                if (keyword("export") || keyword("default") || keyword("declare") || keyword("abstract")) {
                    index += wordAt().length();
                    continue;
                }
                if (keyword("import")) {
                    readImport();
                    atStatement = false;
                    continue;
                }
                if (keyword("interface") || keyword("enum")) {
                    skipBraced();
                    atStatement = true;
                    continue;
                }
                if (keyword("type") && hasWordBoundary("type")) {
                    skipTypeAlias();
                    atStatement = true;
                    continue;
                }
                if (keyword("class")) {
                    readClass();
                    continue;
                }
                if (keyword("function")) {
                    int next = readFunction(index + "function".length(), null);
                    if (next > index) {
                        index = next;
                        atStatement = true;
                        continue;
                    }
                }
                if (keyword("const") || keyword("let") || keyword("var")) {
                    int next = readAssigned(index);
                    if (next > index) {
                        index = next;
                        atStatement = true;
                        continue;
                    }
                }
                if (current == '@') {
                    decorators.add(readDecorator());
                    atStatement = true;
                    continue;
                }
                if (inClassMembers() && (isIdent(current) || keyword("get") || keyword("set") || keyword("async")
                        || keyword("static") || keyword("public") || keyword("private") || keyword("protected")
                        || keyword("readonly") || keyword("override"))) {
                    int next = readMember();
                    if (next > index) {
                        index = next;
                        atStatement = true;
                        continue;
                    }
                }
                atStatement = false;
                index++;
            }
            registerTests();
        }

        private void registerTests() {
            for (int paren = 0; paren < source.length(); paren++) {
                if (!code[paren] || source.charAt(paren) != '(') {
                    continue;
                }
                Callee callee = calleeBefore(source, code, paren);
                if (callee == null || !isTestCallee(callee.name(), paren)) {
                    continue;
                }
                registerCallback(paren);
            }
        }

        private boolean isTestCallee(String name, int paren) {
            if (name.equals("test") || name.equals("it") || name.equals("specify")) {
                return true;
            }
            if (!name.equals("only") && !name.equals("skip") && !name.equals("todo")) {
                return false;
            }
            int cursor = paren - 1;
            while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
                cursor--;
            }
            while (cursor >= 0 && code[cursor] && isIdent(source.charAt(cursor))) {
                cursor--;
            }
            while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
                cursor--;
            }
            if (cursor < 0 || source.charAt(cursor) != '.') {
                return false;
            }
            cursor--;
            while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
                cursor--;
            }
            int end = cursor + 1;
            while (cursor >= 0 && code[cursor] && isIdent(source.charAt(cursor))) {
                cursor--;
            }
            String receiver = source.substring(cursor + 1, end);
            return receiver.equals("test") || receiver.equals("it") || receiver.equals("specify");
        }

        private void registerCallback(int paren) {
            int close = matchingParen(paren);
            if (close < 0) {
                return;
            }
            String title = "test";
            int arrow = -1;
            int functionKeyword = -1;
            int depth = 0;
            for (int cursor = paren; cursor <= close; cursor++) {
                if (code[cursor]) {
                    char current = source.charAt(cursor);
                    if (current == '(') {
                        depth++;
                    } else if (current == ')') {
                        depth--;
                    } else if (depth == 1 && current == '=' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '>') {
                        arrow = cursor;
                    } else if (depth == 1 && keywordAt(cursor, "function")) {
                        functionKeyword = cursor;
                    }
                    continue;
                }
                if (depth == 1 && title.equals("test") && (source.charAt(cursor) == '\'' || source.charAt(cursor) == '"' || source.charAt(cursor) == '`')) {
                    String literal = readLiteral(cursor);
                    if (literal != null && !literal.isBlank()) {
                        title = literal.replaceAll("\\s+", " ").strip();
                        if (title.length() > 80) {
                            title = title.substring(0, 80);
                        }
                    }
                }
            }
            int savedIndex = index;
            int savedBrace = brace;
            int before = spans.size();
            if (functionKeyword >= 0) {
                index = functionKeyword;
                readFunction(functionKeyword + "function".length(), title);
            } else if (arrow >= 0) {
                int params = parameterStartBeforeArrow(arrow, paren);
                if (params >= 0) {
                    index = params;
                    readArrow(title, params, module, true);
                }
            }
            index = savedIndex;
            brace = savedBrace;
            if (spans.size() > before) {
                spans.get(spans.size() - 1).test = true;
            }
        }

        private int parameterStartBeforeArrow(int arrow, int paren) {
            int cursor = arrow - 1;
            while (cursor > paren && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
                cursor--;
            }
            if (cursor <= paren) {
                return -1;
            }
            if (isIdent(source.charAt(cursor))) {
                while (cursor > paren && code[cursor - 1] && isIdent(source.charAt(cursor - 1))) {
                    cursor--;
                }
                return cursor;
            }
            if (source.charAt(cursor) != ')') {
                return -1;
            }
            int depth = 0;
            for (int i = cursor; i > paren; i--) {
                if (!code[i]) {
                    continue;
                }
                if (source.charAt(i) == ')') {
                    depth++;
                } else if (source.charAt(i) == '(') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private String readLiteral(int quoteIndex) {
            char quote = source.charAt(quoteIndex);
            StringBuilder literal = new StringBuilder();
            for (int i = quoteIndex + 1; i < source.length(); i++) {
                char current = source.charAt(i);
                if (current == '\\') {
                    i++;
                    continue;
                }
                if (current == quote) {
                    return literal.toString();
                }
                literal.append(current);
            }
            return null;
        }

        private void readClass() {
            int cursor = index + "class".length();
            cursor = skipSpace(cursor);
            String simple = readIdentifier(cursor);
            if (simple.isEmpty()) {
                index += "class".length();
                atStatement = false;
                return;
            }
            cursor += simple.length();
            int open = findBrace(cursor);
            if (open < 0) {
                index = cursor;
                atStatement = false;
                return;
            }
            String basis = serviceBasis(decorators);
            String qualified = module + "." + simple;
            if (basis != null) {
                services.add(new ParsedService(qualified, simple, basis));
            }
            decorators.clear();
            classes.push(new ClassScope(simple, qualified, brace + 1));
            brace++;
            index = open + 1;
            atStatement = true;
        }

        private int readFunction(int nameIndex, String forcedName) {
            int cursor = skipSpace(nameIndex);
            boolean generator = cursor < source.length() && source.charAt(cursor) == '*';
            if (generator) {
                cursor = skipSpace(cursor + 1);
            }
            String name = forcedName;
            if (name == null && cursor < source.length() && isIdent(source.charAt(cursor))) {
                name = readIdentifier(cursor);
                cursor += name.length();
            }
            if (name == null || name.isEmpty()) {
                return index;
            }
            cursor = skipSpace(cursor);
            cursor = skipGenerics(cursor);
            if (cursor >= source.length() || source.charAt(cursor) != '(') {
                return index;
            }
            int close = matchingParen(cursor);
            if (close < 0) {
                return index;
            }
            List<String> parameters = parameterNames(source.substring(cursor + 1, close));
            int body = findBody(close + 1);
            if (body < 0 || source.charAt(body) != '{') {
                return close + 1;
            }
            int end = matchingBrace(body);
            addSpan(name, className(), signature(name, parameters), false, lineOf(cursor), lineOf(end), body + 1, end, cursor);
            brace++;
            return body + 1;
        }

        private int readAssigned(int start) {
            int cursor = start;
            if (keywordAt(cursor, "const")) {
                cursor += "const".length();
            } else if (keywordAt(cursor, "let")) {
                cursor += "let".length();
            } else if (keywordAt(cursor, "var")) {
                cursor += "var".length();
            } else {
                return start;
            }
            cursor = skipSpace(cursor);
            if (cursor >= source.length() || !isIdent(source.charAt(cursor))) {
                return start;
            }
            String name = readIdentifier(cursor);
            cursor = skipSpace(cursor + name.length());
            if (cursor >= source.length() || source.charAt(cursor) != '=') {
                return start;
            }
            cursor = skipSpace(cursor + 1);
            if (keywordAt(cursor, "async")) {
                cursor = skipSpace(cursor + "async".length());
            }
            if (keywordAt(cursor, "function")) {
                int next = readFunction(cursor + "function".length(), name);
                return next > cursor ? next : start;
            }
            return readArrow(name, cursor, className(), false);
        }

        private int readMember() {
            int cursor = index;
            while (isModifier(cursor)) {
                cursor = skipSpace(cursor + wordLength(cursor));
            }
            if (keywordAt(cursor, "get") || keywordAt(cursor, "set") || keywordAt(cursor, "async")) {
                int next = skipSpace(cursor + wordLength(cursor));
                if (next < source.length() && isIdent(source.charAt(next))) {
                    cursor = next;
                }
            }
            if (cursor >= source.length() || !isIdent(source.charAt(cursor))) {
                return index;
            }
            String name = readIdentifier(cursor);
            int afterName = skipSpace(cursor + name.length());
            if (afterName < source.length() && source.charAt(afterName) == '=') {
                int value = skipSpace(afterName + 1);
                if (keywordAt(value, "async")) {
                    value = skipSpace(value + "async".length());
                }
                ClassScope owner = classes.peek();
                return readArrow(name, value, owner == null ? module : owner.qualified, false);
            }
            afterName = skipGenerics(afterName);
            if (afterName >= source.length() || source.charAt(afterName) != '(') {
                return index;
            }
            int close = matchingParen(afterName);
            if (close < 0) {
                return index;
            }
            int body = findBody(close + 1);
            if (body < 0 || source.charAt(body) != '{') {
                return index;
            }
            boolean constructor = "constructor".equals(name);
            ClassScope owner = classes.peek();
            String ownerName = owner == null ? module : owner.qualified;
            String simple = owner == null ? name : owner.simple;
            String signatureName = constructor ? simple : name;
            List<String> parameters = parameterNames(source.substring(afterName + 1, close));
            int end = matchingBrace(body);
            addSpan(constructor ? "<init>" : name, ownerName, signature(signatureName, parameters), constructor,
                    lineOf(cursor), lineOf(end), body + 1, end, afterName);
            brace++;
            return body + 1;
        }

        private int readArrow(String name, int cursor, String className, boolean test) {
            int start = cursor;
            List<String> parameters;
            if (cursor < source.length() && source.charAt(cursor) == '(') {
                int close = matchingParen(cursor);
                if (close < 0) {
                    return index;
                }
                parameters = parameterNames(source.substring(cursor + 1, close));
                cursor = skipSpace(close + 1);
            } else if (cursor < source.length() && isIdent(source.charAt(cursor))) {
                String only = readIdentifier(cursor);
                parameters = List.of(only);
                cursor = skipSpace(cursor + only.length());
            } else {
                return index;
            }
            if (cursor + 1 >= source.length() || source.charAt(cursor) != '=' || source.charAt(cursor + 1) != '>') {
                return index;
            }
            cursor = skipSpace(cursor + 2);
            if (cursor < source.length() && source.charAt(cursor) == '{') {
                int end = matchingBrace(cursor);
                addSpan(name, className, signature(name, parameters), false, lineOf(start), lineOf(end), cursor + 1, end,
                        start < source.length() && source.charAt(start) == '(' ? start : -1);
                spans.get(spans.size() - 1).test = test || spans.get(spans.size() - 1).test;
                brace++;
                return cursor + 1;
            }
            int end = expressionEnd(cursor);
            addSpan(name, className, signature(name, parameters), false, lineOf(start), lineOf(Math.max(start, end - 1)),
                    cursor, end, start < source.length() && source.charAt(start) == '(' ? start : -1);
            spans.get(spans.size() - 1).test = test || spans.get(spans.size() - 1).test;
            return end;
        }

        private void addSpan(
                String name,
                String className,
                String signature,
                boolean constructor,
                int startLine,
                int endLine,
                int bodyStart,
                int bodyEnd,
                int paramParen) {
            boolean test = constructor ? false : isTestName(name);
            spans.add(new Span(name, className, signature, test, constructor, startLine, endLine, bodyStart, bodyEnd));
            if (paramParen >= 0) {
                paramParens.add(paramParen);
            }
            decorators.clear();
        }

        private boolean isTestName(String name) {
            return name.startsWith("test") || name.startsWith("it ") || (testFile && name.toLowerCase(Locale.ROOT).startsWith("test"));
        }

        private String className() {
            return classes.isEmpty() ? module : classes.peek().qualified;
        }

        private boolean inClassMembers() {
            return !classes.isEmpty() && brace == classes.peek().memberDepth;
        }

        private void readImport() {
            int start = index;
            while (index < source.length()) {
                if (code[index] && (source.charAt(index) == ';' || source.charAt(index) == '\n')) {
                    break;
                }
                index++;
            }
            if (imports.size() < 40) {
                String text = source.substring(start, Math.min(index, start + 200)).replaceAll("\\s+", " ").strip();
                imports.add(text);
            }
        }

        private void skipBraced() {
            int open = findBrace(index);
            if (open < 0) {
                index += wordAt().length();
                return;
            }
            index = matchingBrace(open) + 1;
        }

        private void skipTypeAlias() {
            int depth = 0;
            index += "type".length();
            while (index < source.length()) {
                if (!code[index]) {
                    index++;
                    continue;
                }
                char current = source.charAt(index);
                if (current == '{' || current == '(' || current == '[') {
                    depth++;
                } else if (current == '}' || current == ')' || current == ']') {
                    depth = Math.max(0, depth - 1);
                } else if (current == ';' && depth == 0) {
                    index++;
                    return;
                } else if (current == '\n' && depth == 0) {
                    return;
                }
                index++;
            }
        }

        private String readDecorator() {
            int cursor = skipSpace(index + 1);
            String name = readIdentifier(cursor);
            int dot = cursor + name.length();
            while (dot < source.length() && code[dot] && source.charAt(dot) == '.') {
                dot++;
                String next = readIdentifier(dot);
                if (next.isEmpty()) {
                    break;
                }
                name = next;
                dot += next.length();
            }
            index = dot;
            if (index < source.length() && code[index] && source.charAt(index) == '(') {
                int close = matchingParen(index);
                index = close < 0 ? index + 1 : close + 1;
            }
            return name;
        }

        private int findBrace(int cursor) {
            int depth = 0;
            for (int i = cursor; i < source.length(); i++) {
                if (!code[i]) {
                    continue;
                }
                char current = source.charAt(i);
                if (current == '{') {
                    if (depth == 0) {
                        return i;
                    }
                    depth++;
                } else if (current == '}' && depth > 0) {
                    depth--;
                } else if (current == '(' || current == '[') {
                    depth++;
                } else if ((current == ')' || current == ']') && depth > 0) {
                    depth--;
                }
            }
            return -1;
        }

        private int findBody(int cursor) {
            int depth = 0;
            for (int i = cursor; i < source.length(); i++) {
                if (!code[i]) {
                    continue;
                }
                char current = source.charAt(i);
                if (current == '{' && depth == 0) {
                    return i;
                }
                if (current == ';' && depth == 0) {
                    return -1;
                }
                if (current == '(' || current == '[') {
                    depth++;
                } else if (current == ')' || current == ']') {
                    depth = Math.max(0, depth - 1);
                }
            }
            return -1;
        }

        private int expressionEnd(int cursor) {
            int paren = 0;
            int bracket = 0;
            int braceDepth = 0;
            int i = cursor;
            while (i < source.length()) {
                if (!code[i]) {
                    i++;
                    continue;
                }
                char current = source.charAt(i);
                if (current == '(') {
                    paren++;
                } else if (current == ')') {
                    if (paren == 0 && bracket == 0 && braceDepth == 0) {
                        return i;
                    }
                    paren = Math.max(0, paren - 1);
                } else if (current == '[') {
                    bracket++;
                } else if (current == ']') {
                    bracket = Math.max(0, bracket - 1);
                } else if (current == '{') {
                    braceDepth++;
                } else if (current == '}') {
                    if (braceDepth == 0 && paren == 0 && bracket == 0) {
                        return i;
                    }
                    braceDepth = Math.max(0, braceDepth - 1);
                } else if ((current == ',' || current == ';') && paren == 0 && bracket == 0 && braceDepth == 0) {
                    return i;
                } else if (current == '\n' && paren == 0 && bracket == 0 && braceDepth == 0) {
                    return i;
                }
                i++;
            }
            return i;
        }

        private int matchingParen(int open) {
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                if (!code[i]) {
                    continue;
                }
                char current = source.charAt(i);
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

        private int matchingBrace(int open) {
            int depth = 0;
            for (int i = open; i < source.length(); i++) {
                if (!code[i]) {
                    continue;
                }
                if (source.charAt(i) == '{') {
                    depth++;
                } else if (source.charAt(i) == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return source.length() - 1;
        }

        private int skipGenerics(int cursor) {
            int start = skipSpace(cursor);
            if (start >= source.length() || !code[start] || source.charAt(start) != '<') {
                return cursor;
            }
            int depth = 0;
            for (int i = start; i < source.length(); i++) {
                if (!code[i]) {
                    continue;
                }
                char current = source.charAt(i);
                if (current == '<') {
                    depth++;
                } else if (current == '>') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                } else if (current == '{' || current == ';' || current == '=') {
                    return cursor;
                }
            }
            return cursor;
        }

        private List<String> parameterNames(String params) {
            List<String> names = new ArrayList<>();
            for (String piece : splitParams(params)) {
                String name = parameterName(piece.strip());
                if (!name.isEmpty() && !name.equals("this")) {
                    names.add(name);
                }
            }
            return names;
        }

        private List<String> splitParams(String params) {
            List<String> pieces = new ArrayList<>();
            int depth = 0;
            int start = 0;
            for (int i = 0; i < params.length(); i++) {
                char current = params.charAt(i);
                if (current == '(' || current == '[' || current == '{' || current == '<') {
                    depth++;
                } else if (current == ')' || current == ']' || current == '}' || current == '>') {
                    depth = Math.max(0, depth - 1);
                } else if (current == ',' && depth == 0) {
                    pieces.add(params.substring(start, i));
                    start = i + 1;
                }
            }
            if (start <= params.length()) {
                pieces.add(params.substring(start));
            }
            return pieces;
        }

        private String parameterName(String raw) {
            if (raw.isEmpty()) {
                return "";
            }
            String stripped = raw.replaceFirst("^(public|private|protected|readonly|override)\\s+", "");
            stripped = stripped.replaceFirst("^(public|private|protected|readonly|override)\\s+", "");
            stripped = stripped.replaceFirst("^\\.\\.\\.", "");
            if (stripped.startsWith("{") || stripped.startsWith("[")) {
                return "arg";
            }
            int depth = 0;
            int cut = stripped.length();
            for (int i = 0; i < stripped.length(); i++) {
                char current = stripped.charAt(i);
                if (current == '(' || current == '[' || current == '{' || current == '<') {
                    depth++;
                } else if (current == ')' || current == ']' || current == '}' || current == '>') {
                    depth = Math.max(0, depth - 1);
                } else if (depth == 0 && (current == ':' || current == '=' || current == '?')) {
                    cut = i;
                    break;
                }
            }
            String left = stripped.substring(0, cut).strip();
            int start = left.length();
            while (start > 0 && isIdent(left.charAt(start - 1))) {
                start--;
            }
            return left.substring(start);
        }

        private int skipSpace(int cursor) {
            int i = cursor;
            while (i < source.length() && (!code[i] || Character.isWhitespace(source.charAt(i)))) {
                i++;
            }
            return i;
        }

        private String readIdentifier(int cursor) {
            if (cursor >= source.length() || !code[cursor] || !isIdent(source.charAt(cursor)) || Character.isDigit(source.charAt(cursor))) {
                return "";
            }
            int end = cursor + 1;
            while (end < source.length() && code[end] && isIdent(source.charAt(end))) {
                end++;
            }
            return source.substring(cursor, end);
        }

        private boolean keyword(String word) {
            return keywordAt(index, word);
        }

        private boolean keywordAt(int cursor, String word) {
            if (cursor < 0 || cursor + word.length() > source.length() || !source.startsWith(word, cursor)) {
                return false;
            }
            if (!code[cursor]) {
                return false;
            }
            if (cursor > 0 && code[cursor - 1] && isIdent(source.charAt(cursor - 1))) {
                return false;
            }
            int end = cursor + word.length();
            return end >= source.length() || !code[end] || !isIdent(source.charAt(end));
        }

        private boolean hasWordBoundary(String word) {
            return keywordAt(index, word);
        }

        private String wordAt() {
            return readIdentifier(index);
        }

        private int wordLength(int cursor) {
            String word = readIdentifier(cursor);
            return word.isEmpty() ? 1 : word.length();
        }

        private boolean isModifier(int cursor) {
            return keywordAt(cursor, "public") || keywordAt(cursor, "private") || keywordAt(cursor, "protected")
                    || keywordAt(cursor, "readonly") || keywordAt(cursor, "override") || keywordAt(cursor, "static")
                    || keywordAt(cursor, "async") || keywordAt(cursor, "abstract");
        }

        private int lineOf(int offset) {
            int line = 1;
            int end = Math.min(Math.max(offset, 0), source.length());
            for (int i = 0; i < end; i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    private static String serviceBasis(List<String> decorators) {
        for (String decorator : decorators) {
            if (SERVICE_DECORATORS.contains(decorator.toLowerCase(Locale.ROOT))) {
                return decorator;
            }
        }
        return null;
    }

    private static String signature(String name, List<String> parameters) {
        return name + "(" + String.join(",", parameters) + ")";
    }

    private static final class ClassScope {
        private final String simple;
        private final String qualified;
        private final int memberDepth;

        private ClassScope(String simple, String qualified, int memberDepth) {
            this.simple = simple;
            this.qualified = qualified;
            this.memberDepth = memberDepth;
        }
    }

    private static final class Span {
        private final String name;
        private final String className;
        private final String signature;
        private boolean test;
        private final boolean constructor;
        private final int startLine;
        private final int endLine;
        private final int bodyStart;
        private final int bodyEnd;

        private Span(
                String name,
                String className,
                String signature,
                boolean test,
                boolean constructor,
                int startLine,
                int endLine,
                int bodyStart,
                int bodyEnd) {
            this.name = name;
            this.className = className;
            this.signature = signature;
            this.test = test;
            this.constructor = constructor;
            this.startLine = startLine;
            this.endLine = endLine;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyEnd;
        }

        private ParsedFunction toFunction(String ownerPath, String source, boolean[] code, List<Span> spans) {
            List<ParsedCall> calls = callsIn(source, code, spans);
            String qualifiedSignature = className + "#" + signature;
            String id = ownerPath + "#" + qualifiedSignature;
            return new ParsedFunction(id, name, qualifiedSignature, signature, className, test, constructor, startLine, endLine, List.of(), calls);
        }

        private List<ParsedCall> callsIn(String source, boolean[] code, List<Span> spans) {
            List<ParsedCall> calls = new ArrayList<>();
            for (int i = bodyStart; i < bodyEnd && i < source.length(); i++) {
                if (!code[i] || source.charAt(i) != '(') {
                    continue;
                }
                if (insideSmallerSpan(spans, i)) {
                    continue;
                }
                Callee callee = calleeBefore(source, code, i);
                if (callee == null || SKIP_KEYWORDS.contains(callee.name())) {
                    continue;
                }
                calls.add(new ParsedCall(callee.name(), argumentCount(source, code, i), callee.kind()));
            }
            return calls;
        }

        private boolean insideSmallerSpan(List<Span> spans, int position) {
            int size = bodyEnd - bodyStart;
            for (Span other : spans) {
                if (other == this) {
                    continue;
                }
                int otherSize = other.bodyEnd - other.bodyStart;
                if (otherSize < size && position >= other.bodyStart && position < other.bodyEnd) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Callee(String name, String kind) {
    }

    private static Callee calleeBefore(String source, boolean[] code, int paren) {
        int cursor = paren - 1;
        while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
            cursor--;
        }
        if (cursor < 0) {
            return null;
        }
        if (source.charAt(cursor) == '>') {
            int depth = 0;
            while (cursor >= 0) {
                if (code[cursor]) {
                    char current = source.charAt(cursor);
                    if (current == '>') {
                        depth++;
                    } else if (current == '<') {
                        depth--;
                        if (depth == 0) {
                            cursor--;
                            break;
                        }
                    }
                }
                cursor--;
            }
            while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
                cursor--;
            }
        }
        if (cursor < 0 || !isIdent(source.charAt(cursor))) {
            return null;
        }
        int end = cursor + 1;
        while (cursor >= 0 && code[cursor] && isIdent(source.charAt(cursor))) {
            cursor--;
        }
        String name = source.substring(cursor + 1, end);
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            return null;
        }
        boolean constructed = keywordImmediatelyBefore(source, code, cursor + 1, "new");
        String kind = constructed ? "CONSTRUCTOR" : "METHOD";
        return new Callee(name, kind);
    }

    private static boolean keywordImmediatelyBefore(String source, boolean[] code, int nameStart, String word) {
        int cursor = nameStart - 1;
        while (cursor >= 0 && (!code[cursor] || Character.isWhitespace(source.charAt(cursor)))) {
            cursor--;
        }
        int start = cursor - word.length() + 1;
        if (start < 0 || !source.startsWith(word, start)) {
            return false;
        }
        if (start > 0 && code[start - 1] && isIdent(source.charAt(start - 1))) {
            return false;
        }
        int end = start + word.length();
        return end >= source.length() || !code[end] || !isIdent(source.charAt(end));
    }

    private static int argumentCount(String source, boolean[] code, int openParen) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = openParen; i < source.length(); i++) {
            if (!code[i]) {
                char hidden = source.charAt(i);
                if (depth == 1 && (hidden == '\'' || hidden == '"' || hidden == '`')) {
                    any = true;
                }
                continue;
            }
            char current = source.charAt(i);
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
}
