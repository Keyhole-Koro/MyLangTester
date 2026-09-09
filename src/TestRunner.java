import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class TestRunner {
    private TestRunner() {
    }

    public static Path findRepoRoot() throws IOException {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isReadable(dir.resolve("qa/runners/build_toolchain.py"))) {
                return dir;
            }
        }
        throw new IOException("mytest: could not find repo root containing qa/runners/build_toolchain.py");
    }

    public static boolean run(Path repo, Path testPath) throws IOException, InterruptedException {
        Path absTest = testPath.toRealPath();
        String base = testBasename(absTest);
        TestPaths paths = derivePaths(repo, absTest, base);
        boolean useTestKit = !TestParser.usesLegacyTestRuntime(absTest);

        try {
            TestMeta meta = TestParser.readMetadataAndWriteSource(absTest, paths.source, useTestKit);
            if (meta.name.isEmpty()) {
                meta.name = base;
            }
            TestPaths namedPaths = derivePaths(repo, absTest, meta.name);
            if (!paths.source.equals(namedPaths.source)) {
                Files.move(paths.source, namedPaths.source, StandardCopyOption.REPLACE_EXISTING);
            }
            paths = namedPaths;

            if (!buildTest(repo, meta, paths, useTestKit)) {
                return false;
            }
            if (!executeTest(repo, meta, paths, useTestKit)) {
                return false;
            }
            System.out.printf("[PASS] %s%n", meta.name);
            return true;
        } finally {
            Files.deleteIfExists(paths.source);
        }
    }

    private static boolean buildTest(Path repo, TestMeta meta, TestPaths paths, boolean useTestKit)
            throws IOException, InterruptedException {
        Files.createDirectories(paths.buildDir);

        String importPath = paths.source.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String stub = "import { kernel_main } from \"" + importPath + "\"\n\n"
                + "__START__:\n"
                + "  call kernel_main\n"
                + "  halt\n";
        Files.writeString(paths.stub, stub, StandardCharsets.UTF_8);
        Files.writeString(paths.input, meta.stdinText, StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>();
        command.add("python3");
        command.add(repo.resolve("qa/runners/build_toolchain.py").toString());
        command.add(paths.stub.toString());
        command.add(paths.source.toString());
        if (useTestKit) {
            for (Path source : testKitSources(repo)) {
                command.add(source.toString());
            }
        }
        command.add("-o");
        command.add(paths.linked.toString());
        command.add("--build-dir");
        command.add(paths.buildDir.toString());

        int status = runQuiet(command);
        if (status != 0) {
            System.err.printf("[FAIL] %s: build failed%n", meta.name);
            return false;
        }
        return true;
    }

    private static boolean executeTest(Path repo, TestMeta meta, TestPaths paths, boolean useTestKit)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(repo.resolve("runtime/MyEmulator/target/release/myemu").toString());
        command.add("-i");
        command.add(paths.linked.toString());
        command.add("--headless");
        command.add("--step");
        command.add(meta.step);
        if (!meta.timerInterval.isEmpty()) {
            command.add("--timer-interval");
            command.add(meta.timerInterval);
        }

        CommandResult result = capture(command, paths.input);
        String failure = verdictReason(result.output, "TEST_FAIL:");
        if (failure != null) {
            System.err.printf("[FAIL] %s: %s%n", meta.name, failure);
            return false;
        }
        if (result.status != 0) {
            System.err.printf("[FAIL] %s: emulator exited with %d%n", meta.name, result.status);
            System.err.println(result.output);
            return false;
        }
        if (useTestKit && !result.output.contains("TEST_PASS:")) {
            System.err.printf("[FAIL] %s: no test verdict%n", meta.name);
            System.err.println(result.output);
            return false;
        }
        if (!meta.expect.isEmpty() && !result.output.contains(meta.expect)) {
            System.err.printf("[FAIL] %s: expected output %s%n", meta.name, meta.expect);
            System.err.println(result.output);
            return false;
        }
        return true;
    }

    private static List<Path> testKitSources(Path repo) throws IOException {
        Path root = repo.resolve("toolchain/MyLangTestKit");
        List<Path> sources = List.of(
                root.resolve("runtime/abi.mln"),
                root.resolve("runtime/verdict.mln"),
                root.resolve("platform/mycomputer/verdict.mln"));
        for (Path source : sources) {
            if (!Files.isRegularFile(source)) {
                throw new IOException("mytest: missing MyLangTestKit source " + source);
            }
        }
        return sources;
    }

    private static String verdictReason(String output, String marker) {
        int start = output.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int end = output.indexOf('\n', start);
        String line = end < 0 ? output.substring(start) : output.substring(start, end);
        return line.trim();
    }

    private static int runQuiet(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(new File("/dev/null"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        return process.waitFor();
    }

    private static CommandResult capture(List<String> command, Path input)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectInput(input.toFile())
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int status = process.waitFor();
        return new CommandResult(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    private static String testBasename(Path path) {
        String base = path.getFileName().toString();
        if (base.endsWith(".test.mln")) {
            base = base.substring(0, base.length() - ".test.mln".length());
        }
        return base.replaceAll("[/ \\t.]", "_");
    }

    /**
     * Filename of the generated MyLang source, written next to the test file.
     *
     * mlc reads the source profile out of the filename, so the name must be a
     * stem followed only by modifiers it knows (`dom`, `safe`). The marker is
     * glued on with an underscore and the modifiers are carried over:
     * `serial_rx.test.mln` -> `serial_rx_gen_test.mln`,
     * `dom_lowering.dom.test.mln` -> `dom_lowering_gen_test.dom.mln`.
     */
    private static String generatedSourceName(Path absTest) {
        String base = absTest.getFileName().toString();
        if (base.endsWith(".test.mln")) {
            base = base.substring(0, base.length() - ".test.mln".length());
        } else if (base.endsWith(".mln")) {
            base = base.substring(0, base.length() - ".mln".length());
        }
        int dot = base.indexOf('.');
        String stem = dot < 0 ? base : base.substring(0, dot);
        String modifiers = dot < 0 ? "" : base.substring(dot);
        return stem.replaceAll("[/ \\t]", "_") + "_gen_test" + modifiers + ".mln";
    }

    private static TestPaths derivePaths(Path repo, Path absTest, String name) {
        Path source = absTest.resolveSibling(generatedSourceName(absTest));
        Path buildDir = repo.resolve(".mytest/build").resolve(name);
        return new TestPaths(
                source,
                buildDir,
                buildDir.resolve("test_stub.masm"),
                buildDir.resolve(name + "_linked.mbin"),
                buildDir.resolve("stdin.txt"));
    }

    private static final class TestPaths {
        final Path source;
        final Path buildDir;
        final Path stub;
        final Path linked;
        final Path input;

        TestPaths(Path source, Path buildDir, Path stub, Path linked, Path input) {
            this.source = source;
            this.buildDir = buildDir;
            this.stub = stub;
            this.linked = linked;
            this.input = input;
        }
    }

    private static final class CommandResult {
        final int status;
        final String output;

        CommandResult(int status, String output) {
            this.status = status;
            this.output = output;
        }
    }
}
