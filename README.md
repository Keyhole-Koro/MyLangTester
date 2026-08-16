# MyLangTester

`mytest` is the test runner for MyLang `*.test.mln` files.

It discovers test files, reads top-level `test(...)` declarations, builds each test
through the MyLang toolchain, runs it in MyEmulator, and checks the expected serial
output.

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
import test from "libs/test.mln";

test("serial_rx", {
    stdin: "PINGq";
    expect: "TEST_PASS";
    step: 10000000;
}, () => {
    test.pass();
});
```

## Generated Source

The test body is rewritten into a `kernel_main` and written next to the test file
as `<stem>_gen_test[.modifiers].mln`, then deleted once the run finishes. mlc reads
the source profile out of the filename, so the modifiers are carried over and the
marker is joined with an underscore:

| test file | generated source |
| --- | --- |
| `serial_rx.test.mln` | `serial_rx_gen_test.mln` |
| `dom_lowering.dom.test.mln` | `dom_lowering_gen_test.dom.mln` |

A `.dom.test.mln` test therefore keeps DOM syntax, and imports resolve as they do
from the test file itself because the generated source is its sibling.
