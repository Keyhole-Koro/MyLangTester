import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestParser {
    private TestParser() {
    }

    public static TestMeta readMetadataAndWriteSource(Path path, Path outPath, boolean useTestKit)
            throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        ParsedTest test = parseTestSource(path, source);
        String generated;
        if (useTestKit) {
            String testName = test.meta.name.isEmpty() ? defaultTestName(path) : test.meta.name;
            generated = source.substring(0, test.declStart)
                    + "extern void testkit_pass(char* name);\n\n"
                    + "extern void __mlt_require_abi_v1();\n\n"
                    + "i32 __mlt_test_body() {\n"
                    + source.substring(test.bodyStart + 1, test.bodyEnd)
                    + "\nreturn 0;\n}\n\n"
                    + "i32 kernel_main() {\n"
                    + "__mlt_require_abi_v1();\n"
                    + "__mlt_test_body();\n"
                    + "testkit_pass(\"" + escapeString(testName) + "\");\n"
                    + "return 0;\n}\n"
                    + source.substring(test.tail);
        } else {
            generated = source.substring(0, test.declStart)
                    + "i32 kernel_main() {\n"
                    + source.substring(test.bodyStart + 1, test.bodyEnd)
                    + "\nreturn 0;\n}\n"
                    + source.substring(test.tail);
        }
        Files.writeString(outPath, generated, StandardCharsets.UTF_8);
        return test.meta;
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
    public static void writeAnnotatedHarness(Path original, Path outPath, TestMeta meta,
                                             boolean useTestKit) throws IOException {
        String importPath = original.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder generated = new StringBuilder();
        generated.append("import { ").append(meta.functionName).append(" } from \"")
                .append(importPath).append("\";\n\n");
        if (useTestKit) {
            generated.append("extern void testkit_pass(char* name);\n")
                    .append("extern void __mlt_require_abi_v1();\n\n");
        }
        generated.append("i32 kernel_main() {\n");
        if (useTestKit) generated.append("__mlt_require_abi_v1();\n");
        generated.append(meta.functionName).append("();\n");
        if (useTestKit) {
            generated.append("testkit_pass(\"").append(escapeString(meta.name)).append("\");\n");
        }
        generated.append("return 0;\n}\n");
        Files.writeString(outPath, generated.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Existing tests which import the old kernel-local test library supply
     * their own bare assert_fail hook.  They remain compatible while new
     * tests use the TestKit-provided hook.
     */
    public static boolean usesLegacyTestRuntime(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).contains("libs/test.mln");
    }

    private static String defaultTestName(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".test.mln")
                ? name.substring(0, name.length() - ".test.mln".length())
                : name;
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

    private static ParsedTest parseTestSource(Path path, String source) throws IOException {
        int declStart = findTestKeyword(source, 0);
        while (declStart >= 0) {
            int after = skipWs(source, declStart + 4);
            if (after < source.length() && source.charAt(after) == '(') {
                return parseTestDeclaration(path, source, declStart);
            }
            declStart = findTestKeyword(source, declStart + 4);
        }
        throw new IOException("mytest: missing top-level test declaration in " + path);
    }

    private static ParsedTest parseTestDeclaration(Path path, String source, int declStart) throws IOException {
        ParsedTest out = new ParsedTest();
        out.declStart = declStart;

        int p = skipWs(source, declStart + 4);
        if (charAt(source, p) != '(') {
            throw new IOException("mytest: expected '(' after test in " + path);
        }
        p = skipWs(source, p + 1);
        if (charAt(source, p) != '"') {
            throw new IOException("mytest: expected test name string in " + path);
        }

        int nameEnd = findStringEnd(source, p);
        out.meta.name = unquoteValue(source.substring(p, nameEnd + 1));

        p = skipWs(source, nameEnd + 1);
        if (charAt(source, p) != ',') {
            throw new IOException("mytest: expected options after test name in " + path);
        }
        p = skipWs(source, p + 1);
        if (charAt(source, p) != '{') {
            throw new IOException("mytest: expected options block in " + path);
        }

        int optionsStart = p;
        int optionsEnd = findMatching(source, optionsStart, '{', '}');
        applyOptions(out.meta, source.substring(optionsStart + 1, optionsEnd), path);

        p = skipWs(source, optionsEnd + 1);
        if (charAt(source, p) != ',') {
            throw new IOException("mytest: expected callback after test options in " + path);
        }
        p = skipWs(source, p + 1);
        if (charAt(source, p) != '(') {
            throw new IOException("mytest: expected callback parameter list in " + path);
        }

        int paramsEnd = findMatching(source, p, '(', ')');
        if (skipWs(source, p + 1) != paramsEnd) {
            throw new IOException("mytest: test callback must not declare parameters in " + path);
        }
        p = skipWs(source, paramsEnd + 1);
        if (charAt(source, p) != '=' || charAt(source, p + 1) != '>') {
            throw new IOException("mytest: expected => before test callback body in " + path);
        }
        p = skipWs(source, p + 2);
        if (charAt(source, p) != '{') {
            throw new IOException("mytest: expected callback body in " + path);
        }

        out.bodyStart = p;
        out.bodyEnd = findMatching(source, out.bodyStart, '{', '}');
        p = skipWs(source, out.bodyEnd + 1);
        if (charAt(source, p) != ')') {
            throw new IOException("mytest: expected ')' after test callback in " + path);
        }
        p = skipWs(source, p + 1);
        out.tail = charAt(source, p) == ';' ? p + 1 : p;
        return out;
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

    private static int findTestKeyword(String source, int from) {
        ScannerState state = new ScannerState();
        for (int i = from; i < source.length(); i++) {
            int next = state.consume(source, i);
            if (next != i) {
                i = next;
                continue;
            }
            if (state.isCode() && isTestKeywordAt(source, i)) {
                return i;
            }
        }
        return -1;
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

    private static int skipWs(String source, int index) {
        int i = index;
        while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
            i++;
        }
        return i;
    }

    private static char charAt(String source, int index) {
        return index >= 0 && index < source.length() ? source.charAt(index) : '\0';
    }

    private static boolean isTestKeywordAt(String source, int index) {
        if (!source.startsWith("test", index)) {
            return false;
        }
        boolean leftOk = index == 0 || !isIdentChar(source.charAt(index - 1));
        boolean rightOk = index + 4 >= source.length() || !isIdentChar(source.charAt(index + 4));
        return leftOk && rightOk;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static final class ParsedTest {
        final TestMeta meta = new TestMeta();
        int declStart;
        int bodyStart;
        int bodyEnd;
        int tail;
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
