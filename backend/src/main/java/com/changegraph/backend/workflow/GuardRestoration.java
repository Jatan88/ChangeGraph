package com.changegraph.backend.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Puts a removed early-return guard back into a reviewed file when the commit diff shows exactly where it was deleted.
 */
final class GuardRestoration {

    private static final Pattern IF_WORD = Pattern.compile("\\bif\\b");
    private static final Pattern RETURN_WORD = Pattern.compile("\\breturn\\b");
    private static final int MAX_DELETED_LINES = 12;

    private GuardRestoration() {
    }

    static String restore(String current, String patch) {
        if (current == null || current.isBlank() || patch == null || patch.isBlank()) {
            return null;
        }
        String updated = current;
        boolean changed = false;
        for (Hunk hunk : hunks(patch)) {
            if (!removesGuard(hunk) || hunk.newSide().isEmpty()) {
                continue;
            }
            String next = apply(updated, hunk);
            if (next == null) {
                return null;
            }
            if (!next.equals(updated)) {
                updated = next;
                changed = true;
            }
        }
        return changed ? updated : null;
    }

    private static String apply(String current, Hunk hunk) {
        List<String> lines = split(current);
        int at = uniqueIndex(lines, hunk.newSide());
        if (at < 0) {
            return null;
        }
        int insertAt = at + hunk.insertAt();
        if (startsWith(lines, insertAt, hunk.deleted())) {
            return current;
        }
        List<String> next = new ArrayList<>();
        next.addAll(lines.subList(0, insertAt));
        next.addAll(hunk.deleted());
        next.addAll(lines.subList(insertAt, lines.size()));
        return String.join("\n", next);
    }

    private static boolean removesGuard(Hunk hunk) {
        if (hunk.deleted().isEmpty() || hunk.deleted().size() > MAX_DELETED_LINES) {
            return false;
        }
        StringBuilder code = new StringBuilder();
        for (String line : hunk.deleted()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            code.append(line).append('\n');
        }
        String body = code.toString();
        return IF_WORD.matcher(body).find() && RETURN_WORD.matcher(body).find();
    }

    private static List<Hunk> hunks(String patch) {
        List<Hunk> hunks = new ArrayList<>();
        String[] rawLines = patch.split("\n", -1);
        int index = 0;
        while (index < rawLines.length) {
            if (!rawLines[index].startsWith("@@")) {
                index++;
                continue;
            }
            index++;
            List<String> newSide = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            int insertAt = -1;
            while (index < rawLines.length && !rawLines[index].startsWith("@@")) {
                String raw = rawLines[index++];
                if (raw.startsWith("\\")) {
                    continue;
                }
                if (raw.startsWith("-")) {
                    if (insertAt < 0) {
                        insertAt = newSide.size();
                    }
                    deleted.add(raw.substring(1));
                } else if (raw.startsWith("+")) {
                    newSide.add(raw.substring(1));
                } else if (raw.startsWith(" ")) {
                    newSide.add(raw.substring(1));
                }
            }
            if (insertAt < 0) {
                insertAt = newSide.size();
            }
            hunks.add(new Hunk(newSide, deleted, insertAt));
        }
        return hunks;
    }

    private static int uniqueIndex(List<String> lines, List<String> sequence) {
        if (sequence.isEmpty() || sequence.size() > lines.size()) {
            return -1;
        }
        int found = -1;
        for (int start = 0; start <= lines.size() - sequence.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.size(); offset++) {
                if (!lines.get(start + offset).equals(sequence.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (found >= 0) {
                    return -1;
                }
                found = start;
            }
        }
        return found;
    }

    private static boolean startsWith(List<String> lines, int at, List<String> prefix) {
        if (at < 0 || at + prefix.size() > lines.size()) {
            return false;
        }
        for (int offset = 0; offset < prefix.size(); offset++) {
            if (!lines.get(at + offset).equals(prefix.get(offset))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> split(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    private record Hunk(List<String> newSide, List<String> deleted, int insertAt) {
    }
}
