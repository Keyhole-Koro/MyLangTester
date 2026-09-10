public final class TestMeta {
    public String name = "";
    // Set only for the comment-annotation form. The runner calls this
    // ordinary top-level function from a generated harness instead of
    // rewriting its body.
    public String functionName = "";
    public int bodyStart = -1;
    public int bodyEnd = -1;
    public String stdinText = "";
    public String expect = "TEST_PASS";
    public String step = "10000000";
    public String timerInterval = "";
    // A fresh, empty emulator disk is created for this test invocation.
    public boolean disk = false;
    // When non-empty, the runner copies this test-file-relative fixture to
    // the per-invocation disk path instead of creating an empty image.
    public String diskFixture = "";
}
