package com.epam.aidial.evaluation.architectural;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LoggingConventionTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final String SUPPRESSION_MARKER = "@AllowsLogWithoutException";

    // Matches `catch ( <Type1>[ | <Type2>...] <var> ) {`. Captures the var name.
    private static final Pattern CATCH_DECL =
            Pattern.compile("\\bcatch\\s*\\(\\s*[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)\\s*\\{");

    // Matches `log.warn(`, `log.error(`, `log.debug(`, `log.trace(`. info is excluded:
    // info-level logs are for normal-flow narration where a stacktrace would be noise.
    private static final Pattern LOG_CALL_START = Pattern.compile("\\blog\\.(warn|error|debug|trace)\\(");

    // Narrow safety net: `<var>.getMessage()` as the trailing argument of any log call,
    // anywhere in the file. Catches the same trap outside catch blocks (e.g. when a
    // Throwable is passed in as a method parameter).
    private static final Pattern TRAILING_GET_MESSAGE = Pattern.compile("\\blog\\.(warn|error|debug|trace)\\([^;]*\\b"
            + "(e|ex|exception|throwable|t|cause)\\.getMessage\\(\\)\\s*\\)\\s*;");

    @Test
    void catchBlockLogCallsMustPassCaughtExceptionAsLastArg() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanCatchBlocks(p, violations));
        }
        assertThat(violations)
                .as("In every catch block, log.{warn,error,debug,trace}(...) calls MUST pass the "
                        + "caught exception as the LAST argument so SLF4J prints a stacktrace "
                        + "(see AGENTS.md). Suppress with `// " + SUPPRESSION_MARKER
                        + ": <reason>` on the same line as the log call.")
                .isEmpty();
    }

    @Test
    void noLogCallEndsWithExceptionDotGetMessage() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> scanGetMessageTrap(p, violations));
        }
        assertThat(violations)
                .as("Log calls must not have `<ex>.getMessage()` as the trailing argument — "
                        + "the exception object itself must follow it. Suppress with `// "
                        + SUPPRESSION_MARKER + ": <reason>`.")
                .isEmpty();
    }

    private static void scanCatchBlocks(Path file, List<String> violations) {
        String src = readFile(file);
        String code = stripCommentsAndStrings(src);
        int[] lineStarts = lineStarts(src);

        Deque<CatchScope> scopes = new ArrayDeque<>();
        int n = code.length();
        Matcher catchMatcher = CATCH_DECL.matcher(code);
        Matcher logMatcher = LOG_CALL_START.matcher(code);

        int i = 0;
        while (i < n) {
            // Pop scopes whose closing brace we've passed.
            while (!scopes.isEmpty() && i >= scopes.peek().closingBracePos) {
                scopes.pop();
            }
            char c = code.charAt(i);

            if (c == 'c' && catchMatcher.region(i, n).lookingAt()) {
                String var = catchMatcher.group(1);
                int braceIdx = catchMatcher.end() - 1;
                int closeBrace = findMatching(code, braceIdx, '{', '}');
                if (closeBrace > 0) {
                    scopes.push(new CatchScope(var, closeBrace));
                }
                i = catchMatcher.end();
                continue;
            }

            if (c == 'l' && !scopes.isEmpty() && logMatcher.region(i, n).lookingAt()) {
                int openParen = logMatcher.end() - 1;
                int closeParen = findMatching(code, openParen, '(', ')');
                if (closeParen < 0) {
                    i = logMatcher.end();
                    continue;
                }
                int startLine = lineOf(lineStarts, i);
                int endLine = lineOf(lineStarts, closeParen);
                if (!suppressionInRange(src, lineStarts, startLine, endLine)) {
                    String lastArg = lastArg(code, openParen + 1, closeParen).trim();
                    if (!scopes.peek().varName.equals(lastArg)) {
                        violations.add(file + ":" + startLine + " — "
                                + lineText(src, lineStarts, startLine).trim());
                    }
                }
                i = closeParen + 1;
                continue;
            }
            i++;
        }
    }

    private static void scanGetMessageTrap(Path file, List<String> violations) {
        String src = readFile(file);
        String[] lines = src.split("\n", -1);
        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            if (line.contains(SUPPRESSION_MARKER)) {
                continue;
            }
            if (TRAILING_GET_MESSAGE.matcher(line).find()) {
                violations.add(file + ":" + (idx + 1) + " — " + line.trim());
            }
        }
    }

    // --- helpers ---

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + file, e);
        }
    }

    private static int findMatching(String s, int start, char open, char close) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    // Returns the substring after the last top-level comma in [from, to).
    private static String lastArg(String s, int from, int to) {
        int depth = 0;
        int lastComma = from - 1;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                lastComma = i;
            }
        }
        return s.substring(lastComma + 1, to);
    }

    // Replaces line comments, block comments, string literals, char literals, and text
    // blocks with spaces (preserving offsets and newlines), so structural scanning is not
    // confused by syntax inside literals/comments.
    private static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int j = i; j < end; j++) {
                    out.append(src.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end;
                continue;
            }
            if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                for (int j = i; j < end; j++) {
                    out.append(src.charAt(j) == '\n' ? '\n' : ' ');
                }
                i = end;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' ');
                i++;
                while (i < n) {
                    char cc = src.charAt(i);
                    if (cc == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                        continue;
                    }
                    if (cc == quote) {
                        out.append(' ');
                        i++;
                        break;
                    }
                    out.append(cc == '\n' ? '\n' : ' ');
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int[] lineStarts(String src) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < src.length(); i++) {
            if (src.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] arr = new int[starts.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = starts.get(i);
        }
        return arr;
    }

    private static int lineOf(int[] lineStarts, int pos) {
        // binary search: largest index with lineStarts[idx] <= pos. Line numbers are 1-based.
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= pos) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo + 1;
    }

    private static String lineText(String src, int[] lineStarts, int lineNum) {
        int start = lineStarts[lineNum - 1];
        int end = lineNum < lineStarts.length ? lineStarts[lineNum] - 1 : src.length();
        return src.substring(start, end);
    }

    private static boolean suppressionInRange(String src, int[] lineStarts, int fromLine, int toLine) {
        for (int line = fromLine; line <= toLine; line++) {
            if (lineText(src, lineStarts, line).contains(SUPPRESSION_MARKER)) {
                return true;
            }
        }
        return false;
    }

    private static final class CatchScope {
        final String varName;
        final int closingBracePos;

        CatchScope(String varName, int closingBracePos) {
            this.varName = varName;
            this.closingBracePos = closingBracePos;
        }
    }
}
