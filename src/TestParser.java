import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestParser {
    private TestParser() {
    }

    /**
     * Discover ordinary functions preceded by a runner pragma:
     *
     *   /*@Test "name"*&#47;
     *   void test_name() { ... }
     *
     * A metadata block may follow the name inside the same comment, using the
     * existing `key: value;` spelling: `/*@Test "name" { step: 1000; }*&#47;`.
     * The pragma intentionally remains a comment until MyLang gains general
     * annotations, but it maps one-to-one to a future `@Test(...)` form.
     */
    public static List<TestMeta> readAnnotatedTests(Path path) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        List<TestMeta> tests = new ArrayList<>();
        ScannerState state = new ScannerState();
        int braceDepth = 0;

        for (int i = 0; i < source.length(); i++) {
            if (state.isCode() && braceDepth == 0 && source.startsWith("/*@Test", i)) {
                int end = source.indexOf("*/", i + 7);
                if (end < 0) {
                    throw new IOException("mytest: unclosed @Test pragma in " + path);
                }
                TestMeta meta = parseTestPragma(path, source.substring(i + 7, end));
                int header = skipTrivia(source, end + 2);
                int openParen = findNextCodeChar(source, header, '(');
                if (openParen < 0) {
                    throw new IOException("mytest: @Test must precede a function in " + path);
                }
                String functionName = identifierBefore(source, openParen);
                if (functionName.isEmpty()) {
                    throw new IOException("mytest: cannot find @Test function name in " + path);
                }
                int closeParen = findMatching(source, openParen, '(', ')');
                int bodyStart = findNextCodeChar(source, closeParen + 1, '{');
                if (bodyStart < 0) {
                    throw new IOException("mytest: @Test function '" + functionName
                            + "' has no body in " + path);
                }
                meta.functionName = functionName;
                meta.bodyStart = bodyStart;
                meta.bodyEnd = findMatching(source, bodyStart, '{', '}');
                if (meta.name.isEmpty()) meta.name = functionName;
                tests.add(meta);
                i = end + 1;
                state = new ScannerState();
                continue;
            }
            if (state.isCode()) {
                if (source.charAt(i) == '{') braceDepth++;
                if (source.charAt(i) == '}' && braceDepth > 0) braceDepth--;
            }
            int next = state.consume(source, i);
            if (next != i) i = next;
        }
        return tests;
    }

    /** Write a tiny harness without modifying the annotated test function. */
    public static void writeAnnotatedHarness(Path original, Path outPath, TestMeta meta) throws IOException {
        String importPath = original.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder generated = new StringBuilder();
        generated.append("import { ").append(meta.functionName).append(" } from \"")
                .append(importPath).append("\";\n\n")
                .append("extern void testkit_pass(char* name);\n")
                .append("extern void __mlt_require_abi_v1();\n\n")
                .append("i32 kernel_main() {\n")
                .append("__mlt_require_abi_v1();\n")
                .append(meta.functionName).append("();\n")
                .append("testkit_pass(\"").append(escapeString(meta.name)).append("\");\n")
                .append("return 0;\n}\n");
        Files.writeString(outPath, generated.toString(), StandardCharsets.UTF_8);
    }

    private static TestMeta parseTestPragma(Path path, String payload) throws IOException {
        String text = payload.trim();
        if (text.isEmpty() || text.charAt(0) != '"') {
            throw new IOException("mytest: @Test requires a quoted name in " + path);
        }
        int nameEnd = findStringEnd(text, 0);
        TestMeta meta = new TestMeta();
        meta.name = unquoteValue(text.substring(0, nameEnd + 1));
        String rest = text.substring(nameEnd + 1).trim();
        if (rest.isEmpty()) return meta;
        if (rest.charAt(0) != '{' || rest.charAt(rest.length() - 1) != '}') {
            throw new IOException("mytest: @Test metadata must use { key: value; } in " + path);
        }
        applyOptions(meta, rest.substring(1, rest.length() - 1), path);
        return meta;
    }

    private static int skipTrivia(String source, int index) {
        int i = index;
        while (i < source.length()) {
            if (Character.isWhitespace(source.charAt(i))) {
                i++;
            } else if (source.startsWith("//", i)) {
                int newline = source.indexOf('\n', i + 2);
                i = newline < 0 ? source.length() : newline + 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else {
                return i;
            }
        }
        return i;
    }

    private static int findNextCodeChar(String source, int from, char wanted) {
        ScannerState state = new ScannerState();
        for (int i = from; i < source.length(); i++) {
            int next = state.consume(source, i);
            if (next != i) {
                i = next;
                continue;
            }
            if (state.isCode() && source.charAt(i) == wanted) return i;
            if (state.isCode() && (source.charAt(i) == ';' || source.charAt(i) == '}')) return -1;
        }
        return -1;
    }

    private static String identifierBefore(String source, int index) {
        int end = index;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && isIdentChar(source.charAt(start - 1))) start--;
        return source.substring(start, end);
    }

    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void applyOptions(TestMeta meta, String block, Path path) throws IOException {
        for (String rawLine : block.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new IOException("mytest: invalid test metadata line: " + line + " in " + path);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.endsWith(";")) {
                value = value.substring(0, value.length() - 1).trim();
            }

            switch (key) {
                case "name":
                    meta.name = unquoteValue(value);
                    break;
                case "stdin":
                    meta.stdinText = unquoteValue(value);
                    break;
                case "expect":
                    meta.expect = unquoteValue(value);
                    break;
                case "step":
                    meta.step = value;
                    break;
                case "timer_interval":
                case "timer-interval":
                    meta.timerInterval = value;
                    break;
                case "disk":
                    String disk = unquoteValue(value);
                    meta.disk = !disk.equals("false");
                    meta.diskFixture = (disk.equals("true") || disk.equals("false"))
                            ? "" : disk;
                    break;
                default:
                    break;
            }
        }
    }

    private static String unquoteValue(String value) {
        String text = value.trim();
        if (!text.startsWith("\"")) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                break;
            }
            if (c == '\\' && i + 1 < text.length()) {
                char escaped = text.charAt(++i);
                out.append(escaped == 'n' ? '\n' : escaped == 't' ? '\t' : escaped);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int findStringEnd(String source, int quote) throws IOException {
        for (int i = quote + 1; i < source.length(); i++) {
            if (source.charAt(i) == '"' && source.charAt(i - 1) != '\\') {
                return i;
            }
        }
        throw new IOException("mytest: unclosed test name");
    }

    private static int findMatching(String source, int open, char openChar, char closeChar) throws IOException {
        int depth = 0;
        ScannerState state = new ScannerState();
        for (int i = open; i < source.length(); i++) {
            int next = state.consume(source, i);
            if (next != i) {
                i = next;
                continue;
            }
            if (!state.isCode()) {
                continue;
            }
            char c = source.charAt(i);
            if (c == openChar) {
                depth++;
            } else if (c == closeChar && --depth == 0) {
                return i;
            }
        }
        throw new IOException("mytest: unclosed " + openChar + closeChar + " block");
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final class ScannerState {
        private boolean inString;
        private boolean inChar;
        private boolean inLineComment;
        private boolean inBlockComment;

        int consume(String source, int index) {
            char c = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                return index;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    return index + 1;
                }
                return index;
            }
            if (inString) {
                if (c == '\\' && next != '\0') {
                    return index + 1;
                }
                if (c == '"') {
                    inString = false;
                }
                return index;
            }
            if (inChar) {
                if (c == '\\' && next != '\0') {
                    return index + 1;
                }
                if (c == '\'') {
                    inChar = false;
                }
                return index;
            }

            if (c == '/' && next == '/') {
                inLineComment = true;
                return index + 1;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                return index + 1;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            }
            return index;
        }

        boolean isCode() {
            return !inString && !inChar && !inLineComment && !inBlockComment;
        }
    }
}
