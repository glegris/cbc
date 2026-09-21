#!/bin/bash
# Regression suite for the JVM backend (-arch=jvm), run against the
# checked-in test/*.c files. This is the primary way to test cbc in
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
    [ptrdiff]="pointers are 8-byte JVM longs here, not 4-byte like x86, so pointer arithmetic differs"
    [sizeof-type]="sizeof(long)/sizeof(T*) is 8 on the JVM backend, not 4 like x86"
    [sizeof-expr]="sizeof(long)/sizeof(T*) is 8 on the JVM backend, not 4 like x86"
    [implicitaddr]="&printf is rejected: taking the address of a variadic function is not supported on the JVM backend"
    [funcptr]="calling through a variadic function pointer is not supported on the JVM backend"
    [varargs]="vfprintf() -- unlike printf() -- has no real implementation on the JVM backend yet (see stdio.h's own doc comment); unrelated to varargs themselves, which this backend does support"
)
# usertype (&puts) and gvar (&stdin) used to be here too: both puts()
# and stdin are real, addressable things now (a defined function and a
# real static FILE object, respectively -- see stdio.h), not a compile-
# time intrinsic/bare extern declaration with nothing behind it.

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

# Compiles NAME.c in its own scratch subdirectory (so a stray .h sibling
# in test/, e.g. decloverride.h, still resolves via a quoted #include's
# own "look next to the including file first" rule) and echoes the
# resulting class name on success.
compile_case() {
    local name="$1" work="$SCRATCH/$1"
    mkdir -p "$work"
    cp "$DIR/$name.c" "$work/"
    if [ -f "$DIR/$name.h" ]; then
        cp "$DIR/$name.h" "$work/"
    fi
    ( cd "$work" && "$CBC" -arch=jvm -I "$IMPORT" -I "$DIR" "$name.c" ) \
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

# run_multifile_case NAME EXPECTED-STDOUT -- compiles test/NAME-*.c
# (lexical order) together as one unit via -arch=jvm's own multi-file
# support (Compiler#buildMultipleSourcesAsOneUnit()), explicitly naming
# the result with -o so the produced class name is predictable.
run_multifile_case() {
    local name="$1" expected="$2"
    local work="$SCRATCH/$name"
    mkdir -p "$work"
    local srcs=()
    for f in "$DIR/$name"-*.c; do
        cp "$f" "$work/"
        srcs+=("$(basename "$f")")
    done
    ( cd "$work" && "$CBC" -arch=jvm -I "$IMPORT" -o "$name" "${srcs[@]}" ) \
        >"$work/compile.out" 2>"$work/compile.err"
    if [ $? -ne 0 ]; then
        report FAIL "$name" "$(grep -v 'Picked up' "$work/compile.err" | head -1)"
        return
    fi
    local actual
    actual=$(cd "$work" && java -cp .:"$CBC_CLASSES" "$name" 2>run.err)
    if [ "$actual" = "$expected" ]; then
        report OK "$name"
    else
        report FAIL "$name" "expected [$expected] got [$actual] ($(grep -v 'Picked up' "$work/run.err" | head -1))"
    fi
}

# assert_error_contains NAME PATTERN... -- compiles NAME.c (plus NAME.h
# if present), expecting the compile to FAIL, and checks stderr contains
# every given substring -- used to confirm a compile error names the
# actual (file, line) with the mistake, not always this top-level file
# (see Preprocessor's LineMap and Parser#location()/#topLevelLocation()).
assert_error_contains() {
    local name="$1"; shift
    local work="$SCRATCH/$name"
    mkdir -p "$work"
    cp "$DIR/$name.c" "$work/"
    if [ -f "$DIR/$name.h" ]; then
        cp "$DIR/$name.h" "$work/"
    fi
    ( cd "$work" && "$CBC" -arch=jvm -I "$IMPORT" -I "$DIR" "$name.c" ) \
        >"$work/compile.out" 2>"$work/compile.err"
    if [ $? -eq 0 ]; then
        report FAIL "$name" "expected a compile error, but it succeeded"
        return
    fi
    local errs missing=""
    errs=$(grep -v 'Picked up' "$work/compile.err")
    for pat in "$@"; do
        grep -qF -- "$pat" <<<"$errs" || missing="$missing [$pat]"
    done
    if [ -z "$missing" ]; then
        report OK "$name"
    else
        report FAIL "$name" "stderr missing:$missing -- got: $errs"
    fi
}

# assert_multifile_error_contains NAME PATTERN... -- same as
# assert_error_contains, but for a multi-file build (see
# run_multifile_case's own doc comment for the NAME-*.c convention).
assert_multifile_error_contains() {
    local name="$1"; shift
    local work="$SCRATCH/$name"
    mkdir -p "$work"
    local srcs=()
    for f in "$DIR/$name"-*.c; do
        cp "$f" "$work/"
        srcs+=("$(basename "$f")")
    done
    ( cd "$work" && "$CBC" -arch=jvm -I "$IMPORT" -o "$name" "${srcs[@]}" ) \
        >"$work/compile.out" 2>"$work/compile.err"
    if [ $? -eq 0 ]; then
        report FAIL "$name" "expected a compile error, but it succeeded"
        return
    fi
    local errs missing=""
    errs=$(grep -v 'Picked up' "$work/compile.err")
    for pat in "$@"; do
        grep -qF -- "$pat" <<<"$errs" || missing="$missing [$pat]"
    done
    if [ -z "$missing" ]; then
        report OK "$name"
    else
        report FAIL "$name" "stderr missing:$missing -- got: $errs"
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

# varargs.c itself (above) only exercises &stdout, a separate, already
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
run_case designated-init "1;2;3;4;10;11;0;0;30;31;1;5;9;0;99;2"
run_case compound-literal-static "7;8;100;200;11;12"
run_case address-of-static "5;5;0;0;1;1;0;0;5;3;9;9;42;6;1;2;3;0"
run_case malloc "0;1;4;9;16;0;0;0;10;20;30;40;4950"
run_case null-pointer-return "1;1"
run_case string-funcs "o;3;3;o world;world;3;1;copy me;dup me;trun;a.b.c.;1;3"
run_case ctype-funcs "1;0;1;0;0;1;1;0;1;0;1;1"
run_case narrowing-cast-return "132767"
run_case stdlib-funcs "3;1;3;2;-123;abc;255;314;xyz;1;1;1;2;3;4;5;1;4"
run_case printf-funcs "7-seven;[   42][42   ][00042][+42][-42];[hi        ][he];[4000000000][ff][FF][0xff][10][123456789];[3.141590][3.14][    3.14];100%;x=5,y=abc,9;12,5;1"
run_case file-io "hello file
Xworld;0;1;0;1"

# --- preprocessor: variadic macros (incl. GNU ", ##__VA_ARGS__" comma
# elision), predefined macros, and #line's effect on __LINE__ ---
run_case preprocessor   "42;noargs;witharg:7;1;0;1;1000"

# stdio.h and string.h both "#include \"stddef.h\"" -- this passing
# (rather than a duplicate NULL/size_t/ptrdiff_t definition error) is
# what actually exercises stddef.h's own include guard.
run_case duplicated-import "OK"

# --- three real bugs found by running a subset of the GCC c-torture
# execute tests through this compiler: a narrowing cast silently
# dropped instead of truncating (isEffectiveCast()), "long long"/
# "unsigned long long" mixed with a plain "int" silently falling back
# to 32-bit arithmetic (usualArithmeticConversion()), and a narrow
# operand's promotion never actually materialized as a cast when it
# happened to already equal the target type (arithmeticImplicitCast())
# -- see arith-conversion.c's own comments for each one ---
run_case arith-conversion "1;255;37;1;"

# --- operator precedence: relational/equality vs. the bitwise ops,
# found via the c-testsuite project's own tests -- see
# operator-precedence.c's own comments ---
run_case operator-precedence "0;1;0;1;5;"

# --- unary +/-/~ must apply integer promotion to their operand
# (C99 6.5.3.3p1), found via the c-testsuite project's own tests --
# see unary-promotion.c's own comments ---
run_case unary-promotion "1;1;1;1;"

# --- "!"'s own result type is always "int" (C99 6.5.3.3p5), found via
# the c-testsuite project's own tests -- see sizeof-not.c ---
run_case sizeof-not "1;4;4;"

# --- a bare ";" (e.g. a whole for/do-while loop body) used to crash
# the first AST visitor to reach it instead of doing nothing, found
# via the c-testsuite project's own tests -- see empty-stmt.c ---
run_case empty-stmt "5;5;"

# --- hex/octal literal overflow crashed instead of using C99's own
# unsigned fallback, and a suffix-less literal was always typed plain
# "int" regardless of its value, found via the c-testsuite project's
# own tests -- see int-literal-overflow.c ---
run_case int-literal-overflow "4294967295;18446744073709551615;1;1;"

# --- lowercase/mixed-case integer suffixes ("100ul", "5ll", ...)
# failed to lex as part of the literal at all, found via the
# c-testsuite project's own tests -- see int-suffix-case.c ---
run_case int-suffix-case "100;5;7;100;5;100;"

# --- several standard C99 integer type-specifier combinations (bare
# "signed"/"unsigned", "signed char", "long int", "unsigned short
# int", ...) had no grammar production at all, found via the
# c-testsuite project's own tests -- see type-specifiers.c ---
run_case type-specifiers "-1;1;-2;100000;30000;4000000000;60000;100;-100;"

# --- a declarator's own "[N]"/"[]" array suffix (attaching to the
# *name*, e.g. "int arr[2];") had no grammar support at all outside of
# a typeref() context (a parameter/cast/sizeof spelling), found via
# the c-testsuite project's own tests -- see array-decl.c ---
run_case array-decl "3;7;30;15;6;8;"

# --- each declarator in a "T d1, d2, ...;" comma list must get its
# own pointer level, not blindly inherit the first declarator's own
# "*" (e.g. "int *p, q;" -- "q" must be plain "int", not "int*"),
# found via the c-testsuite project's own tests -- see
# multi-declarator.c ---
run_case multi-declarator "5;10;4;1;1;1;"

# --- a trailing "," after an enum's last enumerator (C99 6.7.2.2p1)
# was rejected, found via the c-testsuite project's own tests -- see
# enum-trailing-comma.c ---
run_case enum-trailing-comma "0;1;2;1;"

# --- "T x[] = {...};" must infer the array's length from its
# initializer (C99 6.7.8p22) instead of being rejected outright, found
# via the c-testsuite project's own tests -- see
# array-size-inference.c ---
run_case array-size-inference "4;5;4;55;3;16;0;20;"

# --- C89's "tentative definition" (several file-scope declarations of
# the same variable with no initializer -- or with only one of them
# ever supplying one -- refer to the same variable, not a "duplicated
# definition" error), found via the c-testsuite project's own tests --
# see tentative-definition.c ---
run_case tentative-definition "3;0;"

# --- a struct/union tag has always been optional (since K&R): "struct
# { ... } v;" and "struct Name { ... } v;" (a real tag, defined right
# where it's first used as a type) had no grammar support as a
# variable's own type, found via the c-testsuite project's own tests --
# see anonymous-struct.c ---
run_case anonymous-struct "6;3;3;42;5;6;"

# --- a bare, non-"extern" top-level function prototype ("int f(char
# *);", a header's usual content) and its own optional parameter names
# had no grammar support, found via the c-testsuite project's own
# tests -- see bare-prototype.c ---
run_case bare-prototype "5;"

# --- real C's own function-pointer declarator spelling ("int
# (*fp)();", vs. this project's own preexisting "int ()* fp;") had no
# grammar support at all, found via the c-testsuite project's own
# tests -- see funcptr-c-syntax.c ---
run_case funcptr-c-syntax "5;3;8;"

# --- a flexible array member ("int data[];", a struct's last member,
# C99 6.7.2.1p18) used to silently inflate sizeof(struct) by a whole
# pointer's worth of bytes it never actually reserves, instead of
# contributing nothing -- see flexible-array-member.c ---
run_case flexible-array-member "4;5;100;8;"

# --- bit-fields ("unsigned int a : 3;") had no grammar support at
# all -- see bitfield.c for the read/write/packing/sign-extension
# cases this covers ---
run_case bitfield "0;7;7;-1;-8;65;0;1234;0;100000;1234;1234;5678;1234;"

# --- multiple ".c" sources given to a target with no separate
# assemble/link step of its own (the JVM one) used to silently produce
# broken output: each file compiled to its own class with no way for
# them to call each other, failing only at *run* time with a confusing
# NoSuchMethodError. See Compiler#buildMultipleSourcesAsOneUnit()'s own
# comment for how this compiles several files as one unit instead
# (and its documented "static" name collision caveat) -- multifile-a.c
# defines a "static" helper/global + one function multifile-b.c calls
# via "extern", and both files have their own same-named-by-design
# "static" helper to confirm those don't collide with each other. ---
run_multifile_case multifile "106;10;100;"

# --- a compile error inside an #include'd file (a header, or -- for a
# multi-file JVM build -- another translation unit spliced in via the
# wrapper mechanism above) used to always be reported under the
# top-level file's own name, at some raw line count within
# Preprocessor's internal flattened buffer, instead of the file/line
# that actually has the mistake -- see errloc.c/.h and
# errloc-multifile-*.c, and Preprocessor's LineMap ---
assert_error_contains errloc \
    "errloc.h:2: unresolved reference: errloc_undefined_in_header" \
    "errloc.c:12: unresolved reference: errloc_undefined_after_include"
assert_multifile_error_contains errloc-multifile \
    "errloc-multifile-b.c:10: unresolved reference: errloc_multifile_undefined"

echo
echo "pass=$pass known-diff=$known fail=$fail"
rm -rf "$SCRATCH"
[ $fail -eq 0 ]
