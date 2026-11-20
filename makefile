JAVAC = javac

SRC = $(shell find src -name '*.java' | sort)
UNIT_SRC = $(shell find unit -name '*.java' | sort)
BUILD_DIR = build
TARGET = $(BUILD_DIR)/mytest
CLASSES = $(BUILD_DIR)/classes
UNIT_CLASSES = $(BUILD_DIR)/unit-classes

all: $(TARGET)

$(TARGET): $(SRC)
	@mkdir -p $(CLASSES)
	$(JAVAC) -d $(CLASSES) $^
	@printf '%s\n' '#!/bin/sh' 'SCRIPT_DIR=$$(CDPATH= cd -- "$$(dirname -- "$$0")" && pwd)' 'exec java -Djava.io.tmpdir="$${TMPDIR:-/tmp}" -cp "$$SCRIPT_DIR/classes" Main "$$@"' > $@
	@chmod +x $@

# The runner's parsing logic, checked with the JDK alone.
unit-test: $(SRC) $(UNIT_SRC)
	@mkdir -p $(UNIT_CLASSES)
	$(JAVAC) -d $(UNIT_CLASSES) $^
	java -Djava.io.tmpdir="$${TMPDIR:-/tmp}" -cp $(UNIT_CLASSES) UnitTests

# The runner's own cases. They build and run like any test, so the MyComputer
# toolchain and emulator must be built.
test: unit-test $(TARGET)
	./$(TARGET) tests

clean:
	rm -rf $(BUILD_DIR)

.PHONY: all unit-test test clean
