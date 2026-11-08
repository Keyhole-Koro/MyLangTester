import java.util.Optional;

public final class TestMeta {
    public String name = "";
    // The runner calls this ordinary top-level function from a generated
    // harness instead of rewriting its body.
    public String functionName;
    // The function's body, braces included. Mock targets are found in it.
    public String body;
    public String stdinText = "";
    public String expect = "TEST_PASS";
    public String step = "10000000";
    public Optional<String> timerInterval = Optional.empty();
    public Disk disk = Disk.NONE;

    /** The emulator disk a test runs with. */
    public static final class Disk {
        /** No disk is attached. */
        public static final Disk NONE = new Disk(false, Optional.empty());
        /** A fresh, empty image is created for each run. */
        public static final Disk EMPTY = new Disk(true, Optional.empty());

        public final boolean attached;
        // A test-file-relative image that the runner copies to the run's
        // private disk, so the fixture itself is never modified.
        public final Optional<String> fixture;

        private Disk(boolean attached, Optional<String> fixture) {
            this.attached = attached;
            this.fixture = fixture;
        }

        public static Disk copyOf(String fixture) {
            return new Disk(true, Optional.of(fixture));
        }
    }
}
