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
