CC = gcc
CFLAGS = -Wall -Wextra -Wno-format-truncation -std=c11

SRC = $(shell find src -name '*.c' | sort)
BUILD_DIR = build
TARGET = $(BUILD_DIR)/mytest

all: $(TARGET)

$(TARGET): $(SRC)
	@mkdir -p $(dir $@)
	$(CC) $(CFLAGS) -o $@ $^

clean:
	rm -rf $(BUILD_DIR)

.PHONY: all clean
