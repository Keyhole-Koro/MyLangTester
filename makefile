JAVAC = javac

SRC = $(shell find src -name '*.java' | sort)
BUILD_DIR = build
TARGET = $(BUILD_DIR)/mytest
CLASSES = $(BUILD_DIR)/classes

all: $(TARGET)

$(TARGET): $(SRC)
	@mkdir -p $(CLASSES)
	$(JAVAC) -d $(CLASSES) $^
	@printf '%s\n' '#!/bin/sh' 'SCRIPT_DIR=$$(CDPATH= cd -- "$$(dirname -- "$$0")" && pwd)' 'exec java -cp "$$SCRIPT_DIR/classes" Main "$$@"' > $@
	@chmod +x $@

clean:
	rm -rf $(BUILD_DIR)

.PHONY: all clean
