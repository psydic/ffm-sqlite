/*
 * A minimal SQLite extension for ffm-sqlite's tests: rot13(text) and, through a second entry point,
 * rot13_alt(text). Built by scripts/build-test-extension.sh into target/test-ext/libffmtest.so.
 */
#include "sqlite3ext.h"
SQLITE_EXTENSION_INIT1

static void rot13(sqlite3_context *ctx, int argc, sqlite3_value **argv) {
    const unsigned char *in = sqlite3_value_text(argv[0]);
    if (in == 0) {
        sqlite3_result_null(ctx);
        return;
    }
    int n = sqlite3_value_bytes(argv[0]);
    char *out = sqlite3_malloc(n + 1);
    if (out == 0) {
        sqlite3_result_error_nomem(ctx);
        return;
    }
    for (int i = 0; i < n; i++) {
        unsigned char c = in[i];
        if (c >= 'a' && c <= 'z') c = 'a' + (c - 'a' + 13) % 26;
        else if (c >= 'A' && c <= 'Z') c = 'A' + (c - 'A' + 13) % 26;
        out[i] = (char) c;
    }
    sqlite3_result_text(ctx, out, n, sqlite3_free);
}

/* Default entry point: SQLite derives "sqlite3_ffmtest_init" from the file name libffmtest.so. */
int sqlite3_ffmtest_init(sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    SQLITE_EXTENSION_INIT2(pApi);
    return sqlite3_create_function(db, "rot13", 1, SQLITE_UTF8 | SQLITE_DETERMINISTIC, 0, rot13, 0, 0);
}

/* Explicit entry point, selected with loadExtension(file, "ffmtest_alt_init"). */
int ffmtest_alt_init(sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    SQLITE_EXTENSION_INIT2(pApi);
    return sqlite3_create_function(db, "rot13_alt", 1, SQLITE_UTF8 | SQLITE_DETERMINISTIC, 0, rot13, 0, 0);
}

/* An entry point that fails, to test error reporting. */
int ffmtest_fail_init(sqlite3 *db, char **pzErrMsg, const sqlite3_api_routines *pApi) {
    SQLITE_EXTENSION_INIT2(pApi);
    *pzErrMsg = sqlite3_mprintf("ffmtest refuses to load");
    return SQLITE_ERROR;
}
