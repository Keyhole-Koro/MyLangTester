import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        boolean listOnly = false;
        boolean compilerMode = false;
        boolean compilerE2EMode = false;
        List<Path> inputs = new ArrayList<>();
        List<Path> tests = new ArrayList<>();

        if (args.length == 0) {
            printUsage(System.err);
            System.exit(2);
        }

        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                printUsage(System.out);
                return;
            }
            if (arg.equals("--version")) {
                System.out.println("mytest 0.1.0");
                return;
            }
            if (arg.equals("--list")) {
                listOnly = true;
                continue;
            }
            if (arg.equals("--compiler")) {
                compilerMode = true;
                continue;
            }
            if (arg.equals("--compiler-e2e")) {
                compilerE2EMode = true;
                continue;
            }
            inputs.add(Path.of(arg));
        }

        if (compilerMode || compilerE2EMode) {
            if (compilerMode && compilerE2EMode) {
                throw new IllegalArgumentException("mytest: choose either --compiler or --compiler-e2e");
            }
            if (inputs.size() != 1) {
                throw new IllegalArgumentException("mytest compiler mode expects exactly one tests directory");
            }
            Path repo = TestRunner.findRepoRoot();
            if (listOnly) {
                if (compilerE2EMode) {
                    throw new IllegalArgumentException("mytest --list is only available with --compiler");
                }
                CompilerTestRunner.list(inputs.get(0));
                return;
            }
            boolean passed = compilerMode
                    ? CompilerTestRunner.run(repo, inputs.get(0))
                    : CompilerTestRunner.runE2E(repo, inputs.get(0));
            if (!passed) {
                System.exit(1);
            }
            return;
        }

        for (Path input : inputs) {
            tests.addAll(TestDiscoverer.discover(input));
        }
        Collections.sort(tests);

        if (listOnly) {
            for (Path test : tests) {
                List<TestMeta> annotated = TestParser.readAnnotatedTests(test);
                if (annotated.isEmpty()) {
                    System.out.println(test);
                } else {
                    for (TestMeta meta : annotated) {
                        System.out.printf("%s::%s%n", test, meta.name);
                    }
                }
            }
            return;
        }

        Path repo = TestRunner.findRepoRoot();
        int failed = 0;
        for (Path test : tests) {
            if (!TestRunner.run(repo, test)) {
                failed++;
            }
        }

        if (failed > 0) {
            System.err.printf("[FAIL] %d test(s) failed%n", failed);
            System.exit(1);
        }
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("usage: mytest [--list] <path>...");
        out.println("       mytest [--list] --compiler <MyLangCompiler/tests>");
        out.println("       mytest --compiler-e2e <MyLangCompiler/tests>");
        out.println("       mytest --help");
        out.println("       mytest --version");
    }
}
