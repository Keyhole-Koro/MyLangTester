import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Unit tests for the parts of mytest that need no toolchain: pragma and
 * option parsing, mock-target discovery, the source scanner, generated
 * names and the compiler manifests. Plain Java, so `make unit-test` needs
 * nothing beyond the JDK. It sits in the default package beside the runner
 * to reach its package-private members.
 */
public final class UnitTests {
    private static int checks;
    private static int failures;
    private static String current = "";
    private static Path scratch;

    private UnitTests() {
    }

    interface Case {
        void run() throws Exception;
    }

    public static void main(String[] args) throws IOException {
        scratch = Files.createTempDirectory("mytest-unit-");
        try {
            run("pragma name and options", UnitTests::pragmaNameAndOptions);
            run("options sharing a line", UnitTests::optionsSharingALine);
            run("quoted ; and // stay in a value", UnitTests::quotedSeparatorsStay);
            run("disk and timer options", UnitTests::diskAndTimerOptions);
            run("pragma errors", UnitTests::pragmaErrors);
            run("only top-level pragmas count", UnitTests::onlyTopLevelPragmas);
            run("mock targets", UnitTests::mockTargets);
            run("source scanner", UnitTests::sourceScanner);
            run("generated source names", UnitTests::generatedSourceNames);
            run("harness", UnitTests::harness);
            run("diagnostics.cases", UnitTests::diagnosticsCases);
            run("e2e.cases", UnitTests::e2eCases);
            run("test discovery", UnitTests::testDiscovery);
        } finally {
            deleteTree(scratch);
        }
        System.out.printf("[SUMMARY] unit: %d checks, %d failed%n", checks, failures);
        if (failures > 0) System.exit(1);
    }

    // --- TestParser -------------------------------------------------------

    private static void pragmaNameAndOptions() throws IOException {
        List<TestMeta> tests = parse(
                "/*@Test \"adds\" {\n"
                + "    step: 1000;   // a comment\n"
                + "    stdin: \"in\\n\";\n"
                + "    expect: \"TEST_PASS:adds\";\n"
                + "    unknown: ignored;\n"
                + "}*/\n"
                + "void adds() { assert(1 + 1 == 2); }\n"
                + "\n"
                + "/*@Test \"\"*/\n"
                + "void defaults() {}\n");
        equal(2, tests.size(), "two annotated tests");
        TestMeta adds = tests.get(0);
        equal("adds", adds.name, "name");
        equal("adds", adds.functionName, "function name");
        equal("{ assert(1 + 1 == 2); }", adds.body, "body keeps its braces");
        equal("1000", adds.step, "step");
        equal("in\n", adds.stdinText, "stdin unquoted with \\n");
        equal("TEST_PASS:adds", adds.expect, "expect");
        TestMeta defaults = tests.get(1);
        equal("defaults", defaults.name, "an empty name falls back to the function name");
        equal("10000000", defaults.step, "default step");
        equal("TEST_PASS", defaults.expect, "default expect");
        equal("", defaults.stdinText, "default stdin");
        check(!defaults.disk.attached, "no disk by default");
    }

    private static void optionsSharingALine() throws IOException {
        TestMeta meta = parseOne("/*@Test \"t\" { step: 5; stdin: \"x\"; expect: \"y\" }*/\nvoid t() {}\n");
        equal("5", meta.step, "step");
        equal("x", meta.stdinText, "stdin");
        equal("y", meta.expect, "the last option needs no ;");
        TestMeta named = parseOne("/*@Test \"t\" { name: \"renamed\"; }*/\nvoid t() {}\n");
        equal("renamed", named.name, "name option");
    }

    private static void quotedSeparatorsStay() throws IOException {
        TestMeta meta = parseOne("/*@Test \"t\" { stdin: \"a;b//c\"; expect: \"q\\\"; r\"; }*/\nvoid t() {}\n");
        equal("a;b//c", meta.stdinText, "; and // inside quotes");
        equal("q\"; r", meta.expect, "an escaped quote does not close the value");
    }

    private static void diskAndTimerOptions() throws IOException {
        equal(false, parseOne(pragma("disk: false;")).disk.attached, "disk: false");
        TestMeta fresh = parseOne(pragma("disk: true;"));
        check(fresh.disk.attached && fresh.disk.fixture.isEmpty(), "disk: true is a fresh disk");
        TestMeta fixture = parseOne(pragma("disk: \"fixtures/seed.img\";"));
        check(fixture.disk.attached, "a fixture attaches a disk");
        equal(Optional.of("fixtures/seed.img"), fixture.disk.fixture, "fixture path");
        equal(Optional.of("7"), parseOne(pragma("timer_interval: 7;")).timerInterval, "timer_interval");
        equal(Optional.of("8"), parseOne(pragma("timer-interval: 8;")).timerInterval, "timer-interval");
        equal(Optional.empty(), parseOne(pragma("timer_interval: ;")).timerInterval, "empty timer_interval");
    }

    private static void pragmaErrors() {
        throwsIOException("unquoted name", "quoted name", () -> parse("/*@Test name*/\nvoid t() {}\n"));
        throwsIOException("unclosed name", "unclosed test name", () -> parse("/*@Test \"t*/\nvoid t() {}\n"));
        throwsIOException("unclosed pragma", "unclosed @Test", () -> parse("/*@Test \"t\"\nvoid t() {}\n"));
        throwsIOException("no function", "must precede a function", () -> parse("/*@Test \"t\"*/\ni32 x;\n"));
        throwsIOException("no body", "has no body", () -> parse("/*@Test \"t\"*/\nvoid t();\n"));
        throwsIOException("metadata not in braces", "{ key: value; }",
                () -> parse("/*@Test \"t\" step: 5*/\nvoid t() {}\n"));
        throwsIOException("option without a colon", "invalid test metadata",
                () -> parse("/*@Test \"t\" { step 5; }*/\nvoid t() {}\n"));
    }

    private static void onlyTopLevelPragmas() throws IOException {
        List<TestMeta> tests = parse(
                "// /*@Test \"in a line comment\"*/\n"
                + "str s = \"/*@Test \\\"in a string\\\"*/\";\n"
                + "void outer() {\n"
                + "    /*@Test \"inside a body\"*/\n"
                + "}\n"
                + "/*@Test \"real\"*/\n"
                + "void real() { str brace = \"}\"; }\n");
        equal(1, tests.size(), "only the top-level pragma");
        equal("real", tests.get(0).name, "its name");
        equal("{ str brace = \"}\"; }", tests.get(0).body, "a brace in a string does not end the body");
    }

    private static void mockTargets() {
        equal(List.of("ssd_read_block", "serial_write"), TestParser.mockTargets(
                "{ mock.of(ssd.read_block).ret(1);\n"
                + "  mock.spy(serial_write);\n"
                + "  mock.of(ssd.read_block); }"),
                "dotted targets become linker symbols, once each, in order");
        equal(List.of(), TestParser.mockTargets(
                "{ // mock.of(in_comment)\n"
                + "  /* mock.spy(in_block) */\n"
                + "  assert(1, \"mock.of(in_string)\");\n"
                + "  mock.calls(counted);\n"
                + "  mock.of(two, args);\n"
                + "  mock.of(1); }"),
                "comments, strings, other calls and non-target arguments are ignored");
        equal(List.of("a"), TestParser.mockTargets("{ mock . of ( a ) ; }"), "spacing does not matter");
    }

    // --- SourceScanner ----------------------------------------------------

    private static void sourceScanner() {
        equal(5, SourceScanner.skipNonCode("// x\ny = 1", 0), "a line comment ends after its newline");
        equal(4, SourceScanner.skipNonCode("// x", 0), "an unclosed line comment runs to the end");
        equal(7, SourceScanner.skipNonCode("/* x */y", 0), "a block comment");
        equal(5, SourceScanner.skipNonCode("/* x ", 0), "an unclosed block comment runs to the end");
        equal(6, SourceScanner.skipNonCode("\"a\\\"b\"c", 0), "an escaped quote stays in the string");
        equal(4, SourceScanner.skipNonCode("'\\''x", 0), "a character literal");
        equal(3, SourceScanner.skipNonCode("\"ab", 0), "an unclosed string runs to the end");
        equal(0, SourceScanner.skipNonCode("x = 1", 0), "code stays put");
        equal(14, SourceScanner.nextCode("/*a*//**/// c\nx", 0), "nextCode skips comments in a row");
        equal(5, SourceScanner.nextCode("/*a*/ x", 0), "nextCode stops at whitespace, which is code");
        equal(List.of("foo", ".", "bar", "(", "x1", ")", ";"),
                SourceScanner.codeTokens("foo.bar(x1); // baz(\n\"q(\" /* r */"), "tokens");
        check(SourceScanner.isIdentifierStart('_') && !SourceScanner.isIdentifierStart('1'), "identifier start");
        check(SourceScanner.isIdentifierPart('1') && !SourceScanner.isIdentifierPart('.'), "identifier part");
    }

    // --- TestRunner -------------------------------------------------------

    private static void generatedSourceNames() {
        equal("serial_rx_gen_test.mln", TestRunner.generatedSourceName(Path.of("/t/serial_rx.test.mln")),
                "a plain test");
        equal("dom_lowering_gen_test.dom.mln",
                TestRunner.generatedSourceName(Path.of("/t/dom_lowering.dom.test.mln")), "modifiers are kept");
        equal("pragma_layouts_gen_test.unsafe.mln",
                TestRunner.generatedSourceName(Path.of("/t/pragma_layouts.unsafe.test.mln")), "unsafe");
        equal("plain_gen_test.mln", TestRunner.generatedSourceName(Path.of("/t/plain.mln")), "a .mln file");
        equal("a_b_gen_test.mln", TestRunner.generatedSourceName(Path.of("/t/a b.test.mln")),
                "spaces become underscores");
    }

    private static void harness() throws IOException {
        TestMeta meta = new TestMeta();
        meta.name = "say \"hi\"";
        meta.functionName = "say_hi";
        Path out = scratch.resolve("harness.mln");
        TestParser.writeAnnotatedHarness(Path.of("/t/say.test.mln"), out, meta);
        String harness = Files.readString(out, StandardCharsets.UTF_8);
        contains(harness, "import { say_hi } from \"/t/say.test.mln\";", "imports the test function");
        contains(harness, "say_hi();", "calls it");
        contains(harness, "testkit_pass(\"say \\\"hi\\\"\");", "passes with the escaped name");
    }

    // --- CompilerTestRunner -----------------------------------------------

    private static void diagnosticsCases() throws IOException {
        CompilerTestRunner.Expectations expected = CompilerTestRunner.readExpectations(write("diagnostics.cases",
                "# comment\n"
                + "\n"
                + "error | fail/a.mln | expected ';'\n"
                + "skip | succeed/later.mln\n"
                + "profile | ok.safe.mln | \n"
                + "profile-error | bad.zz.mln | unknown modifier | i32 f() { return 1; }\n"));
        equal("expected ';'", expected.errors.get("fail/a.mln"), "an error's text is trimmed");
        check(expected.skipped.contains("succeed/later.mln"), "skip");
        equal(2, expected.profiles.size(), "profiles");
        CompilerTestRunner.ProfileCase ok = expected.profiles.get(0);
        equal("ok.safe.mln", ok.filename, "profile filename");
        check(ok.accepted, "profile is accepted");
        equal("i32 main() { return 0; }\n", ok.source, "default profile source");
        CompilerTestRunner.ProfileCase bad = expected.profiles.get(1);
        check(!bad.accepted, "profile-error is refused");
        equal("unknown modifier", bad.text, "profile-error text");
        equal("i32 f() { return 1; }\n", bad.source, "profile-error source");

        equal(1, CompilerTestRunner.readExpectations(write("pipe.cases", "error|fail/b.mln|x\n")).errors.size(),
                "spaces around | are optional");
        throwsIOException("duplicate error", "listed twice", () -> CompilerTestRunner.readExpectations(
                write("dup.cases", "error | fail/a.mln | x\nerror | fail/a.mln | y\n")));
        throwsIOException("unknown kind", "malformed", () -> CompilerTestRunner.readExpectations(
                write("kind.cases", "warning | fail/a.mln | x\n")));
        throwsIOException("missing name", "malformed", () -> CompilerTestRunner.readExpectations(
                write("name.cases", "skip | \n")));
        throwsIOException("line number", ":2", () -> CompilerTestRunner.readExpectations(
                write("line.cases", "# ok\nerror | a\n")));
    }

    private static void e2eCases() throws IOException {
        List<CompilerTestRunner.E2ECase> cases = CompilerTestRunner.readE2ECases(write("e2e.cases",
                "# name | R1 | sources | flags\n"
                + "add | 0x2A | e2e/add.mln\n"
                + "two | -1 | e2e/a.mln, e2e/b.mln | -O1   --safe \n"
                + "dec | 10 | e2e/c.mln | \n"));
        equal(3, cases.size(), "three cases");
        CompilerTestRunner.E2ECase add = cases.get(0);
        equal("add", add.name, "name");
        equal(42L, add.expected, "hex register value");
        equal(List.of("e2e/add.mln"), add.sources, "one source");
        equal(List.of(), add.flags, "no flags");
        CompilerTestRunner.E2ECase two = cases.get(1);
        equal(-1L, two.expected, "negative value");
        equal(List.of("e2e/a.mln", "e2e/b.mln"), two.sources, "sources are split and trimmed");
        equal(List.of("-O1", "--safe"), two.flags, "flags are split on whitespace");
        equal(List.of(), cases.get(2).flags, "an empty flags field");

        throwsIOException("too few fields", "malformed", () -> CompilerTestRunner.readE2ECases(
                write("few.cases", "add | 1\n")));
        throwsIOException("no name", "malformed", () -> CompilerTestRunner.readE2ECases(
                write("noname.cases", " | 1 | a.mln\n")));
        throwsIOException("no sources", "malformed", () -> CompilerTestRunner.readE2ECases(
                write("nosrc.cases", "add | 1 | \n")));
        throwsIOException("bad register value", "invalid register value", () -> CompilerTestRunner.readE2ECases(
                write("value.cases", "add | forty | a.mln\n")));
    }

    // --- TestDiscoverer ---------------------------------------------------

    private static void testDiscovery() throws IOException {
        Path root = scratch.resolve("discover");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("a.test.mln"), "");
        Files.writeString(root.resolve("b.mln"), "");
        Files.writeString(root.resolve("c.test.mln.bak"), "");
        Files.writeString(root.resolve("sub/d.dom.test.mln"), "");
        List<String> found = TestDiscoverer.discover(root).stream()
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .sorted().collect(Collectors.toList());
        equal(List.of("a.test.mln", "sub/d.dom.test.mln"), found, "only .test.mln files, recursively");
        equal(List.of(root.resolve("a.test.mln")), TestDiscoverer.discover(root.resolve("a.test.mln")),
                "a test file names itself");
        equal(List.of(), TestDiscoverer.discover(root.resolve("b.mln")), "another file is no test");
        throwsIOException("missing path", "cannot stat", () -> TestDiscoverer.discover(root.resolve("none")));
    }

    // --- helpers ----------------------------------------------------------

    private static String pragma(String options) {
        return "/*@Test \"t\" { " + options + " }*/\nvoid t() {}\n";
    }

    private static List<TestMeta> parse(String source) throws IOException {
        return TestParser.readAnnotatedTests(write("case.test.mln", source));
    }

    private static TestMeta parseOne(String source) throws IOException {
        List<TestMeta> tests = parse(source);
        if (tests.size() != 1) throw new AssertionError("expected one test, found " + tests.size());
        return tests.get(0);
    }

    private static Path write(String name, String text) throws IOException {
        Path path = scratch.resolve(name);
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private static void run(String name, Case test) {
        current = name;
        try {
            test.run();
        } catch (Throwable e) {
            fail("threw " + e);
        }
    }

    private static void check(boolean condition, String what) {
        checks++;
        if (!condition) fail(what);
    }

    private static void equal(Object expected, Object actual, String what) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            fail(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void contains(String text, String part, String what) {
        checks++;
        if (!text.contains(part)) fail(what + ": <" + part + "> not in <" + text + ">");
    }

    private static void throwsIOException(String what, String messagePart, Case action) {
        checks++;
        try {
            action.run();
            fail(what + ": no exception");
        } catch (IOException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains(messagePart)) {
                fail(what + ": message <" + message + "> lacks <" + messagePart + ">");
            }
        } catch (Exception e) {
            fail(what + ": threw " + e + " instead of an IOException");
        }
    }

    private static void fail(String detail) {
        failures++;
        System.err.printf("[FAIL] %s: %s%n", current, detail);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) Files.deleteIfExists(path);
        }
    }
}
