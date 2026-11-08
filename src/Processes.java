import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Runs the toolchain programs a test needs and waits for them. */
final class Processes {
    private Processes() {
    }

    /** A finished program: its exit status and its stdout and stderr, interleaved. */
    static final class Result {
        final int status;
        final String output;

        Result(int status, String output) {
            this.status = status;
            this.output = output;
        }
    }

    /** Run a program with stderr merged into stdout, keeping both. */
    static Result capture(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int status = process.waitFor();
        return new Result(status, new String(output.toByteArray(), StandardCharsets.UTF_8));
    }

    /** Run a program in `directory` with stderr merged into stdout, keeping both. */
    static Result capture(List<String> command, Path directory) throws IOException, InterruptedException {
        return capture(new ProcessBuilder(command).directory(directory.toFile()));
    }

    /** Run a program, dropping its stdout and passing its stderr through; return its exit status. */
    static int runQuiet(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectOutput(new File("/dev/null"))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        return process.waitFor();
    }
}
