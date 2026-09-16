#!/bin/bash
# Builds cbc from source without going through ant (ant's own "compile"
# target needs build.properties' javacc.dir to point at a real javacc.jar,
# which isn't a given on every machine). This compiles the already
# checked-in, generated parser sources as-is, unless a javacc.jar can be
# found, in which case it regenerates them from Parser.jj first -- so
# editing the grammar and just running this script picks the change up.
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$DIR"

ASM_JAR="$DIR/lib/asm-9.7.1.jar"
BUILD_DIR="$DIR/build/classes"
PARSER_DIR="$DIR/src/net/loveruby/cflat/parser"

if [ ! -f "$ASM_JAR" ]; then
    echo "build.sh: $ASM_JAR not found" 1>&2
    exit 1
fi

JAVACC_JAR="${JAVACC_JAR:-}"
if [ -z "$JAVACC_JAR" ]; then
    for candidate in "$DIR/lib/javacc.jar" /usr/share/java/javacc.jar; do
        if [ -f "$candidate" ]; then
            JAVACC_JAR="$candidate"
            break
        fi
    done
fi

if [ -n "$JAVACC_JAR" ]; then
    echo "build.sh: regenerating parser from Parser.jj with $JAVACC_JAR"
    ( cd "$PARSER_DIR" && java -cp "$JAVACC_JAR" org.javacc.parser.Main Parser.jj )
else
    echo "build.sh: no javacc.jar found (set \$JAVACC_JAR to use one);" \
         "compiling the already-generated parser sources as-is"
fi

echo "build.sh: compiling..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
javac -nowarn -cp "$ASM_JAR" -d "$BUILD_DIR" $(find "$DIR/src" -name "*.java")
echo "build.sh: done -> $BUILD_DIR"
