import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs compiler-facing fixture tests without embedding a Python runner in
 * MyLangCompiler. The fixtures' expected diagnostics are declared beside them
 * in tests/diagnostics.cases.
 */
public final class CompilerTestRunner {
    private CompilerTestRunner() {
    }

    public static boolean run(Path repo, Path root) throws IOException, InterruptedException {
        Path tests = root.toRealPath();
        Path compiler = repo.resolve(Toolchain.COMPILER).toRealPath();
        if (!Files.isDirectory(tests.resolve("succeed")) || !Files.isDirectory(tests.resolve("fail"))) {
            throw new IOException("mytest --compiler expects a MyLangCompiler tests directory: " + tests);
        }
        Path manifest = tests.resolve("diagnostics.cases");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("mytest --compiler: missing " + manifest);
        }
        Expectations expected = readExpectations(manifest);
        if (!runBuild(compiler)) return false;

        Path work = Files.createTempDirectory("mytest-compiler-");
        Results results = new Results();
        try {
            runCompileTree(compiler, tests.resolve("succeed"), true, null, expected, work, results);
            runCompileTree(compiler, tests.resolve("fail"), false, null, expected, work, results);
            // A warn fixture compiles and reports at least one warning.
            if (Files.isDirectory(tests.resolve("warn"))) {
                runCompileTree(compiler, tests.resolve("warn"), true, "warning[", expected, work, results);
            }
            runGenericCases(compiler, tests.resolve("generic_cases"), expected, work, results);
            for (ProfileCase profile : expected.profiles) {
                runProfileCase(compiler, work, profile, results);
            }
            runSyntaxCases(compiler, tests.resolve("syntax_check"), false, expected, results);
            runSyntaxCases(compiler, tests.resolve("token"), true, expected, results);
        } finally {
            deleteTree(work);
        }
        System.out.printf("[SUMMARY] compiler fixtures: %d passed, %d failed%n", results.passed, results.failed);
        return results.failed == 0;
    }

    /** Execute the declarative register-result matrix formerly held in a Python script. */
    public static boolean runE2E(Path repo, Path root) throws IOException, InterruptedException {
        Path tests = root.toRealPath();
        Path compiler = repo.resolve(Toolchain.COMPILER).toRealPath();
        Path casesFile = tests.resolve("e2e.cases");
        if (!Files.isRegularFile(casesFile)) {
            throw new IOException("mytest --compiler-e2e: missing " + casesFile);
        }
        if (!runBuild(compiler) || !runE2EBuild(repo)) return false;

        Results results = new Results();
        Path work = Files.createTempDirectory("mytest-compiler-e2e-");
        // Each case has its own directory, so they run side by side. Results are
        // reported in manifest order, so the output does not depend on timing.
        ExecutorService pool = Executors.newFixedThreadPool(jobs());
        try {
            List<Future<Results>> running = new ArrayList<>();
            for (E2ECase test : readE2ECases(casesFile)) {
                running.add(pool.submit(() -> {
                    Results outcome = Results.deferred();
                    runE2ECase(repo, compiler, tests, test, work, outcome);
                    return outcome;
                }));
            }
            for (Future<Results> outcome : running) {
                try {
                    outcome.get().reportTo(results);
                } catch (ExecutionException e) {
                    throw new IOException("mytest --compiler-e2e: a case could not run", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.MINUTES);
            deleteTree(work);
        }
        System.out.printf("[SUMMARY] compiler e2e: %d passed, %d failed%n", results.passed, results.failed);
        return results.failed == 0;
    }

    /** MYTEST_JOBS overrides the default of one case per processor. */
    private static int jobs() {
        String configured = System.getenv("MYTEST_JOBS");
        if (configured != null && !configured.isEmpty()) return Math.max(1, Integer.parseInt(configured));
        return Runtime.getRuntime().availableProcessors();
    }

    public static void list(Path root) throws IOException {
        Path tests = root.toRealPath();
        for (String section : List.of("succeed", "fail", "generic_cases", "syntax_check", "token")) {
            Path directory = tests.resolve(section);
            if (!Files.isDirectory(directory)) continue;
            for (Path source : mlnFiles(directory)) {
                System.out.printf("%s::%s%n", section, directory.relativize(source));
            }
        }
        System.out.println("source-profiles::canonical-filename-validation");
    }

    /** Read diagnostics.cases; the format is described at the top of that file. */
    private static Expectations readExpectations(Path manifest) throws IOException {
        Expectations expected = new Expectations();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\|", -1);
            for (int i = 0; i < fields.length; i++) fields[i] = fields[i].trim();
            String kind = fields[0];
            boolean named = fields.length > 1 && !fields[1].isEmpty();
            if (kind.equals("error") && fields.length == 3 && named) {
                if (expected.errors.put(fields[1], fields[2]) != null) {
                    throw new IOException("mytest --compiler: " + fields[1] + " is listed twice in "
                            + manifest + ":" + lineNumber);
                }
            } else if (kind.equals("skip") && fields.length == 2 && named) {
                expected.skipped.add(fields[1]);
            } else if ((kind.equals("profile") || kind.equals("profile-error"))
                    && (fields.length == 3 || fields.length == 4) && named) {
                String source = fields.length == 4 ? fields[3] : "i32 main() { return 0; }";
                expected.profiles.add(new ProfileCase(fields[1], source + "\n", kind.equals("profile"), fields[2]));
            } else {
                throw new IOException("mytest --compiler: malformed " + manifest + ":" + lineNumber);
            }
        }
        return expected;
    }

    private static boolean runBuild(Path compiler) throws IOException, InterruptedException {
        Processes.Result build = Processes.capture(List.of("make", "all", "syntax-check"), compiler);
        if (build.status == 0) return true;
        System.err.println("[FAIL] compiler fixture setup: could not build MyLangCompiler");
        System.err.print(build.output);
        return false;
    }

    private static boolean runE2EBuild(Path repo) throws IOException, InterruptedException {
        List<String> locations = List.of(Toolchain.ASSEMBLER, Toolchain.LINKER, Toolchain.EMULATOR);
        for (String location : locations) {
            Processes.Result build = Processes.capture(
                    List.of("make", "-C", repo.resolve(location).toString(), "all"), repo);
            if (build.status == 0) continue;
            if (location.equals(Toolchain.EMULATOR) && Files.isExecutable(Toolchain.emulator(repo))) {
                System.err.println("[WARN] could not rebuild MyEmulator; using existing binary");
                continue;
            }
            System.err.printf("[FAIL] compiler e2e setup: could not build %s%n%s", location, build.output);
            return false;
        }
        return true;
    }

    private static List<E2ECase> readE2ECases(Path manifest) throws IOException {
        List<E2ECase> cases = new ArrayList<>();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\|", -1);
            // name | expected R1 | sources [| compiler flags]
            if ((fields.length != 3 && fields.length != 4)
                    || fields[0].trim().isEmpty() || fields[2].trim().isEmpty()) {
                throw new IOException("mytest --compiler-e2e: malformed " + manifest + ":" + lineNumber);
            }
            long expected;
            try {
                expected = Long.decode(fields[1].trim());
            } catch (NumberFormatException e) {
                throw new IOException("mytest --compiler-e2e: invalid register value at "
                        + manifest + ":" + lineNumber, e);
            }
            List<String> sources = new ArrayList<>();
            for (String source : fields[2].split(",")) sources.add(source.trim());
            List<String> flags = new ArrayList<>();
            if (fields.length == 4) {
                for (String flag : fields[3].trim().split("\\s+")) {
                    if (!flag.isEmpty()) flags.add(flag);
                }
            }
            cases.add(new E2ECase(fields[0].trim(), expected, sources, flags));
        }
        return cases;
    }

    private static void runE2ECase(Path repo, Path compiler, Path tests, E2ECase test,
                                   Path work, Results results) throws IOException, InterruptedException {
        Path directory = work.resolve(safeName(test.name));
        Files.createDirectories(directory);
        List<String> objects = new ArrayList<>();
        Path assembler = Toolchain.assembler(repo);
        Path linker = Toolchain.linker(repo);
        // A case may link MyStdLib modules (`../../MyStdLib/text/str.mln`) alongside
        // its own sources; nothing else outside the tests tree.
        Path stdlib = repo.resolve(Toolchain.STDLIB).normalize();
        for (int index = 0; index < test.sources.size(); index++) {
            Path source = tests.resolve(test.sources.get(index)).normalize();
            boolean allowed = source.startsWith(tests) || source.startsWith(stdlib);
            if (!allowed || !Files.isRegularFile(source)) {
                results.fail(test.name, "missing source " + test.sources.get(index));
                return;
            }
            Path assembly = directory.resolve(index + ".masm");
            Path prelink = directory.resolve(index + ".mbin");
            Path object = directory.resolve(index + ".mobj");
            List<String> compile = new ArrayList<>();
            compile.add(compiler.resolve("mlc").toString());
            compile.addAll(test.flags);
            compile.add(source.toString());
            compile.add(assembly.toString());
            Processes.Result compiled = Processes.capture(compile, directory);
            if (compiled.status != 0) {
                results.fail(test.name, "compilation failed\n" + compiled.output);
                return;
            }
            Processes.Result assembled = Processes.capture(List.of(assembler.toString(), assembly.toString(),
                    prelink.toString(), "--obj", object.toString()), directory);
            if (assembled.status != 0) {
                results.fail(test.name, "assembly failed\n" + assembled.output);
                return;
            }
            objects.add(object.toString());
        }
        Path binary = directory.resolve(test.name + ".mbin");
        List<String> link = new ArrayList<>();
        link.add(linker.toString());
        link.add(binary.toString());
        link.addAll(objects);
        Processes.Result linked = Processes.capture(link, directory);
        if (linked.status != 0) {
            results.fail(test.name, "link failed\n" + linked.output);
            return;
        }
        Processes.Result executed = Processes.capture(List.of(Toolchain.emulator(repo).toString(),
                "-i", binary.toString(), "--headless", "--reg", "R1"), directory);
        if (executed.status != 0) {
            results.fail(test.name, "emulator failed\n" + executed.output);
            return;
        }
        String value = lastLine(executed.output);
        try {
            long actual = Long.decode(value);
            if (actual == test.expected) {
                results.pass(test.name + " R1=" + String.format("0x%x", actual));
            } else {
                results.fail(test.name, String.format("R1=0x%x, expected 0x%x", actual, test.expected));
            }
        } catch (NumberFormatException e) {
            results.fail(test.name, "could not parse R1 from emulator output: " + value);
        }
    }

    private static String lastLine(String output) {
        String value = "";
        for (String line : output.split("\\R")) {
            if (!line.trim().isEmpty()) value = line.trim();
        }
        return value;
    }

    private static void runCompileTree(Path compiler, Path directory, boolean expectedSuccess,
                                       String expectedError, Expectations expected, Path work, Results results)
            throws IOException, InterruptedException {
        for (Path source : mlnFiles(directory)) {
            String name = fixtureName(directory, source);
            if (expected.skipped.contains(name)) {
                System.out.printf("[SKIP] deferred fixture %s%n", name);
                continue;
            }
            runCompileCase(compiler, source, expectedSuccess, expectedError, name, work, results);
        }
    }

    private static void runGenericCases(Path compiler, Path directory, Expectations expected, Path work,
                                        Results results)
            throws IOException, InterruptedException {
        for (Path source : mlnFiles(directory)) {
            String marker = expected.errors.get(fixtureName(directory, source));
            runCompileCase(compiler, source, marker == null, marker,
                    "generic/" + source.getFileName(), work, results);
        }
    }

    private static void runCompileCase(Path compiler, Path source, boolean expectedSuccess,
                                       String expectedError, String name, Path work, Results results)
            throws IOException, InterruptedException {
        Path output = work.resolve("compile").resolve(safeName(name) + ".masm");
        Files.createDirectories(output.getParent());
        Processes.Result result = Processes.capture(List.of(compiler.resolve("mlc").toString(),
                source.toString(), output.toString()), compiler);
        boolean statusMatches = expectedSuccess == (result.status == 0);
        boolean outputMatches = !expectedSuccess || (Files.isRegularFile(output)
                && result.output.contains("AST parsing completed.")
                && result.output.contains("Semantic analysis completed.")
                && result.output.contains("Code generation completed."));
        boolean diagnosticMatches = expectedError == null || result.output.contains(expectedError);
        if (statusMatches && outputMatches && diagnosticMatches) {
            results.pass(name);
            return;
        }
        String expectation = expectedSuccess ? "successful compilation" : "compiler failure";
        if (expectedError != null) expectation += " containing '" + expectedError + "'";
        results.fail(name, "expected " + expectation + " (exit=" + result.status + ")\n" + result.output);
    }

    private static void runProfileCase(Path compiler, Path work, ProfileCase profile, Results results)
            throws IOException, InterruptedException {
        Path source = work.resolve("profiles").resolve(profile.filename);
        Files.createDirectories(source.getParent());
        Files.writeString(source, profile.source, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        runCompileCase(compiler, source, profile.accepted, profile.text, "source-profile/" + profile.filename,
                work, results);
    }

    private static void runSyntaxCases(Path compiler, Path directory, boolean tokenCases, Expectations expected,
                                       Results results)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(directory)) return;
        Process process = new ProcessBuilder(compiler.resolve("mylang-syntax-check").toString(), "--stdio")
                .directory(compiler.toFile()).redirectErrorStream(true).start();
        try (OutputStream input = process.getOutputStream();
             BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(),
                     StandardCharsets.UTF_8))) {
            String ready = output.readLine();
            if (!"ready".equals(ready)) {
                results.fail(directory.getFileName().toString(), "syntax checker did not become ready: " + ready);
                return;
            }
            for (Path source : mlnFiles(directory)) {
                byte[] bytes = Files.readAllBytes(source);
                input.write(("content " + bytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
                input.write(bytes);
                input.write('\n');
                input.flush();
                String response = output.readLine();
                String name = fixtureName(directory, source);
                String error = expected.errors.get(name);
                String status = error != null ? "\"status\":\"error\"" : "\"status\":\"ok\"";
                if (response != null && response.contains(status)
                        && (!tokenCases || response.contains("\"tokens\":"))
                        && (error == null || response.contains(error))) {
                    results.pass(name);
                } else {
                    results.fail(name, "unexpected syntax-check response: " + response);
                }
            }
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private static List<Path> mlnFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".mln"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());
        }
    }

    /** A fixture's name relative to the tests directory, as diagnostics.cases spells it. */
    private static String fixtureName(Path directory, Path source) {
        return directory.getFileName() + "/" + directory.relativize(source).toString().replace('\\', '/');
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** What diagnostics.cases says that the fixtures' directories do not. */
    private static final class Expectations {
        // Fixture -> text that must appear when it is rejected.
        final Map<String, String> errors = new HashMap<>();
        final Set<String> skipped = new HashSet<>();
        final List<ProfileCase> profiles = new ArrayList<>();
    }

    /** A source written under `filename` that mlc must accept or refuse. */
    private static final class ProfileCase {
        final String filename;
        final String source;
        final boolean accepted;
        final String text;

        ProfileCase(String filename, String source, boolean accepted, String text) {
            this.filename = filename;
            this.source = source;
            this.accepted = accepted;
            this.text = text;
        }
    }

    private static final class E2ECase {
        final String name;
        final long expected;
        final List<String> sources;
        final List<String> flags;

        E2ECase(String name, long expected, List<String> sources, List<String> flags) {
            this.name = name;
            this.expected = expected;
            this.sources = sources;
            this.flags = flags;
        }
    }

    private static final class Results {
        int passed;
        int failed;
        private final boolean defer;
        private final List<String[]> lines = new ArrayList<>();

        Results() { this(false); }

        private Results(boolean defer) { this.defer = defer; }

        /** Holds its lines until reportTo(), for a case running on another thread. */
        static Results deferred() { return new Results(true); }

        void pass(String name) {
            passed++;
            if (defer) lines.add(new String[] {name});
            else System.out.printf("[PASS] %s%n", name);
        }

        void fail(String name, String detail) {
            failed++;
            if (defer) lines.add(new String[] {name, detail});
            else System.err.printf("[FAIL] %s: %s%n", name, detail);
        }

        void reportTo(Results target) {
            for (String[] line : lines) {
                if (line.length == 1) target.pass(line[0]);
                else target.fail(line[0], line[1]);
            }
        }
    }
}
