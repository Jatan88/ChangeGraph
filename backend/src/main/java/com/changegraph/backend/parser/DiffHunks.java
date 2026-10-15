package com.changegraph.backend.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DiffHunks {

    public record Hunk(int newStart, int newCount) {
    }

    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@", Pattern.MULTILINE);

    private DiffHunks() {
    }

    public static List<Hunk> parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return List.of();
        }
        List<Hunk> hunks = new ArrayList<>();
        Matcher matcher = HUNK.matcher(patch);
        while (matcher.find()) {
            int start = Integer.parseInt(matcher.group(1));
            int count = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            hunks.add(new Hunk(start, count));
        }
        return hunks;
    }

    public static boolean overlaps(int startLine, int endLine, List<Hunk> hunks) {
        for (Hunk hunk : hunks) {
            if (hunk.newCount() <= 0) {
                continue;
            }
            int hunkEnd = hunk.newStart() + hunk.newCount() - 1;
            if (startLine <= hunkEnd && endLine >= hunk.newStart()) {
                return true;
            }
        }
        return false;
    }
}
