# MyLangTester

`mytest` is the test runner for MyLang `*.test.mln` files.

It discovers test files, reads `/*@Test*/` pragmas attached to ordinary
top-level functions, builds each case through the MyLang toolchain, runs it in
MyEmulator, and checks the expected serial output.

## TestKit runtime

New tests are linked with `toolchain/MyLangTestKit` automatically. A normal
test-body return emits `TEST_PASS:<name>`, and `MyStdLib/assert.mln` failures
emit `TEST_FAIL:<reason>` through the TestKit `assert_fail` hook. For a test
that completes from another task or interrupt-driven callback, import
`runtime/testkit.mln` and call `testkit.pass(name)` or `testkit.fail(reason)`.

The obsolete kernel-local `tests/libs/test.mln` runtime has been removed; new
and legacy-declaration tests both use TestKit's assertion bridge.

## Mock and Spy

TestKit-backed tests can intercept a direct function call without changing
production code:

```mylang
import mock from "path/to/MyLangTestKit/runtime/facade.mln";

mock.spy(ssd.read_block)
    .when(2, mock.any())
    .ret(-1);
```

`mock.of(target)` requires a configured matching rule; an unmatched call fails
the test. `mock.spy(target)` uses the original implementation when no rule
matches. `ret(value).then_ret(next)` supplies successive return values.
`mock.calls(target)` returns the exact intercepted-call count, while
`mock.called_with(target, ...)` searches the latest sixteen calls with exact
values, `mock.any()`, or `mock.match(predicate)` matchers. `mock.times`,
`mock.once`, and `mock.never` are boolean verification helpers intended for
ordinary `assert` calls.

The facade carries six ordinary ABI words (three registers and three stack
arguments). Struct/array and `Result` returns use `.call(fake)`; their hidden
result buffer is forwarded to the fake and to Spy fallback. An aggregate Spy
fake may delegate with direct `return mock.call_original(args...);`. `mytest`
discovers targets from code tokens (comments and strings are ignored), creates
the entries, and passes redirects through MLC and the linker, including for
calls within the same source module. An unmatched Mock reports
`TEST_FAIL:mock.unexpected:<target>`.

For scheduler tests, configure `mock.set_context_provider(scheduler.current_context)`
before spawning tasks. TestKit then keeps active mock state, return cursors,
and histories per task while `calls` and `called_with` aggregate them.

## Build

Requires JDK 11 or newer with `javac` and `java` on `PATH`.

```bash
make
```

## Usage

```bash
./build/mytest --list path/to/tests
./build/mytest path/to/tests/example.test.mln
```

## Test Declaration

```mylang
import assert from "path/to/MyStdLib/assert.mln";

/*@Test "addition" {
    step: 1000000;
}*/
void addition() {
    assert.assert_true(1 + 1 == 2, "addition");
}
```

The name is required. The optional block retains the existing `key: value;`
metadata, including `stdin`, `expect`, `step`, and `timer_interval`.
`disk: true` supplies a fresh empty disk. `disk: "fixtures/seed.img"` copies a
test-file-relative fixture to a private execution disk, so the fixture itself
is never modified.
`mytest --list` prints every annotated case as `<file>::<name>`. Multiple
annotated functions in one file are built and run independently, so their
TestKit Mock, Spy, and call-history state cannot leak into another case.

The legacy top-level `test(...)` declaration is still supported during the
migration, but new tests should use `/*@Test*/`.

## Generated Source

For an annotated test, the function body is never rewritten. `mytest` writes a
small `kernel_main` harness next to the test file, imports the annotated
function, calls it, and emits the TestKit pass verdict on normal return. The
harness is deleted once the run finishes. mlc reads the source profile out of
the filename, so modifiers are carried over and the marker is joined with an
underscore:

| test file | generated source |
| --- | --- |
| `serial_rx.test.mln` | `serial_rx_gen_test.mln` |
| `dom_lowering.dom.test.mln` | `dom_lowering_gen_test.dom.mln` |

A `.dom.test.mln` test therefore keeps DOM syntax, and imports resolve as they do
from the test file itself because the generated harness is its sibling. Legacy
`test(...)` files still use the older body-rewriting harness until migrated.
