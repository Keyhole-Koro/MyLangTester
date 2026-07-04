import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            if (Files.isReadable(dir.resolve("qa/build_toolchain.py"))) {
                return dir;
            }
        }
        throw new IOException("mytest: could not find repo root containing qa/build_toolchain.py");
    }

    public static boolean run(Path repo, Path testPath) throws IOException, InterruptedException {
        Path absTest = testPath.toRealPath();
        String base = testBasename(absTest);
        TestPaths paths = derivePaths(repo, absTest, base, base);

        try {
            TestMeta meta = TestParser.readMetadataAndWriteSource(absTest, paths.source);
            if (meta.name.isEmpty()) {
                meta.name = base;
            }
            paths = derivePaths(repo, absTest, base, meta.name);

            if (!buildTest(repo, meta, paths)) {
                return false;
            }
            if (!executeTest(repo, meta, paths)) {
                return false;
            }
            System.out.printf("[PASS] %s%n", meta.name);
            return true;
        } finally {
            Files.deleteIfExists(paths.source);
        }
    }

    private static boolean buildTest(Path repo, TestMeta meta, TestPaths paths)
            throws IOException, InterruptedException {
        Files.createDirectories(paths.buildDir);

        String importPath = paths.source.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String stub = "import { kernel_main } from \"" + importPath + "\"\n\n"
                + "__START__:\n"
                + "  call kernel_main\n"
                + "  halt\n";
        Files.writeString(paths.stub, stub, StandardCharsets.UTF_8);
        Files.writeString(paths.input, meta.stdinText, StandardCharsets.UTF_8);

        List<String> command = List.of(
                "python3",
                repo.resolve("qa/build_toolchain.py").toString(),
                paths.stub.toString(),
                paths.source.toString(),
                "-o",
                paths.linked.toString(),
                "--build-dir",
                paths.buildDir.toString());

        int status = runQuiet(command);
        if (status != 0) {
            System.err.printf("[FAIL] %s: build failed%n", meta.name);
            return false;
        }
        return true;
    }

    private static boolean executeTest(Path repo, TestMeta meta, TestPaths paths)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(repo.resolve("runtime/MyEmulator/build/myemu").toString());
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
        if (result.status != 0) {
            System.err.printf("[FAIL] %s: emulator exited with %d%n", meta.name, result.status);
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

    private static TestPaths derivePaths(Path repo, Path absTest, String base, String name) {
        Path source = absTest.resolveSibling("." + base + ".mytest.mln");
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
