#!/bin/bash
# Regression suite for the JVM backend (-arch=jvm), run against the
# checked-in test/*.cb files. This is the primary way to test cbc in
# environments that lack a 32-bit x86 toolchain (no "as"/"ld" for -m32,
# e.g. missing multilib libraries) -- see test_cbc.sh for the equivalent
# native-x86 suite, which this script does not replace.
#
# A handful of tests are expected to differ from the native x86 backend
# (or not compile at all) for reasons that are architectural, not bugs --
# see KNOWN_DIFF below. Everything else must match exactly.
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

CBC="${CBC:-../bin/cbc}"
case "$CBC" in
    */*) CBC="$(cd "$(dirname "$CBC")" && pwd)/$(basename "$CBC")" ;;
esac
IMPORT="$DIR/../import"
# Any program using putchar/puts/printf (or a StandardRuntime function
# via the extensible native-library mechanism) needs
# net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime on its runtime
# classpath too, not just the compiler's own.
CBC_CLASSES="$DIR/../build/classes"
SCRATCH="$DIR/.jvmtest_out"
rm -rf "$SCRATCH"
mkdir -p "$SCRATCH"

if [ ! -x "$CBC" ] && ! command -v "$CBC" >/dev/null 2>&1; then
    echo "run_jvm.sh: $CBC not found or not executable -- run bin/build.sh first" 1>&2
    exit 1
fi

pass=0
fail=0
known=0

# name -> why the JVM backend can't/doesn't match x86 here. All of these
# are documented in README.md's "JVM backend" section.
declare -A KNOWN_DIFF=(
    [usertype]="&puts is rejected: intrinsics have no real address on the JVM backend"
    [ptrdiff]="pointers are 8-byte JVM longs here, not 4-byte like x86, so pointer arithmetic differs"
    [sizeof-type]="sizeof(long)/sizeof(T*) is 8 on the JVM backend, not 4 like x86"
    [sizeof-expr]="sizeof(long)/sizeof(T*) is 8 on the JVM backend, not 4 like x86"
    [implicitaddr]="&printf is rejected: intrinsics have no real address on the JVM backend"
    [funcptr]="&printf is rejected: intrinsics have no real address on the JVM backend"
    [gvar]="taking the address of libc's stdin is not supported by the JVM backend"
    [varargs]="&stdout (an external variable, not a function) is rejected the same way as gvar above -- unrelated to varargs themselves, which this backend does support"
)

report() {
    local status="$1" name="$2" detail="${3:-}"
    if [ "$status" = "OK" ]; then
        printf "OK            %s\n" "$name"
        pass=$((pass + 1))
    elif [ -n "${KNOWN_DIFF[$name]:-}" ]; then
        printf "KNOWN-DIFF    %s: %s\n" "$name" "${KNOWN_DIFF[$name]}"
        known=$((known + 1))
    else
        printf "FAIL          %s: %s\n" "$name" "$detail"
        fail=$((fail + 1))
    fi
}

# Compiles NAME.cb in its own scratch subdirectory (so a stray .hb sibling
# in test/, e.g. decloverride.hb, still resolves via the default "."
# search path) and echoes the resulting class name on success.
compile_case() {
    local name="$1" work="$SCRATCH/$1"
    mkdir -p "$work"
    cp "$DIR/$name.cb" "$work/"
    if [ -f "$DIR/$name.hb" ]; then
        cp "$DIR/$name.hb" "$work/"
    fi
    ( cd "$work" && "$CBC" -arch=jvm -I "$IMPORT" -I "$DIR" "$name.cb" ) \
        >"$work/compile.out" 2>"$work/compile.err"
    if [ $? -ne 0 ]; then
        return 1
    fi
    # Every JVM-backend program now compiles alongside a NativeRuntime.class
    # (it extends NativeRuntime directly -- see CodeGenerator's class doc),
    # so this can no longer just take the first *.class file: exclude it by
    # its fixed, hardcoded name to find the actual program class.
    local cls
    cls=$(cd "$work" && ls -- *.class 2>/dev/null | grep -v '^NativeRuntime\.class$' | head -1)
    [ -n "$cls" ] || return 1
    echo "${cls%.class}"
}

# run_case NAME EXPECTED-STDOUT [ARGS...]
run_case() {
    local name="$1" expected="$2"; shift 2
    local jname
    if ! jname=$(compile_case "$name"); then
        report FAIL "$name" "$(grep -v 'Picked up' "$SCRATCH/$name/compile.err" | head -1)"
        return
    fi
    local actual
    actual=$(cd "$SCRATCH/$name" && java -cp .:"$CBC_CLASSES" "$jname" "$@" 2>run.err)
    if [ "$actual" = "$expected" ]; then
        report OK "$name"
    else
        report FAIL "$name" "expected [$expected] got [$actual] ($(grep -v 'Picked up' "$SCRATCH/$name/run.err" | head -1))"
    fi
}

# run_exit0 NAME -- passes if it compiles and exits 0, output not checked.
run_exit0() {
    local name="$1"
    local jname
    if ! jname=$(compile_case "$name"); then
        report FAIL "$name" "$(grep -v 'Picked up' "$SCRATCH/$name/compile.err" | head -1)"
        return
    fi
    ( cd "$SCRATCH/$name" && java -cp .:"$CBC_CLASSES" "$jname" >run.out 2>run.err )
    local st=$?
    if [ $st -eq 0 ]; then
        report OK "$name"
    else
        report FAIL "$name" "exit $st: $(grep -v 'Picked up' "$SCRATCH/$name/run.err" | head -1)"
    fi
}

# --- arithmetic / expressions / control flow ---
run_case integer      "0;0;0;1;1;1;9;9;9;17;17;17"
run_case funcall0     ""
run_case param        "1;2"
run_case lvar1        "1;2"
run_case unaryminus   "-1;0;1"
run_case unaryplus    "1;0;-1"
run_case add          "1;2;3;4;5;6;7;8;9;10;11"
run_case sub          "1;2;3;4;5;6;7;8;9;10;11;12;13"
run_case mul          "1;4;15"
run_case div          "1;2;2;2;4"
run_case mod          "0;0;1;4;7"
run_case assoc        "3"
run_case bitand       "0;1;2;3;4"
run_case bitor        "0;1;2;3;2;6;8;10"
run_case bitxor       "1;2;0;0;2"
run_case bitnot       "-1;-2;0"
run_case lshift       "1;2;4;8;16"
run_case rshift       "16;8;4;2;1"
run_case eq           "0;1;0;0;1;0"
run_case neq          "1;0;1;1;0;1"
run_case gt           "1;0;0"
run_case lt           "0;0;1"
run_case gteq         "1;1;0"
run_case lteq         "0;1;1"
run_case assign       "1;2;2;3;4;5;6;7;8;8;9;10;11;777;S;12"
run_case opassign     "3;4;3;12;4;1;1;7;5;1;4;e;H;76;75;1;3;6;82;81"
run_case inc          "0;1;2;2;3;3;4;5;5"
run_case dec          "4;3;2;2;1;1;0"
run_case logicalnot   "1;0;0;0;1"
run_case condexpr     "OK;OK;OK;OK;OK"
run_case logicaland   "0;0;0;2;OK"
run_case logicalor    "0;1;1;1;OK"
run_case while3       "3;3;2;1;0"
run_case dowhile3     "3;3;2;1;0"
run_case for1         "3;3;2;1;0"
run_case charops      "2;64;-128;0"
run_case charops2     "-2;-64;-128;0"
run_case ucharops     "2;64;128;0"
run_case ucharops2    "254;192;128;0"
run_case shortops     "2;16384;-32768;0"
run_case shortops2    "-2;-16384;-32768;0"
run_case ushortops    "2;16384;32768;0"
run_case ushortops2   "65534;49152;32768;0"
run_case intops       "2;1073741824;-2147483648;0"
run_case uintops      "2;1073741824;2147483648;0"
run_case cast         "25000000;1;1;-1;-1;1;1;-1;-1"
run_case block        "1;2;3;1;OK"
run_case defvar       "1;2;3"
run_case decloverride "77"
run_case gvar         "1;2;OK;NEW"
run_case sgvar        "1;2;OK;NEW"
run_case slvar        "1;2;OK;NEW"
run_case comm         "1;2;OK;NEW"
run_case scomm        "1;2;OK;NEW"
run_case slcomm       "1;2;OK;NEW"
run_case switch "1 or 2"
run_case switch "1 or 2" x
run_case switch "3 or 4" x x
run_case switch "5 or 6" x x x x
run_case switch "other" x x x x x x

# --- exit-code-only tests ---
for t in if1 if2 while1 while2 while-break while-continue dowhile1 dowhile2 \
         dowhile-break dowhile-continue for-break for-continue noreturn \
         staticfunc funcall1 funcall2 funcall3 funcall4 funcall5 longops \
         ulongops varargs; do
    run_exit0 "$t"
done

# varargs.cb itself (above) only exercises &stdout, a separate, already
# known-diff limitation (see KNOWN_DIFF) -- this exercises the JVM
# backend's actual variadic-function support (int/long/double varargs,
# multiple call sites, a nested vararg call as an outer vararg's own
# argument) end to end.
run_case varargs2 "60;0;103;700"

# --- pointers / arrays / structs / unions ---
run_case array          "1;5;9"
run_case array2         "0;0;0"
run_case mdarray        "3;4;5;6;7;8;9;10;11;"
run_case ptrarray       "775;776;777;778;775;776;777;778;775;776;777;778;775;776;777;778;"
run_case struct         "11;22"
run_case struct2        "701;702;703;704"
run_case struct3        "7"
run_case union          "1;2;513"
run_case usertype       "1;2;1;1;3;4;5;6;OK"
run_case pointer        "5;5"
run_case pointer2       "777"
run_case pointer3       "1;777;3;4;1;777;3;4"
run_case pointer4       "777"
run_case ptrmemb        "1;2;3;4;5;6;77;78"
run_case ptrmemb2       "7"
run_case addressof      "OK;OK;OK;OK"
run_case ptrdiff        "-4;-5;-5;-3"
run_case sizeof-type    "1;1;2;2;4;4;4;4;4;4;4;16;12;16;12"
run_case sizeof-struct  "12;20;1;2;6;3"
run_case sizeof-union   "1;1;4;8"
run_case sizeof-expr    "1;2;4;4;4;8;12;16;12"
run_case implicitaddr   ";OK;OK;OK;OK;OK"
run_case const          "16;16;16;msgstring"
run_case initializer    "4;80;0;local"
run_case funcptr        "OK;OK;OK;OK"

# --- preprocessor: variadic macros (incl. GNU ", ##__VA_ARGS__" comma
# elision), predefined macros, and #line's effect on __LINE__ ---
run_case preprocessor   "42;noargs;witharg:7;1;0;1;1000"

echo
echo "pass=$pass known-diff=$known fail=$fail"
rm -rf "$SCRATCH"
[ $fail -eq 0 ]
