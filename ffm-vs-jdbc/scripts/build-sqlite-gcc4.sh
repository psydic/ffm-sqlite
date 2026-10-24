#!/usr/bin/env bash
# Builds native/libsqlite3-gcc4.so the way sqlite-jdbc 3.50.3.0 builds the engine inside its jar: the same
# container image and compiler (xerial/centos5-linux-x86_64, gcc 4.1.2), the same flags (-Os, its -D list),
# the same source patches and its extension functions (from sqlite-jdbc's Makefile and Makefile.common).
# The one deliberate difference: no -fvisibility=hidden, so the sqlite3_* symbols are exported for FFM.
# Needs docker or podman.
set -euo pipefail
cd "$(dirname "$0")/.."
VER=3500300
TAG=3.50.3.0
DOCKER=$(command -v podman || command -v docker)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/src.zip" "https://www.sqlite.org/2025/sqlite-amalgamation-${VER}.zip"
unzip -q "$WORK/src.zip" -d "$WORK"
mv "$WORK/sqlite-amalgamation-${VER}" "$WORK/src"
curl -fsSL -o "$WORK/extension-functions.c" \
  "https://raw.githubusercontent.com/xerial/sqlite-jdbc/${TAG}/src/main/ext/extension-functions.c"
mkdir -p native
"$DOCKER" run --rm -v "$WORK:/work:Z" -w /work docker.io/xerial/centos5-linux-x86_64 bash -ec '
  perl -p -e "s/sqlite3_api;/sqlite3_api = 0;/g" src/sqlite3ext.h > sqlite3ext.h
  perl -p -e "s/^opendb_out:/  if(!db->mallocFailed && rc==SQLITE_OK){ rc = RegisterExtensionFunctions(db); }\nopendb_out:/;" \
      src/sqlite3.c > sqlite3.c.tmp
  perl -p -e "s/^(static const char \* const sqlite3azCompileOpt.+)\$/\1\n\n\/* This has been automatically added by sqlite-jdbc *\/\n  \"JDBC_EXTENSIONS\",/;" \
      sqlite3.c.tmp > sqlite3.c
  cat extension-functions.c >> sqlite3.c
  gcc -o sqlite3.o -c -I. -Isrc -Os -fPIC -m64 \
      -DSQLITE_ENABLE_LOAD_EXTENSION=1 -DSQLITE_HAVE_ISNAN -DHAVE_USLEEP=1 -DSQLITE_ENABLE_COLUMN_METADATA \
      -DSQLITE_CORE -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5 \
      -DSQLITE_ENABLE_RTREE -DSQLITE_ENABLE_STAT4 -DSQLITE_ENABLE_DBSTAT_VTAB -DSQLITE_ENABLE_MATH_FUNCTIONS \
      -DSQLITE_THREADSAFE=1 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DEFAULT_FILE_PERMISSIONS=0666 \
      -DSQLITE_MAX_VARIABLE_NUMBER=250000 -DSQLITE_MAX_MMAP_SIZE=1099511627776 -DSQLITE_MAX_LENGTH=2147483647 \
      -DSQLITE_MAX_COLUMN=32767 -DSQLITE_MAX_SQL_LENGTH=1073741824 -DSQLITE_MAX_FUNCTION_ARG=127 \
      -DSQLITE_MAX_ATTACHED=125 -DSQLITE_MAX_PAGE_COUNT=4294967294 -DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS \
      sqlite3.c
  gcc -Os -fPIC -m64 -o libsqlite3-gcc4.so sqlite3.o -shared -static-libgcc -pthread -lm -ldl
'
cp "$WORK/libsqlite3-gcc4.so" native/
echo "built $(pwd)/native/libsqlite3-gcc4.so"
