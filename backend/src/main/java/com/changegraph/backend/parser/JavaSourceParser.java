package com.changegraph.backend.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class JavaSourceParser {

    public record ParsedCall(String calleeName, int argumentCount, String kind) {
    }

    public record ParsedFunction(
            String id,
            String name,
            String qualifiedSignature,
            String signature,
            String className,
            boolean test,
            boolean constructor,
            int startLine,
            int endLine,
            List<String> annotations,
            List<ParsedCall> calls) {
    }

    public record ParsedService(String id, String name, String basis) {
    }

    public record ParsedFile(
            String path,
            String packageName,
            List<String> imports,
            List<ParsedFunction> functions,
            List<ParsedService> services,
            List<String> errors) {
    }

    public ParsedFile parse(String path, String source) {
        String normalized = normalizePath(path);
        if (source == null) {
            return failed(normalized, "Source was not available.");
        }
        ParseResult<CompilationUnit> result = new JavaParser().parse(source);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String message = result.getProblems().stream()
                    .map(problem -> problem.getMessage())
                    .findFirst()
                    .orElse("The file could not be parsed.");
            return failed(normalized, safeProblem(message));
        }
        CompilationUnit unit = result.getResult().get();
        String packageName = unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString()).orElse("");
        List<String> imports = unit.getImports().stream()
                .map(imp -> imp.getNameAsString())
                .limit(40)
                .toList();
        List<ParsedFunction> functions = new ArrayList<>();
        List<ParsedService> services = new ArrayList<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String qualifiedClass = qualifiedClassName(packageName, type);
            String basis = serviceBasis(type);
            if (basis != null) {
                services.add(new ParsedService(qualifiedClass, type.getNameAsString(), basis));
            }
            boolean typeLooksLikeTest = looksLikeTest(normalized, type.getNameAsString());
            for (MethodDeclaration method : type.getMethods()) {
                functions.add(toFunction(normalized, qualifiedClass, type, method, false, typeLooksLikeTest));
            }
            for (ConstructorDeclaration constructor : type.getConstructors()) {
                functions.add(toFunction(normalized, qualifiedClass, type, constructor, true, typeLooksLikeTest));
            }
        }
        return new ParsedFile(normalized, packageName, imports, functions, services, List.of());
    }

    private ParsedFunction toFunction(
            String path,
            String qualifiedClass,
            TypeDeclaration<?> type,
            CallableDeclaration<?> callable,
            boolean constructor,
            boolean typeLooksLikeTest) {
        String name = constructor ? "<init>" : callable.getNameAsString();
        String parameters = parameterList(callable);
        String signature = (constructor ? type.getNameAsString() : callable.getNameAsString()) + "(" + parameters + ")";
        String qualifiedSignature = qualifiedClass + "#" + signature;
        String id = path + "#" + qualifiedSignature;
        List<String> annotations = callable.getAnnotations().stream().map(this::annotationName).toList();
        boolean test = typeLooksLikeTest || annotations.stream().anyMatch(ann -> ann.equals("Test") || ann.endsWith(".Test"));
        int start = callable.getRange().map(range -> range.begin.line).orElse(1);
        int end = callable.getRange().map(range -> range.end.line).orElse(start);
        List<ParsedCall> calls = new ArrayList<>();
        callable.findAll(MethodCallExpr.class).stream()
                .filter(call -> ownedBy(call, type))
                .forEach(call -> calls.add(new ParsedCall(call.getNameAsString(), call.getArguments().size(), "METHOD")));
        callable.findAll(ObjectCreationExpr.class).stream()
                .filter(call -> ownedBy(call, type))
                .forEach(call -> calls.add(new ParsedCall(call.getType().getNameAsString(), call.getArguments().size(), "CONSTRUCTOR")));
        return new ParsedFunction(id, name, qualifiedSignature, signature, qualifiedClass, test, constructor, start, end, annotations, calls);
    }

    private static boolean ownedBy(Node node, TypeDeclaration<?> owner) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            if (parent.get() instanceof TypeDeclaration<?> type) {
                return type == owner;
            }
            parent = parent.get().getParentNode();
        }
        return false;
    }

    private static String parameterList(CallableDeclaration<?> callable) {
        List<String> types = new ArrayList<>();
        for (Parameter parameter : callable.getParameters()) {
            types.add(parameter.getType().asString().replace(" ", ""));
        }
        return String.join(",", types);
    }

    private static String qualifiedClassName(String packageName, TypeDeclaration<?> type) {
        String name = type.getNameAsString();
        Optional<Node> parent = type.getParentNode();
        while (parent.isPresent() && parent.get() instanceof TypeDeclaration<?> enclosing) {
            name = enclosing.getNameAsString() + "." + name;
            parent = enclosing.getParentNode();
        }
        return packageName.isBlank() ? name : packageName + "." + name;
    }

    private String serviceBasis(TypeDeclaration<?> type) {
        for (AnnotationExpr annotation : type.getAnnotations()) {
            String name = annotationName(annotation);
            if (name.equals("Service") || name.equals("Component") || name.equals("RestController")
                    || name.equals("Controller") || name.equals("Repository")) {
                return name;
            }
        }
        return null;
    }

    private String annotationName(AnnotationExpr annotation) {
        return annotation.getName().getIdentifier();
    }

    private static boolean looksLikeTest(String path, String className) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("/src/test/")
                || className.endsWith("Test")
                || className.endsWith("Tests")
                || className.endsWith("IT");
    }

    public static String normalizePath(String path) {
        return SourcePaths.normalizePath(path);
    }

    public static boolean isSafePath(String path) {
        return SourcePaths.isSafePath(path);
    }

    private static ParsedFile failed(String path, String error) {
        return new ParsedFile(path, "", List.of(), List.of(), List.of(), List.of(error));
    }

    private static String safeProblem(String message) {
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180);
    }
}
