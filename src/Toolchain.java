import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Where the MyComputer toolchain lives, relative to the repository root. */
final class Toolchain {
    static final String BUILD_SCRIPT = "qa/runners/build_toolchain.py";
    static final String COMPILER = "toolchain/MyLangCompiler";
    static final String ASSEMBLER = "toolchain/MyAssembler";
    static final String LINKER = "toolchain/MyLinker";
    static final String EMULATOR = "runtime/MyEmulator";
    static final String TEST_KIT = "toolchain/MyLangTestKit";
    static final String STDLIB = "toolchain/MyStdLib";

    private Toolchain() {
    }

    /** The nearest directory at or above the working directory that holds the build script. */
    static Path findRepoRoot() throws IOException {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            if (Files.isReadable(dir.resolve(BUILD_SCRIPT))) {
                return dir;
            }
        }
        throw new IOException("mytest: could not find repo root containing " + BUILD_SCRIPT);
    }

    static Path assembler(Path repo) {
        return repo.resolve(ASSEMBLER).resolve("build/myas");
    }

    static Path linker(Path repo) {
        return repo.resolve(LINKER).resolve("mllinker");
    }

    static Path emulator(Path repo) {
        return repo.resolve(EMULATOR).resolve("target/release/myemu");
    }
}
