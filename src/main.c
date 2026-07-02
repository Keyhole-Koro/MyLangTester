#define _POSIX_C_SOURCE 200809L
#define _XOPEN_SOURCE 700

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

typedef struct {
    char **items;
    size_t count;
    size_t capacity;
} TestList;

typedef struct {
    char name[128];
    char stdin_text[1024];
    char expect[1024];
    char step[64];
    char timer_interval[64];
} TestMeta;

// Result of parsing a `test(...)` declaration: the metadata plus the byte
// ranges (into the original source buffer) needed to regenerate a plain
// MyLang source. All pointers refer into the caller-owned source buffer.
typedef struct {
    TestMeta meta;
    const char *decl_start;  // start of the `test` keyword
    const char *body_start;  // the callback body's opening '{'
    const char *body_end;    // the callback body's closing '}'
    const char *tail;        // first char after the whole `test(...)` statement
} ParsedTest;

typedef struct {
    char *text;
    size_t len;
    size_t cap;
} StringBuf;

static int has_suffix(const char *text, const char *suffix) {
    size_t text_len = strlen(text);
    size_t suffix_len = strlen(suffix);
    if (text_len < suffix_len) return 0;
    return strcmp(text + text_len - suffix_len, suffix) == 0;
}

static char *xstrdup(const char *text) {
    size_t len = strlen(text);
    char *copy = malloc(len + 1);
    if (!copy) return NULL;
    memcpy(copy, text, len + 1);
    return copy;
}

static char *join_path(const char *dir, const char *name) {
    size_t dir_len = strlen(dir);
    size_t name_len = strlen(name);
    int need_slash = dir_len > 0 && dir[dir_len - 1] != '/';
    char *path = malloc(dir_len + (size_t)need_slash + name_len + 1);
    if (!path) return NULL;
    memcpy(path, dir, dir_len);
    if (need_slash) path[dir_len++] = '/';
    memcpy(path + dir_len, name, name_len + 1);
    return path;
}

static int ensure_dir(const char *path) {
    char tmp[PATH_MAX];
    size_t len = strlen(path);
    if (len >= sizeof(tmp)) return 0;
    memcpy(tmp, path, len + 1);
    for (char *p = tmp + 1; *p; p++) {
        if (*p != '/') continue;
        *p = '\0';
        if (mkdir(tmp, 0777) != 0 && errno != EEXIST) return 0;
        *p = '/';
    }
    return mkdir(tmp, 0777) == 0 || errno == EEXIST;
}

static int write_text_file(const char *path, const char *text) {
    FILE *file = fopen(path, "w");
    if (!file) return 0;
    int ok = fputs(text, file) >= 0;
    fclose(file);
    return ok;
}

static char *read_text_file(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        return NULL;
    }
    long size = ftell(file);
    if (size < 0) {
        fclose(file);
        return NULL;
    }
    rewind(file);
    char *text = malloc((size_t)size + 1);
    if (!text) {
        fclose(file);
        return NULL;
    }
    size_t got = fread(text, 1, (size_t)size, file);
    text[got] = '\0';
    fclose(file);
    return text;
}

static int shell_quote_append(StringBuf *buf, const char *text);

static int string_buf_append(StringBuf *buf, const char *text) {
    size_t add = strlen(text);
    if (buf->len + add + 1 > buf->cap) {
        size_t next_cap = buf->cap ? buf->cap : 256;
        while (buf->len + add + 1 > next_cap) next_cap *= 2;
        char *next = realloc(buf->text, next_cap);
        if (!next) return 0;
        buf->text = next;
        buf->cap = next_cap;
    }
    memcpy(buf->text + buf->len, text, add + 1);
    buf->len += add;
    return 1;
}

static void string_buf_free(StringBuf *buf) {
    free(buf->text);
    buf->text = NULL;
    buf->len = 0;
    buf->cap = 0;
}

static int run_command(const char *cmd) {
    int status = system(cmd);
    if (status == -1) return 1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}

static int capture_command(const char *cmd, StringBuf *out) {
    FILE *pipe = popen(cmd, "r");
    if (!pipe) return 1;

    char chunk[1024];
    while (fgets(chunk, sizeof(chunk), pipe)) {
        if (!string_buf_append(out, chunk)) {
            pclose(pipe);
            return 1;
        }
    }

    int status = pclose(pipe);
    if (status == -1) return 1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return 1;
}

static int shell_quote_append(StringBuf *buf, const char *text) {
    if (!string_buf_append(buf, "'")) return 0;
    for (const char *p = text; *p; p++) {
        char one[2] = {*p, '\0'};
        if (*p == '\'') {
            if (!string_buf_append(buf, "'\\''")) return 0;
        } else if (!string_buf_append(buf, one)) {
            return 0;
        }
    }
    return string_buf_append(buf, "'");
}

static int test_list_push(TestList *list, const char *path) {
    if (list->count == list->capacity) {
        size_t next_capacity = list->capacity ? list->capacity * 2 : 16;
        char **next = realloc(list->items, next_capacity * sizeof(char *));
        if (!next) return 0;
        list->items = next;
        list->capacity = next_capacity;
    }
    list->items[list->count] = xstrdup(path);
    if (!list->items[list->count]) return 0;
    list->count++;
    return 1;
}

static void test_list_free(TestList *list) {
    for (size_t i = 0; i < list->count; i++) free(list->items[i]);
    free(list->items);
    list->items = NULL;
    list->count = 0;
    list->capacity = 0;
}

static int compare_string_ptrs(const void *a, const void *b) {
    const char *const *sa = a;
    const char *const *sb = b;
    return strcmp(*sa, *sb);
}

static int discover_tests(const char *path, TestList *tests) {
    struct stat st;
    if (stat(path, &st) != 0) {
        fprintf(stderr, "mytest: cannot stat %s: %s\n", path, strerror(errno));
        return 0;
    }

    if (S_ISREG(st.st_mode)) {
        if (has_suffix(path, ".test.mln")) return test_list_push(tests, path);
        return 1;
    }

    if (!S_ISDIR(st.st_mode)) return 1;

    DIR *dir = opendir(path);
    if (!dir) {
        fprintf(stderr, "mytest: cannot open %s: %s\n", path, strerror(errno));
        return 0;
    }

    int ok = 1;
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        char *child = join_path(path, entry->d_name);
        if (!child) {
            ok = 0;
            break;
        }
        if (!discover_tests(child, tests)) ok = 0;
        free(child);
        if (!ok) break;
    }

    closedir(dir);
    return ok;
}

static char *trim(char *text) {
    while (*text == ' ' || *text == '\t' || *text == '\r' || *text == '\n') text++;
    size_t len = strlen(text);
    while (len > 0) {
        char c = text[len - 1];
        if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
        text[--len] = '\0';
    }
    return text;
}

static int unquote_value(const char *src, char *dst, size_t dst_size) {
    if (dst_size == 0) return 0;
    dst[0] = '\0';
    src = trim((char *)src);
    if (*src != '"') {
        snprintf(dst, dst_size, "%s", src);
        return 1;
    }
    src++;
    size_t used = 0;
    while (*src && *src != '"' && used + 1 < dst_size) {
        if (*src == '\\' && src[1]) {
            src++;
            if (*src == 'n') dst[used++] = '\n';
            else if (*src == 't') dst[used++] = '\t';
            else dst[used++] = *src;
            src++;
            continue;
        }
        dst[used++] = *src++;
    }
    dst[used] = '\0';
    return 1;
}

static void default_meta(TestMeta *meta) {
    memset(meta, 0, sizeof(*meta));
    snprintf(meta->expect, sizeof(meta->expect), "TEST_PASS");
    snprintf(meta->step, sizeof(meta->step), "10000000");
}

static void apply_meta_entry(TestMeta *meta, char *key, char *value) {
    key = trim(key);
    value = trim(value);
    size_t len = strlen(value);
    if (len > 0 && value[len - 1] == ';') value[len - 1] = '\0';
    value = trim(value);

    if (strcmp(key, "name") == 0) unquote_value(value, meta->name, sizeof(meta->name));
    else if (strcmp(key, "stdin") == 0) unquote_value(value, meta->stdin_text, sizeof(meta->stdin_text));
    else if (strcmp(key, "expect") == 0) unquote_value(value, meta->expect, sizeof(meta->expect));
    else if (strcmp(key, "step") == 0) snprintf(meta->step, sizeof(meta->step), "%s", value);
    else if (strcmp(key, "timer_interval") == 0 || strcmp(key, "timer-interval") == 0) {
        snprintf(meta->timer_interval, sizeof(meta->timer_interval), "%s", value);
    }
}

static int parse_test_block(TestMeta *meta, char *block) {
    char *line = strtok(block, "\n");
    while (line) {
        char *text = trim(line);
        if (*text && strncmp(text, "//", 2) != 0) {
            char *colon = strchr(text, ':');
            if (!colon) {
                fprintf(stderr, "mytest: invalid test metadata line: %s\n", text);
                return 0;
            }
            *colon = '\0';
            apply_meta_entry(meta, text, colon + 1);
        }
        line = strtok(NULL, "\n");
    }
    return 1;
}

static char *skip_ws(char *p) {
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    return p;
}

static int is_ident_char(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
}

static int is_test_keyword_at(char *source, char *p) {
    if (strncmp(p, "test", 4) != 0) return 0;
    if (p > source && is_ident_char(p[-1])) return 0;
    return !is_ident_char(p[4]);
}

// Scan forward for the first `test` keyword that is not inside a string,
// char literal, or comment. `source` is the buffer origin (for the left-hand
// identifier-boundary check); `from` is where scanning starts.
static char *find_test_keyword(char *source, char *from) {
    int in_string = 0;
    int in_char = 0;
    int in_line_comment = 0;
    int in_block_comment = 0;
    for (char *p = from; *p; p++) {
        if (in_line_comment) {
            if (*p == '\n') in_line_comment = 0;
            continue;
        }
        if (in_block_comment) {
            if (*p == '*' && p[1] == '/') {
                in_block_comment = 0;
                p++;
            }
            continue;
        }
        if (in_string) {
            if (*p == '\\' && p[1]) p++;
            else if (*p == '"') in_string = 0;
            continue;
        }
        if (in_char) {
            if (*p == '\\' && p[1]) p++;
            else if (*p == '\'') in_char = 0;
            continue;
        }
        if (*p == '/' && p[1] == '/') {
            in_line_comment = 1;
            p++;
        } else if (*p == '/' && p[1] == '*') {
            in_block_comment = 1;
            p++;
        } else if (*p == '"') in_string = 1;
        else if (*p == '\'') in_char = 1;
        else if (is_test_keyword_at(source, p)) {
            return p;
        }
    }
    return NULL;
}

static char *find_matching(char *open, char open_char, char close_char) {
    int depth = 0;
    int in_string = 0;
    int in_char = 0;
    int in_line_comment = 0;
    int in_block_comment = 0;
    for (char *p = open; *p; p++) {
        if (in_line_comment) {
            if (*p == '\n') in_line_comment = 0;
            continue;
        }
        if (in_block_comment) {
            if (*p == '*' && p[1] == '/') {
                in_block_comment = 0;
                p++;
            }
            continue;
        }
        if (in_string) {
            if (*p == '\\' && p[1]) p++;
            else if (*p == '"') in_string = 0;
            continue;
        }
        if (in_char) {
            if (*p == '\\' && p[1]) p++;
            else if (*p == '\'') in_char = 0;
            continue;
        }
        if (*p == '/' && p[1] == '/') {
            in_line_comment = 1;
            p++;
        } else if (*p == '/' && p[1] == '*') {
            in_block_comment = 1;
            p++;
        } else if (*p == '"') in_string = 1;
        else if (*p == '\'') in_char = 1;
        else if (*p == open_char) depth++;
        else if (*p == close_char) {
            depth--;
            if (depth == 0) return p;
        }
    }
    return NULL;
}

static int append_slice(StringBuf *buf, const char *start, const char *end) {
    if (end < start) return 0;
    size_t len = (size_t)(end - start);
    if (buf->len + len + 1 > buf->cap) {
        size_t next_cap = buf->cap ? buf->cap : 256;
        while (buf->len + len + 1 > next_cap) next_cap *= 2;
        char *next = realloc(buf->text, next_cap);
        if (!next) return 0;
        buf->text = next;
        buf->cap = next_cap;
    }
    memcpy(buf->text + buf->len, start, len);
    buf->len += len;
    buf->text[buf->len] = '\0';
    return 1;
}

// Parse a single `test("name", { ...options }, () => { ...body }) ;`
// declaration into `out`. Pure: fills `out` (metadata already defaulted by the
// caller) with the parsed values and source byte ranges, and never touches the
// filesystem. Returns 1 on success, 0 on a syntax error (message on stderr).
static int parse_test_declaration(const char *path, char *decl_start, ParsedTest *out) {
    out->decl_start = decl_start;

    char *p = skip_ws(decl_start + 4);
    if (*p != '(') return 0;
    p = skip_ws(p + 1);
    if (*p != '"') {
        fprintf(stderr, "mytest: expected test name string in %s\n", path);
        return 0;
    }

    char *name_start = p;
    char *name_end = p + 1;
    while (*name_end && (*name_end != '"' || name_end[-1] == '\\')) name_end++;
    if (*name_end != '"') {
        fprintf(stderr, "mytest: unclosed test name in %s\n", path);
        return 0;
    }
    char saved_name = name_end[1];
    name_end[1] = '\0';
    unquote_value(name_start, out->meta.name, sizeof(out->meta.name));
    name_end[1] = saved_name;

    p = skip_ws(name_end + 1);
    if (*p != ',') {
        fprintf(stderr, "mytest: expected options after test name in %s\n", path);
        return 0;
    }
    p = skip_ws(p + 1);
    if (*p != '{') {
        fprintf(stderr, "mytest: expected options block in %s\n", path);
        return 0;
    }
    char *options_start = p;
    char *options_end = find_matching(options_start, '{', '}');
    if (!options_end) {
        fprintf(stderr, "mytest: unclosed test options block in %s\n", path);
        return 0;
    }
    char saved_options = *options_end;
    *options_end = '\0';
    char *options_copy = xstrdup(options_start + 1);
    *options_end = saved_options;
    if (!options_copy || !parse_test_block(&out->meta, options_copy)) {
        free(options_copy);
        return 0;
    }
    free(options_copy);

    p = skip_ws(options_end + 1);
    if (*p != ',') {
        fprintf(stderr, "mytest: expected callback after test options in %s\n", path);
        return 0;
    }
    p = skip_ws(p + 1);
    if (*p != '(') {
        fprintf(stderr, "mytest: expected callback parameter list in %s\n", path);
        return 0;
    }
    char *params_end = find_matching(p, '(', ')');
    if (!params_end) {
        fprintf(stderr, "mytest: unclosed callback parameter list in %s\n", path);
        return 0;
    }
    if (skip_ws(p + 1) != params_end) {
        fprintf(stderr, "mytest: test callback must not declare parameters in %s\n", path);
        return 0;
    }
    p = skip_ws(params_end + 1);
    if (p[0] != '=' || p[1] != '>') {
        fprintf(stderr, "mytest: expected => before test callback body in %s\n", path);
        return 0;
    }
    p = skip_ws(p + 2);
    if (*p != '{') {
        fprintf(stderr, "mytest: expected callback body in %s\n", path);
        return 0;
    }
    char *body_start = p;
    char *body_end = find_matching(body_start, '{', '}');
    if (!body_end) {
        fprintf(stderr, "mytest: unclosed callback body in %s\n", path);
        return 0;
    }

    p = skip_ws(body_end + 1);
    if (*p != ')') {
        fprintf(stderr, "mytest: expected ')' after test callback in %s\n", path);
        return 0;
    }
    p = skip_ws(p + 1);
    if (*p == ';') p++;

    out->body_start = body_start;
    out->body_end = body_end;
    out->tail = p;
    return 1;
}

// Turn a parsed test into plain MyLang source: everything before the
// declaration, the callback body wrapped in `i32 kernel_main()`, then the tail.
static int generate_source(const char *source, const ParsedTest *test, StringBuf *out) {
    return append_slice(out, source, test->decl_start) &&
           string_buf_append(out, "i32 kernel_main() {\n") &&
           append_slice(out, test->body_start + 1, test->body_end) &&
           string_buf_append(out, "\nreturn 0;\n}\n") &&
           string_buf_append(out, test->tail);
}

// Find and parse the first top-level `test(...)` declaration in `source`.
// Returns 1 on success (with `out` filled), 0 otherwise.
static int parse_test_source(const char *path, char *source, ParsedTest *out) {
    default_meta(&out->meta);

    char *decl_start = find_test_keyword(source, source);
    while (decl_start) {
        char *after = skip_ws(decl_start + 4);
        if (*after == '(') return parse_test_declaration(path, decl_start, out);
        decl_start = find_test_keyword(source, decl_start + 4);
    }

    fprintf(stderr, "mytest: missing top-level test declaration in %s\n", path);
    return 0;
}

// Read a `.test.mln` file, parse its `test(...)` declaration, and write the
// generated plain MyLang source to `out_path`. Fills `meta` for the caller.
static int read_metadata_and_write_source(const char *path, const char *out_path, TestMeta *meta) {
    char *source = read_text_file(path);
    if (!source) {
        fprintf(stderr, "mytest: cannot open %s: %s\n", path, strerror(errno));
        return 0;
    }

    ParsedTest test;
    if (!parse_test_source(path, source, &test)) {
        free(source);
        return 0;
    }
    *meta = test.meta;

    StringBuf sanitized = {0};
    int ok = generate_source(source, &test, &sanitized) &&
             write_text_file(out_path, sanitized.text ? sanitized.text : "");
    string_buf_free(&sanitized);
    free(source);
    return ok;
}

static int find_repo_root(char *out, size_t out_size) {
    char cwd[PATH_MAX];
    if (!getcwd(cwd, sizeof(cwd))) return 0;

    while (1) {
        char candidate[PATH_MAX];
        snprintf(candidate, sizeof(candidate), "%s/qa/build_toolchain.py", cwd);
        if (access(candidate, R_OK) == 0) {
            snprintf(out, out_size, "%s", cwd);
            return 1;
        }

        char *slash = strrchr(cwd, '/');
        if (!slash || slash == cwd) break;
        *slash = '\0';
    }

    fprintf(stderr, "mytest: could not find repo root containing qa/build_toolchain.py\n");
    return 0;
}

static void test_basename(const char *path, char *out, size_t out_size) {
    const char *base = strrchr(path, '/');
    base = base ? base + 1 : path;
    snprintf(out, out_size, "%s", base);
    if (has_suffix(out, ".test.mln")) out[strlen(out) - strlen(".test.mln")] = '\0';
    for (char *p = out; *p; p++) {
        if (*p == '/' || *p == ' ' || *p == '\t' || *p == '.') *p = '_';
    }
}

// Filesystem paths derived for one test run.
typedef struct {
    char source[PATH_MAX];  // generated plain MyLang source (temporary)
    char build_dir[PATH_MAX];
    char stub[PATH_MAX];    // assembly stub that calls kernel_main
    char linked[PATH_MAX];  // final linked binary fed to the emulator
    char input[PATH_MAX];   // stdin fixture
} TestPaths;

// Derive the generated-source path (a dotfile beside the test) and, from the
// test name, the build-directory artifacts.
static void derive_paths(const char *repo, const char *abs_test, const char *base,
                         const char *name, TestPaths *paths) {
    snprintf(paths->source, sizeof(paths->source), "%s", abs_test);
    char *source_name = strrchr(paths->source, '/');
    if (source_name) {
        source_name++;
        snprintf(source_name, (size_t)(paths->source + sizeof(paths->source) - source_name),
                 ".%s.mytest.mln", base);
    } else {
        snprintf(paths->source, sizeof(paths->source), ".%s.mytest.mln", base);
    }

    snprintf(paths->build_dir, sizeof(paths->build_dir), "%s/.mytest/build/%s", repo, name);
    snprintf(paths->stub, sizeof(paths->stub), "%s/test_stub.masm", paths->build_dir);
    snprintf(paths->linked, sizeof(paths->linked), "%s/%s_linked.mbin", paths->build_dir, name);
    snprintf(paths->input, sizeof(paths->input), "%s/stdin.txt", paths->build_dir);
}

// Write the stub and stdin fixture, then compile and link the test into
// `paths->linked`. Returns 1 on success, 0 on failure (message on stderr).
static int build_test(const char *repo, const TestMeta *meta, const TestPaths *paths) {
    if (!ensure_dir(paths->build_dir)) {
        fprintf(stderr, "[FAIL] %s: cannot create build dir %s\n", meta->name, paths->build_dir);
        return 0;
    }

    StringBuf stub = {0};
    string_buf_append(&stub, "import { kernel_main } from ");
    shell_quote_append(&stub, paths->source);
    string_buf_append(&stub, "\n\n__START__:\n  call kernel_main\n  halt\n");
    for (char *p = stub.text; p && *p; p++) {
        if (*p == '\'') *p = '"';
    }
    int stub_ok = write_text_file(paths->stub, stub.text ? stub.text : "");
    string_buf_free(&stub);
    if (!stub_ok) {
        fprintf(stderr, "[FAIL] %s: cannot write stub\n", meta->name);
        return 0;
    }
    if (!write_text_file(paths->input, meta->stdin_text)) {
        fprintf(stderr, "[FAIL] %s: cannot write stdin fixture\n", meta->name);
        return 0;
    }

    StringBuf cmd = {0};
    char tool[PATH_MAX];
    snprintf(tool, sizeof(tool), "%s/qa/build_toolchain.py", repo);
    string_buf_append(&cmd, "python3 ");
    shell_quote_append(&cmd, tool);
    string_buf_append(&cmd, " ");
    shell_quote_append(&cmd, paths->stub);
    string_buf_append(&cmd, " ");
    shell_quote_append(&cmd, paths->source);
    string_buf_append(&cmd, " -o ");
    shell_quote_append(&cmd, paths->linked);
    string_buf_append(&cmd, " --build-dir ");
    shell_quote_append(&cmd, paths->build_dir);
    string_buf_append(&cmd, " >/dev/null");

    int build_status = run_command(cmd.text);
    string_buf_free(&cmd);
    if (build_status != 0) {
        fprintf(stderr, "[FAIL] %s: build failed\n", meta->name);
        return 0;
    }
    return 1;
}

// Run the linked binary in the emulator and check its serial output against the
// expected string. Returns 1 on pass, 0 on failure (message on stderr).
static int execute_test(const char *repo, const TestMeta *meta, const TestPaths *paths) {
    StringBuf run_cmd = {0};
    char emu[PATH_MAX];
    snprintf(emu, sizeof(emu), "%s/runtime/MyEmulator/build/myemu", repo);
    string_buf_append(&run_cmd, "cat ");
    shell_quote_append(&run_cmd, paths->input);
    string_buf_append(&run_cmd, " | ");
    shell_quote_append(&run_cmd, emu);
    string_buf_append(&run_cmd, " -i ");
    shell_quote_append(&run_cmd, paths->linked);
    string_buf_append(&run_cmd, " --headless --step ");
    string_buf_append(&run_cmd, meta->step);
    if (meta->timer_interval[0]) {
        string_buf_append(&run_cmd, " --timer-interval ");
        string_buf_append(&run_cmd, meta->timer_interval);
    }
    string_buf_append(&run_cmd, " 2>&1");

    StringBuf output = {0};
    int run_status = capture_command(run_cmd.text, &output);
    string_buf_free(&run_cmd);

    int ok = 1;
    if (run_status != 0) {
        fprintf(stderr, "[FAIL] %s: emulator exited with %d\n", meta->name, run_status);
        ok = 0;
    } else if (meta->expect[0] && (!output.text || !strstr(output.text, meta->expect))) {
        fprintf(stderr, "[FAIL] %s: expected output %s\n", meta->name, meta->expect);
        ok = 0;
    }
    if (!ok && output.text) fprintf(stderr, "%s\n", output.text);
    string_buf_free(&output);
    return ok;
}

static int run_test(const char *repo, const char *test_path) {
    char abs_test[PATH_MAX];
    if (!realpath(test_path, abs_test)) {
        fprintf(stderr, "[FAIL] %s: %s\n", test_path, strerror(errno));
        return 1;
    }

    char base[128];
    test_basename(abs_test, base, sizeof(base));

    TestMeta meta;
    TestPaths paths;
    // The generated-source path only depends on the file location, so derive it
    // first to write the source; the build-dir paths depend on meta.name.
    derive_paths(repo, abs_test, base, base, &paths);

    int failed = 1;
    if (!read_metadata_and_write_source(abs_test, paths.source, &meta)) {
        // May have left a partial source file if writing failed mid-way.
        goto cleanup;
    }
    if (meta.name[0] == '\0') snprintf(meta.name, sizeof(meta.name), "%s", base);
    // Re-derive so build artifacts land under the (possibly custom) test name.
    derive_paths(repo, abs_test, base, meta.name, &paths);

    if (!build_test(repo, &meta, &paths)) goto cleanup;
    if (!execute_test(repo, &meta, &paths)) goto cleanup;

    printf("[PASS] %s\n", meta.name);
    failed = 0;

cleanup:
    unlink(paths.source);
    return failed;
}

static void print_usage(FILE *out) {
    fprintf(out, "usage: mytest [--list] <path>...\n");
    fprintf(out, "       mytest --help\n");
    fprintf(out, "       mytest --version\n");
}

int main(int argc, char **argv) {
    int list_only = 0;
    TestList tests = {0};

    if (argc == 1) {
        print_usage(stderr);
        return 2;
    }

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--help") == 0 || strcmp(argv[i], "-h") == 0) {
            print_usage(stdout);
            return 0;
        }
        if (strcmp(argv[i], "--version") == 0) {
            printf("mytest 0.1.0\n");
            return 0;
        }
        if (strcmp(argv[i], "--list") == 0) {
            list_only = 1;
            continue;
        }
        if (!discover_tests(argv[i], &tests)) {
            test_list_free(&tests);
            return 1;
        }
    }

    qsort(tests.items, tests.count, sizeof(char *), compare_string_ptrs);

    if (list_only) {
        for (size_t i = 0; i < tests.count; i++) printf("%s\n", tests.items[i]);
    } else {
        char repo[PATH_MAX];
        if (!find_repo_root(repo, sizeof(repo))) {
            test_list_free(&tests);
            return 1;
        }

        int failed = 0;
        for (size_t i = 0; i < tests.count; i++) {
            if (run_test(repo, tests.items[i]) != 0) failed++;
        }
        if (failed) {
            fprintf(stderr, "[FAIL] %d test(s) failed\n", failed);
            test_list_free(&tests);
            return 1;
        }
    }

    test_list_free(&tests);
    return 0;
}
