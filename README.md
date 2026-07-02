# MyLangTester

`mytest` is the test runner for MyLang `*.test.mln` files.

It discovers test files, reads `test { ... }` metadata, builds each test through the
MyLang toolchain, runs it in MyEmulator, and checks the expected serial output.

## Build

```bash
make
```

## Usage

```bash
./build/mytest --list path/to/tests
./build/mytest path/to/tests/example.test.mln
```

## Metadata

```mylang
test {
    name: "serial_rx";
    stdin: "PINGq";
    expect: "TEST_PASS";
    step: 10000000;
}
```
