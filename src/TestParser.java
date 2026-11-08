import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        int braceDepth = 0;

        int i = 0;
        while (i < source.length()) {
            if (braceDepth == 0 && source.startsWith("/*@Test", i)) {
                int end = source.indexOf("*/", i + 7);
                if (end < 0) {
                    throw new IOException("mytest: unclosed @Test pragma in " + path);
                }
                TestMeta meta = parseTestPragma(path, source.substring(i + 7, end));
                int openParen = findNextCodeChar(source, end + 2, '(');
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
                meta.body = source.substring(bodyStart, findMatching(source, bodyStart, '{', '}') + 1);
                if (meta.name.isEmpty()) meta.name = functionName;
                tests.add(meta);
                i = end + 2;
                continue;
            }
            int next = SourceScanner.skipNonCode(source, i);
            if (next != i) {
                i = next;
                continue;
            }
            if (source.charAt(i) == '{') braceDepth++;
            if (source.charAt(i) == '}' && braceDepth > 0) braceDepth--;
            i++;
        }
        return tests;
    }

    /**
     * The functions a test body hands to `mock.of(...)` or `mock.spy(...)`, in
     * order of first use, each spelled as its linker symbol (`ssd.read_block`
     * is `ssd_read_block`). Only code counts, so documentation and assertion
     * text cannot redirect a call to a symbol that does not exist. The
     * compiler still checks that every target is real.
     */
    public static List<String> mockTargets(String body) {
        Set<String> targets = new LinkedHashSet<>();
        List<String> tokens = SourceScanner.codeTokens(body);
        for (int i = 0; i + 4 < tokens.size(); i++) {
            if (!tokens.get(i).equals("mock") || !tokens.get(i + 1).equals(".")) continue;
            String factory = tokens.get(i + 2);
            if (!factory.equals("of") && !factory.equals("spy")) continue;
            if (!tokens.get(i + 3).equals("(")) continue;

            int cursor = i + 4;
            if (!isIdentifier(tokens.get(cursor))) continue;
            StringBuilder target = new StringBuilder(tokens.get(cursor++));
            while (cursor + 1 < tokens.size() && tokens.get(cursor).equals(".") &&
                    isIdentifier(tokens.get(cursor + 1))) {
                target.append('_').append(tokens.get(cursor + 1));
                cursor += 2;
            }
            if (cursor < tokens.size() && tokens.get(cursor).equals(")")) {
                targets.add(target.toString());
            }
        }
        return List.copyOf(targets);
    }

    private static boolean isIdentifier(String value) {
        return value != null && !value.isEmpty() && SourceScanner.isIdentifierStart(value.charAt(0));
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

    /** The first code `wanted` at or after `from`, or -1 if a `;` or `}` comes first. */
    private static int findNextCodeChar(String source, int from, char wanted) {
        for (int i = SourceScanner.nextCode(source, from); i < source.length();
                i = SourceScanner.nextCode(source, i + 1)) {
            char c = source.charAt(i);
            if (c == wanted) return i;
            if (c == ';' || c == '}') return -1;
        }
        return -1;
    }

    private static String identifierBefore(String source, int index) {
        int end = index;
        while (end > 0 && Character.isWhitespace(source.charAt(end - 1))) end--;
        int start = end;
        while (start > 0 && SourceScanner.isIdentifierPart(source.charAt(start - 1))) start--;
        return source.substring(start, end);
    }

    private static String escapeString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Apply the `key: value;` options of a metadata block. They may share a
     * line, `{ step: 5; disk: true; }`, or take one line each.
     */
    private static void applyOptions(TestMeta meta, String block, Path path) throws IOException {
        for (String option : optionStatements(block)) {
            int colon = option.indexOf(':');
            if (colon < 0) {
                throw new IOException("mytest: invalid test metadata: " + option + " in " + path);
            }
            String key = option.substring(0, colon).trim();
            String value = option.substring(colon + 1).trim();

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
                    meta.timerInterval = value.isEmpty() ? Optional.empty() : Optional.of(value);
                    break;
                case "disk":
                    meta.disk = diskOption(unquoteValue(value));
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Split a metadata block into its options, trimmed and without their `;`.
     * An option ends at a `;` or at the end of its line, and `//` starts a
     * comment running to the end of the line; neither counts inside a quoted
     * value, so `expect: "a; b";` keeps its semicolon. A value never spans
     * lines, quoted or not.
     */
    private static List<String> optionStatements(String block) {
        List<String> options = new ArrayList<>();
        StringBuilder option = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < block.length(); i++) {
            char c = block.charAt(i);
            if (c == '\n' || c == '\r') {
                quoted = false;
                addOption(options, option);
            } else if (quoted) {
                option.append(c);
                if (c == '\\' && i + 1 < block.length() && block.charAt(i + 1) != '\n'
                        && block.charAt(i + 1) != '\r') {
                    option.append(block.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
                option.append(c);
            } else if (block.startsWith("//", i)) {
                while (i + 1 < block.length() && block.charAt(i + 1) != '\n'
                        && block.charAt(i + 1) != '\r') {
                    i++;
                }
            } else if (c == ';') {
                addOption(options, option);
            } else {
                option.append(c);
            }
        }
        addOption(options, option);
        return options;
    }

    private static void addOption(List<String> options, StringBuilder option) {
        String text = option.toString().trim();
        if (!text.isEmpty()) options.add(text);
        option.setLength(0);
    }

    /** `disk: false` attaches none, `disk: true` a fresh one; anything else names a fixture. */
    private static TestMeta.Disk diskOption(String value) {
        if (value.equals("false")) return TestMeta.Disk.NONE;
        if (value.equals("true") || value.isEmpty()) return TestMeta.Disk.EMPTY;
        return TestMeta.Disk.copyOf(value);
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
        for (int i = SourceScanner.nextCode(source, open); i < source.length();
                i = SourceScanner.nextCode(source, i + 1)) {
            char c = source.charAt(i);
            if (c == openChar) {
                depth++;
            } else if (c == closeChar && --depth == 0) {
                return i;
            }
        }
        throw new IOException("mytest: unclosed " + openChar + closeChar + " block");
    }
}
