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
            tests.addAll(TestDiscoverer.discover(Path.of(arg)));
        }

        Collections.sort(tests);

        if (listOnly) {
            for (Path test : tests) {
                System.out.println(test);
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
        out.println("       mytest --help");
        out.println("       mytest --version");
    }
}
