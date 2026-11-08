import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class TestDiscoverer {
    /** What a test file's name ends with. */
    public static final String SUFFIX = ".test.mln";

    private TestDiscoverer() {
    }

    public static List<Path> discover(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("mytest: cannot stat " + path);
        }
        if (Files.isRegularFile(path)) {
            return isTestFile(path) ? List.of(path) : List.of();
        }
        if (!Files.isDirectory(path)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(path)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(TestDiscoverer::isTestFile)
                    .collect(Collectors.toList());
        }
    }

    private static boolean isTestFile(Path path) {
        return path.toString().endsWith(SUFFIX);
    }
}
