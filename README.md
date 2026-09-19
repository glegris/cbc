CbC - Cflat Compiler (the ubuntu 64bit version)
====================

The ubuntu 64-bit version of the cbc compiler implemented in the [book "Homemade Compilers."](http://www.ituring.com.cn/book/1308). This project mainly solves the problem of unable to compile and run [cbc](https://github.com/aamine/cbc) on 64-bit machines.

## Direct installation use (on ubuntu 64-bits systems)

### Installation dependencies

> Note: Different ubuntu distributions may rely on inconsistent library names. The code here is the installation command on ubuntu 16.04

```shell
apt-get update && apt-get install -y \
        gcc-multilib g++-multilib libc6-i386 lib32ncurses5 lib32stdc++6 \
        openjdk-8-jre \
        git
```

###  Download & Install cbc

```shell
git clone https://github.com/leungwensen/cbc-ubuntu-64bit.git
cd cbc-ubuntu-64bit && ./install.sh
```

###  Usage

Unlike the original cbc, the -Wa,"--32" -Wl,"-melf_i386" execution parameters need to be added to the 64-bit system.

```shell
cbc -Wa,"--32" -Wl,"-melf_i386" test/hello.cb
./hello
> Hello, World!
```

##  Use docker image (on any 64-bit host environment)

The general principle is to build an environment for cbc compilation and execution based on the 64-bit system of Ubuntu 16.04. Users only need to download the packaged image locally to get the executable cbc, eliminating the need to configure and compile cbc.

### Install docker

### Start the docker daemon process

```shell
eval $(docker-machine env default)
```

### Download the mirror [leungwensen/cbc-ubuntu-64bit](https://hub.docker.com/r/leungwensen/cbc-ubuntu-64bit)

```shell
docker pull leungwensen/cbc-ubuntu-64bit
```

### Perform mirroring 

```shell
docker run -t -i leungwensen/cbc-ubuntu-64bit
```

### Execute cbc 

> The cbc command in the image is an alias of cbc -Wa,--32 -Wl,-melf_i386 and can be executed directly.

```shell
cbc cbc-ubuntu-64bit/test/hello.cb
```

## Command-line build & test scripts

For working on cbc itself without going through `ant` (which needs
`build.properties`'s `javacc.dir` to already point at a real
`javacc.jar`), three scripts under `bin/` cover the usual edit/build/test
loop:

```shell
bin/build.sh   # compiles src/ into build/classes; regenerates the parser
               # from Parser.jj first if a javacc.jar can be found (set
               # $JAVACC_JAR to point at one), otherwise just recompiles
               # the parser sources already checked into the repo
bin/cbc ...    # runs the compiler just built, same CLI as an installed
               # cbc (test/test_cbc.sh and test/Makefile already expect
               # this exact path)
bin/test.sh    # builds, then runs both test suites below
```

`bin/test.sh` runs `test/run_jvm.sh` (see below) and then the original
native x86 suite (`test/test_cbc.sh`, via `test/run.sh`) -- but only the
latter if this machine actually has the 32-bit runtime objects
`GNULinker.java` needs to link a real x86 executable (`crt1.o`, the
32-bit dynamic linker, etc; see "Installation dependencies" above). On a
machine without the multilib packages installed, that suite is reported
as skipped rather than failed, since there's nothing wrong with the
compiler in that case.

`test/run_jvm.sh` is a from-scratch port of the same `test/*.c` files to
the JVM backend (`-arch=jvm`), and is the main way to test cbc itself in
an environment that only has a JDK and no x86 toolchain at all. A fixed
set of tests are reported as `KNOWN-DIFF` rather than pass/fail, for
reasons that are architectural rather than bugs (see the JVM backend
section below for the underlying cause in each case): `usertype`,
`ptrdiff`, `sizeof-type`, `sizeof-expr`, `implicitaddr`, `funcptr`,
`gvar`, `varargs`.

## Language additions (both backends)

A few C99 features have been added on top of the original cflat
language, working identically on the x86 backend and the JVM backend
described below:

  * **`enum`**: `enum Color { RED, GREEN, BLUE = 10, YELLOW };` declares
    each name as an `int` constant (auto-incrementing from 0, or from
    right after an explicit value), and registers `Color`/`enum Color`
    as a usable type name (an alias for `int` -- there's no separate
    enum type at runtime, matching how little C itself guarantees about
    an enum's representation). An enumerator's explicit value must
    itself be a literal (or another constant expression this compiler
    can fold) for auto-increment to keep working after it.
  * **`switch` fallthrough**: a `case` clause no longer has to end in
    `break` -- omitting it falls through into the next clause (including
    into `default`), same as real C. `case 1: case 2: ...` (grouped
    labels sharing one body) already worked before and still does.
  * **Aggregate initializers**: `int[3] a = {1, 2, 3};`,
    `struct point p = {1, 2};`, and nested forms like
    `int[2][2] m = {{1,2},{3,4}};` or a struct member that's itself an
    array/struct. Fewer initializers than elements/members zero-fills
    the rest; a union initializer sets only one member. **Designated
    initializers** work too: `int[6] a = {[4] = 29, [2] = 15};`,
    `struct point p = {.y = 2, .x = 1};` (any order), and mixing plain
    and designated elements in one list (a plain element continues right
    after the previous element's position, same as C99) -- including
    giving the same spot a value more than once, where the last one
    given wins. A designator can be a full chain, freely nesting
    `.member` and `[index]` steps without needing explicit nested braces
    (`struct rect r = {.tl.x = 1, .tl.y = 2};`,
    `struct point[3] pts = {[0].x = 10, [2].y = 31};`), and `[index]` may
    be any compile-time integer constant expression, not just a literal
    (`int[8] a = {[N-1] = 5, [(2*3)+1] = 9};`) -- except for a named
    constant (an `enum` member or a `const` variable), which isn't
    resolved to a value until after designators are already parsed, so
    can't be used there. Two more scope limits, regardless of
    designators: the array's size must be given explicitly (inferring it
    from the initializer list, like C's `int a[] = {1,2,3};`, isn't
    supported), and a global (or `static` local)'s initializer elements
    must be compile-time constants, same as plain C requires at file
    scope; a non-static local's can be arbitrary runtime expressions,
    lowered to ordinary element-by-element assignments run where the
    declaration appears.
  * **`const`/`volatile` qualifiers**: usable on local/global variables,
    function parameters, struct/union members, casts and `sizeof`, in
    any combination with pointers (`const char *s`, `const int x`, ...).
    Assigning to (or `++`/`--` on) a const-qualified value is a compile
    error. `volatile` only parses and propagates -- neither backend
    reorders or caches memory accesses in a way it would need to
    suppress. Not supported: qualifying a function's own return type or
    a function pointer's parameter types, and qualifying a `typedef`'s
    target type directly (`const MyInt x;` after a plain
    `typedef int MyInt;` works fine, though). The original `const NAME =
    value;` top-level constant form (used well before this, including by
    `enum` above) still works exactly as before and takes priority when
    both could otherwise apply.
  * **Mixed declarations and code**: a variable declaration can appear
    anywhere among a block's statements, not just at the top --
    `printf("go\n"); int x = f(); printf("%d\n", x);` -- and its
    initializer (if any) runs exactly where the declaration appears,
    interleaved with the surrounding statements in source order. One
    simplification versus real C99: the variable is visible for the
    *whole* enclosing block, not only from its declaration point onward,
    so (unlike real C) a forward reference before the declaration is
    accepted rather than rejected as an error.
  * **`for` with a declaration in its init-clause**:
    `for (int i = 0; i < n; i++) ...`. The declared variable is scoped to
    the loop alone (an outer variable of the same name is unaffected
    after the loop ends), matching real C99 -- this desugars to a block
    containing just the declaration followed by the loop, so nesting two
    such loops with the same variable name (`for (int i ...) for (int i
    ...) ...`) works correctly, each `i` shadowing independently.
  * **`struct`/`union`/`enum` defined inside a function body**, not just
    at file scope -- exactly the same syntax as a top-level one:
    ```c
    int distance(void) {
        struct Point { int x; int y; };
        struct Point p;
        p.x = 3; p.y = 4;
        return p.x + p.y;
    }
    ```
    A simplification versus real C99, needed because this compiler has
    always had one single, flat, file-wide struct/union/enum-tag
    namespace with no notion of block scope for tags (this predates
    everything added in this series of changes): a local definition is
    hoisted into that same file-wide namespace rather than actually
    scoped to its enclosing block, so it's usable from any function in
    the file (even one defined earlier in the source, or after the
    function that defines it returns) and, like a top-level one, a
    second definition of the same tag name anywhere else in the file --
    even in another, unrelated function -- is a duplicate-type-definition
    error rather than two independent local types the way real C99 would
    treat them.
  * **Adjacent string literal concatenation**: `"abc" "def"` is the same
    as `"abcdef"` (C99 6.4.5p5) -- any number of adjacent string literals
    concatenate into one, most often used to spread a long literal across
    lines or to paste one built by a macro's `#` stringification onto
    surrounding text.
  * **`_Bool`**: a real, distinct 1-byte integer type (`sizeof(_Bool) ==
    1`), usable anywhere a type can appear -- variables, parameters,
    returns, struct/union members, casts, `sizeof`. One simplification
    versus real C99: assigning a value into a `_Bool` is just an ordinary
    narrowing integer conversion (low byte kept, same as assigning into
    an `unsigned char`), not real C99's "0 if the value compares equal to
    0, otherwise 1" rule -- so `_Bool b = 256;` stores 0 here, where real
    C99 requires 1 (256 is nonzero). Assigning a comparison, `!`, `&&` or
    `||` result (already exactly 0 or 1) is unaffected by this, which
    covers the overwhelming majority of real uses. `<stdbool.h>` isn't a
    real header in C99 either -- it's three macros (`bool`, `true`,
    `false`) over the actual `_Bool` keyword -- so use `#include
    "stdbool.h"` (see `import/stdbool.h`), same as any other header.
  * **`inline`**: parses (alone or combined with `static`, in either
    order) and is otherwise a pure no-op -- neither backend has an
    inliner, so it's accepted purely as documentation/a hint, exactly
    like `volatile` parsing without changing codegen. Accepted a bit more
    broadly than real C99 strictly allows (e.g. also tolerated on a local
    variable declaration) since nothing here ever needs to reject it.
  * **Compound literals**: `(struct point){1, 2}` or `(int[3]){1, 2, 3}`
    used as an expression, anywhere one is allowed inside a function
    body -- passed straight to a function (`sum_point((struct
    point){3,4})`), addressed (`&(struct point){1,2}`), or with a member
    immediately accessed off it (`(struct point){1,2}.x`). It's a
    genuine lvalue with automatic storage duration, like any other local
    variable: `(struct point){1,2}.x = 9;` is legal, and its lifetime is
    the rest of the enclosing block, same as a variable declared there.
    `T x = (T){...};` (the type name is then redundant, since `x`'s own
    type already says the same thing) is recognized as exactly
    equivalent to `T x = {...};`, not a separate hidden copy -- which is
    also how it gets **static storage duration**, matching real C99, when
    written as a global's or a `static` local's whole initializer
    (`struct point origin = (struct point){0, 0};`) or as an
    already-braced nested element of one
    (`int[2] pair = {(int){1}, (int){2}};`); a compound literal nested
    inside some *other* expression -- addressed
    (`struct point *p = &(struct point){1,2};`), passed straight to a
    function, or with a member immediately accessed off it -- still gets
    automatic storage duration when that happens inside a function body,
    but **also now works addressed in a global/`static` initializer**
    (see "Address-of static initializers" below), backed by a
    compiler-synthesized anonymous static object. One scope limit
    remains, matching this compiler's aggregate initializers generally:
    the array/struct/union's own type must be written out in full
    (`(int[])`, without a length, to infer it from the initializer list
    the way C99 itself allows, isn't supported).
  * **Address-of static initializers**: `&expr` is now usable inside a
    global's or a `static` local's own initializer, not just inside a
    function body -- e.g. a table of pointers to other globals
    (`struct point *table[] = {&origin, &unit};`), a self-referential
    linked structure built entirely out of file-scope data
    (`struct node c = {3, NULL}; struct node b = {2, &c};`), or `&` of an
    anonymous compound literal (`struct point *p = &(struct point){1,2};`,
    backed by a compiler-synthesized static object with no name of its
    own). This resolves to an actual link-time constant address for
    exactly two shapes: `&` of a plain global/`static` variable, and `&`
    of a compound literal; `&globalArray[i]` or `&globalStruct.field`
    aren't supported there yet, since those would need computing a
    constant byte offset on top of the base address, which this doesn't
    do. A reference to a named `const TYPE X = ...;` (or an enumerator)
    used *by value* rather than by address -- e.g. stddef.h's own
    `NULL` above -- also now works in this position, inlining its own
    (recursively resolved) initializer expression.
  * **`long long`/`unsigned long long`**: a real, distinct 8-byte integer
    type (`sizeof(long long) == 8`), including the `LL`/`ULL`/`LU`/`UL`
    literal suffixes (`123456789012345LL`), usable anywhere a type can
    appear on both backends. On the JVM backend it's fully functional --
    arithmetic, comparisons, casts, everything -- since the JVM's own
    `long` is already a native 64-bit type. **The x86 backend rejects it
    at code generation time** (parses and type-checks fine, same as
    everywhere else, but a function using it as a parameter/return type,
    or in any expression, is a clean compile error), exactly like
    `float`/`double` on that backend: it's a 32-bit-only target with no
    multi-register/carry-chain 64-bit integer arithmetic, and a silently
    truncated `long long` would be a much worse outcome than a clean
    rejection.
  * **`restrict`**: parses on a pointer (`int *restrict p`) and is a
    complete no-op, exactly like `inline` -- `restrict` only ever
    promises the optimizer that no other pointer aliases the same
    memory, which can't change what a conforming program observes, and
    this compiler does no alias-based optimization that promise could
    enable in the first place.
  * **Hexadecimal floating-point constants**: `0x1.8p3` (== 12.0),
    `0x1p10` (== 1024.0), `0x.1p4` (== 1.0) -- a hex significand with a
    *mandatory* binary (`p`/`P`) exponent, per C99 6.4.4.2. (The `p`
    exponent is what tells a hex float apart from a plain hex integer
    literal like `0x1A`, which is unaffected.)

## Preprocessor

Every `.c` file is now run through a real C-style preprocessor before it
reaches the parser, on both backends. It's a token-based pass (not raw
text substitution), so it gets macro-argument handling, `#`/`##` and
recursive macro expansion right rather than approximately right:

  * **Object-like and function-like macros**: `#define VERSION 42` and
    `#define ADD(a, b) ((a) + (b))`. Arguments are macro-expanded before
    substitution (unless adjacent to `#`/`##`), and a macro never expands
    through its own invocation (directly or via another macro that calls
    back into it), so `#define X X + 1` just adds one `X` to the output
    instead of looping forever. `#undef` removes a definition.
  * **Variadic macros**: `#define LOG(fmt, ...) printf(fmt, __VA_ARGS__)`.
    Calling one with zero trailing arguments (`LOG("hi")`) gives an empty
    `__VA_ARGS__` rather than an error -- not strictly legal pre-C23
    standard C, but the long-standing real-world GNU/Clang/MSVC behavior,
    and needed for the companion GNU `, ##__VA_ARGS__` extension: `,
    ##__VA_ARGS__` elides the preceding comma *and* itself whenever
    `__VA_ARGS__` is empty (`#define LOG0(fmt, ...) printf(fmt,
    ##__VA_ARGS__)`; `LOG0("hi")` -> `printf("hi")`, not `printf("hi",
    )`), and is otherwise just `, __VA_ARGS__` with no real token-pasting
    involved (`##` is never allowed to literally glue the comma onto the
    first variadic token). Only the bare `...`/`__VA_ARGS__` form is
    supported, not the GNU named-variadic extension (`args...`, referred
    to by that name) or C23's `__VA_OPT__`.
  * **Stringification and token pasting**: `#define STR(x) #x` and
    `#define CONCAT(a, b) a ## b`, per C99 semantics (escaping embedded
    `"`/`\` when stringifying, retokenizing the pasted result).
  * **Predefined macros**: `__LINE__`/`__FILE__` (the current line/file,
    re-evaluated at each expansion site, honoring `#line` -- see below);
    `__DATE__`/`__TIME__` (this compile's start time, fixed for the whole
    run, in C99's own `"Mmm dd yyyy"`/`"hh:mm:ss"` formats); `__STDC__`
    (`1`) and `__STDC_VERSION__` (`199901L`); and `__COUNTER__` (a GNU/
    MSVC extension: expands to `0`, then `1`, then `2`, ... incrementing
    on every use). Nothing identifies this compiler/platform/architecture
    itself (no `__GNUC__`, `__unix__`, ...) -- cbc isn't gcc/clang, and
    pretending otherwise would make real headers take branches this
    compiler doesn't actually support.
  * **Conditional compilation**: `#ifdef`/`#ifndef`/`#if`/`#elif`/`#else`/
    `#endif`, with `#if`/`#elif` backed by a real constant-expression
    evaluator supporting the full C operator set (arithmetic, bitwise,
    logical, comparison, shifts, `?:`, `defined(NAME)`/`defined NAME`) at
    the usual precedence.
  * **`#include "..."`/`#include <...>`**, searched next to the including
    file (quoted form only) and then on the `-I` path (see "Standard
    headers" below for what ships in `import/`); `#error "message"`
    aborts the compile with that message, and `#pragma`/the equivalent
    `_Pragma("...")` operator are both accepted and silently ignored (no
    pragma this compiler acts on).
  * **`#line NUMBER ["FILENAME"]`**: adjusts what `__LINE__`/`__FILE__`
    report from the next line onward. This is genuinely as far as it
    goes: it can't redirect what the *rest* of the compiler itself
    reports for a diagnostic (see the caveat below).
  * **`-E`**: prints the preprocessed output and stops, like `gcc -E`,
    for inspecting/debugging macro expansion independent of the rest of
    the pipeline.

Not supported, scoped out: the GNU named-variadic extension and
`__VA_OPT__` (see above), compiler/platform-identifying predefined
macros (also see above), and spreading one function-like macro call
across multiple lines (the whole argument list must be on one logical
line, after backslash-newline splicing). Every source line still
produces exactly one output line so that error messages elsewhere in the
compiler keep pointing at the right line, except across an `#include`:
the included file's lines are spliced in inline, so line numbers in the
including file *after* the `#include` shift by however many lines that
added. `#line` corrects `__LINE__`/`__FILE__` for this, but -- same as
`#include` -- has no way to correct what the rest of the compiler
reports, since nothing downstream of the preprocessor knows anything
beyond a line's position in the one, already-flattened text it receives.

## Standard headers

cflat used to have its own, separate way to pull in a header:
`import stdio;`/`import sys.types;` (dots standing in for a path),
resolved by a dedicated `LibraryLoader` against a `.hb`-suffixed file
and merged in as compiled declarations, never as text -- meaning an
imported file's own macros were never visible to the file that imported
it. **That whole mechanism is gone.** A cflat file now only ever pulls
in another one exactly like real C does: `#include "stdio.h"` (searched
next to the including file first) or `#include <stdio.h>` (searched on
`-I`), going through the same preprocessor pass as everything else, so
a header's own macros (and anything it further `#include`s) are fully
visible to whatever includes it. Every header below now lives directly
under `import/` (or `import/sys/` for `<sys/types.h>`) with a real `.h`
name, and -- since plain `#include` has no built-in "already included"
tracking the old loader's caching gave it for free -- every one of them
has its own `#ifndef`/`#define`/`#endif` include guard, so including
the same header more than once (directly, or transitively through two
different other headers) is always safe.

What ships in `import/`, organized like the standard itself:

  * **C99**: `<stdio.h>`, `<stdlib.h>`, `<string.h>`, `<stdarg.h>`,
    `<stddef.h>`, `<stdbool.h>`, `<ctype.h>`, `<assert.h>`, `<limits.h>`,
    `<float.h>`, `<stdint.h>` (the fixed-width `intN_t`/`uintN_t` types
    and their `INTN_MIN`/`INTN_MAX`/`UINTN_MAX` macros, plus
    `intptr_t`/`uintptr_t`, not every optional "least"/"fast" variant).
    `<ctype.h>`, `<assert.h>`, `<limits.h>`, `<float.h>` and `<stdint.h>`
    are new: `<ctype.h>` in particular declares functions
    (`isdigit`/`isalpha`/...) that were already implemented in
    `StandardRuntime.java` but had no header at all before, so nothing
    could actually call them without declaring them by hand first.
    `<assert.h>`'s `assert()` needed one small new thing to be
    expressible at all: this compiler has no comma operator, so its
    usual `(expr) || (fprintf(...), abort(), 0)` form doesn't work here
    -- see the header's own comment for the (still library-free)
    workaround. `<limits.h>`'s `LONG_MIN`/`LONG_MAX`/`ULONG_MAX` and
    `<stdint.h>`'s `intptr_t`/`uintptr_t` are, unavoidably, the one place
    a header's own content depends on which backend it ends up compiled
    for: plain `long` (and a pointer) is 4 bytes on x86 but 8 on the JVM
    backend (see below), so those are written as expressions the
    *compiler* resolves against long's real width on whichever backend
    it's actually targeting, rather than a `#if`-time literal that would
    be silently wrong on one of the two -- see `limits.h`'s own comment
    for the (small) resulting caveat.
  * **Not C99, kept for compatibility with real code**: `<strings.h>`,
    `<errno.h>`, `<setjmp.h>`, `<unistd.h>`, `<sys/types.h>`,
    `<alloca.h>`, `<dlfcn.h>` -- all x86-only (real libc symbols this
    backend just declares and links against; none are implemented by
    `StandardRuntime`/`NativeRuntime`, so none work on the JVM backend
    without hand-writing them there first -- see the JVM backend section
    below).

Still not provided: `<inttypes.h>`, `<time.h>`, `<signal.h>`,
`<locale.h>`, `<wchar.h>`/`<wctype.h>`, `<complex.h>`/`<tgmath.h>` -- none
of these need the preprocessor or parser to change to add, just more
declarations (and, for anything not already in `StandardRuntime.java`,
a real implementation there for the JVM backend to actually call).

## JVM backend (`-arch=jvm`)

In addition to native x86 assembly, cbc can compile a cflat source file
directly to a JVM class file, using the [ASM](https://asm.ow2.io/) bytecode
library. There is no separate assemble/link step for this target: the
`.class` file it produces is already runnable.

```shell
cbc -arch=jvm test/add.c
java -cp .:path/to/build/classes add
```

(`--target=jvm` is accepted as a longer alias for `-arch=jvm`.) The
produced class is named after the source file, sanitized into a valid
Java identifier (e.g. `while-break.c` becomes class `while_break`), so
that the file name and the class name it contains always match.

The `build/classes` half of that classpath (wherever `bin/build.sh`
put it, or the equivalent for however cbc itself was built) is needed by
*running* the program, not just compiling it, as soon as it calls
`printf`/`putchar`/`puts` or any function handled by the extensible
native-library mechanism below -- both compile to calls into
`net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime`, a class that
ships with cbc itself rather than being folded into every compiled
program's own `.class` file.

This backend supports essentially all of core cflat, including a real
C-style address space:

  * `char`/`short`/`int`/`long`, signed and unsigned, mapped to JVM `int`
    or `long`; arithmetic, bitwise ops, shifts, comparisons and casts.
  * control flow: `if`, `while`, `for`, `do...while`, `switch`, `goto`,
    `break`/`continue`.
  * functions (including recursion) and global/static variables.
  * **pointers, arrays, structs and unions**: `&x`, `*p`, `p[i]`, `p->m`,
    pointer arithmetic/comparison/difference, `sizeof`, and `char*`
    strings (including string literals assigned to variables, not just
    passed straight to a print call) all work. The JVM has no address
    space of its own, so this backend builds one: a single big byte
    array simulates the whole process memory, every global gets a fixed
    offset into it, and every function call bump-allocates and releases
    its own "stack frame" region from it, mirroring how the x86 backend
    lays out its own real stack frame. `main`'s `argv` is backed by a
    real, freshly-built array of C strings.
  * `main(void)` and `main(int argc, char **argv)`; `argc`/`argv` are
    derived from the JVM's own `String[] args` (with a synthetic
    `argv[0]` standing in for the program name).
  * **struct/union by value**: passing one as a parameter, returning one,
    and assigning one struct/union to another as a whole (`s1 = s2;`) all
    work, through a hidden-pointer calling convention (see the class
    comment on `sysdep/jvm/CodeGenerator.java` for the details) -- only
    the x86 backend still requires copying members individually or using
    pointers, since it doesn't implement that convention and rejects
    these at code generation time instead. This only covers a *named*
    struct/union variable, though: a struct/union produced by a more
    complex expression (e.g. `*p`) still needs to be assigned to a plain
    variable first.
  * **function pointers**: taking one's address (`&f`, or a bare function
    name decaying to a value, e.g. `fp = f;`) and calling through one
    both work, for any non-variadic function defined in the same source
    file -- including one returning a struct/union by value. Only
    calling through a plain function-pointer variable is supported, not
    a more complex expression (an array element, a struct member, ...).
  * **`float`/`double`**: variables, parameters, returns, globals,
    struct/union members and array elements; arithmetic (`+ - * /`,
    unary `-`), comparisons (including correct IEEE 754 "unordered"
    NaN semantics -- any comparison against NaN is false except `!=`),
    casts to/from integers and between `float`/`double`, `++`/`--`, and
    `printf`'s `%f`/`%e`/`%g` (field width/precision specifiers, like
    this backend's existing `%d`/`%s`, are not interpreted). This is a
    JVM-only feature for now -- the x86 backend has no floating-point
    codegen at all (no FPU/SSE instructions are ever emitted) and
    rejects any use of `float`/`double` at code generation time instead
    of miscompiling it. There is no `long double` (a plain or
    `L`-suffixed floating constant is just a `double`).
  * **variadic functions**: defining one (`int myprintf(char *fmt, ...)`)
    and calling it both work, for `int`/`long`/pointer and `float`/`double`
    (promoted to `double`, per C's own default argument promotion)
    arguments, using cflat's existing `va_list`/`va_init()`/`va_next()`
    (`#include "stdarg.h"`, see `lib/stdarg.c`) -- unchanged from the x86
    backend's own implementation of those three, despite the JVM having
    no equivalent of a real, contiguous call stack to point into: a call
    to a vararg function marshals its "..." arguments into a small,
    freshly allocated block in this backend's own simulated address
    space instead (one 8-byte slot per argument), and passes that
    block's address as an extra, hidden parameter every vararg function
    receives -- `va_init()` just hands it back directly (as a JVM-backend
    compile-time intrinsic, alongside `printf` and friends), after which
    `va_next()`'s own implementation (a real `StandardRuntime` method,
    `long va_next(long ap)`) is plain pointer arithmetic, exactly as
    `lib/stdarg.c` already expects. Not supported: taking the address of
    a variadic function, or calling one through a function pointer
    (same restriction plain function pointers already have on this
    backend), and a variadic *external* function declared but not
    defined in the same file (there being no real vararg calling
    convention to call into, unlike a fixed-arity one -- see
    `StandardRuntime`/`NativeRuntime` below).

Remaining gaps: `putchar(int)`, `puts(char*)` and `printf(char*, ...)` are
compile-time intrinsics (`printf`'s format string itself must still be a
compile-time literal, though `puts`/`%s` accept any `char*` expression,
not just literals) that compile to calls into `StandardRuntime` (below)
just like anything else, but have no real address, so `&putchar` and
friends are rejected. Lastly, `long` and every pointer type are real
8-byte JVM `long`s here (see the class comment on
`sysdep/jvm/CodeGenerator.java`), so `sizeof(long)`/`sizeof(T*)` are 8,
not 4 like on the (32-bit-only) x86 backend.

### Calling external functions: StandardRuntime and NativeRuntime

The compiled program's own class `extends NativeRuntime`, a small,
human-readable `NativeRuntime.java` that `cbc` generates *as source*, next
to the `.class` file, and compiles with `javac` right after generating it
(using cbc's own runtime classpath, so it can resolve the class below);
`NativeRuntime` in turn `extends`
`net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime`, hand-written and
checked into cbc itself. Because of that chain, a call to a function
declared (e.g. via `#include`) but not defined in the same file --
anything other than `putchar`/`puts`/`printf`, which the compiler
already knows about directly -- is simply a call to an *inherited*
method:

  * **`StandardRuntime`** already implements a useful chunk of libc:
    `<ctype.h>` (`isdigit`, `isalpha`, `isalnum`, `isspace`, `isupper`,
    `islower`, `toupper`, `tolower`), `<string.h>` (`strlen`, `strcpy`,
    `strncpy`, `strcat`, `strncat`, `strcmp`, `strncmp`, `strchr`,
    `memcpy`, `memmove`, `memset`, `memcmp`), and `<stdlib.h>`'s numeric
    conversions (`abs`, `labs`, `atoi`, `atol`, `atof`) -- all operating
    on memory the caller already owns, since this backend has no
    general-purpose `malloc`/`free` exposed to cflat code yet. Add a
    `public` instance method there to make another function available to
    every compiled program without regenerating anything: its
    parameters/return type are the cflat ones, mapped the way
    `CodeGenerator#buildDescriptor` always maps them
    (`int`/`long`/`float`/`double`); an implementation that needs to
    read/write memory directly uses the inherited `mem` field (this
    program's whole simulated address space -- a cflat pointer is a
    plain `long` byte offset into it, cast with `(int)` to index it),
    and one that doesn't simply never mentions it.
  * **`NativeRuntime`** is generated fresh per program and contains one
    stub -- throwing `NotImplementedException` -- for each external
    function the program calls that isn't already in `StandardRuntime`
    (checked by a plain hardcoded name list in `CodeGenerator`, not
    reflection, so generating it never needs `StandardRuntime` itself
    loaded or even built).

Since the compiled class extends this chain directly, every call --
whether to a name already in `StandardRuntime` or to a `NativeRuntime`
stub -- goes through the very same mechanism: the one instance of the
compiled class itself, constructed once in the generated `<clinit>` and
held in a `$rt` static field, since a compiled cflat function is a
*static* JVM method with no `this` of its own to call an inherited method
on directly. `StandardRuntime` allocates this program's whole simulated
address space itself, in its own (no-arg) constructor -- the compiled
class's `$mem`/`$buf` static fields (used directly everywhere else in the
generated bytecode) are just read back off that one instance right after
constructing it, not allocated separately.

Compiling a program that calls such a function always succeeds; only
actually *calling* an unimplemented one fails, at run time, with
`NotImplementedException` naming it -- edit the generated
`NativeRuntime.java` by hand to implement it and re-run `cbc`, which
recompiles it with `javac` automatically (no separate manual step).
**`cbc` never overwrites an existing `NativeRuntime.java`** (your edits
always survive recompiling the `.c` file, and are picked up by that same
automatic recompile); if the program starts calling a function the
existing file doesn't seem to define yet, that's reported as a warning
naming it, not silently patched in or silently left to fail at run time as
a `NoSuchMethodError`. `NativeRuntime.class` is required for the compiled
program to even load now (it's the program's own superclass), so unlike
before, this recompilation step is never optional -- but since freshly
generated or hand-edited source is always valid Java (an unimplemented
stub just throws), there's nothing to wait on the user for before running
it.

This is JVM-only: the x86 backend links against real external symbols the
normal way (`-lc`, `-lcbc`, ...), so it already handles an external
function call natively and needs none of this.

One structural limit: a cflat function compiles to exactly one JVM
method, with no splitting, and a JVM method's bytecode is capped at
65535 bytes by the class file format itself (a 16-bit `code_length`,
not an ASM restriction -- no JVM will load past it regardless of
toolchain). Very large generated code -- e.g. a single function with
many thousands of statements -- can hit that ceiling; there's no
trampoline that splits a too-large function across several JVM methods,
so this is reported as a clean compile error (naming the method and its
actual bytecode size) rather than silently miscompiling or crashing
with a raw ASM stack trace. The fix is splitting the offending cflat
function itself into smaller ones.

Original descrition
====================


    This is the CbC, Cflat programming language compiler.

Requirements
------------

    To compile cbc itself:

        * JDK 1.5 or later
        * JavaCC 4.0 or later
        * ant
        * make

    To run cbc and compiled program:

        * Linux 2.4 or later
        * util-linux (ld-linux.so.2)
        * GNU libc 2.3 or later
        * GNU binutils (as, ld)


Installation
------------

    To install all files under /usr/local/cbc:

        # sudo ./install.sh
        # sudo ln -s ../cbc/bin/cbc /usr/local/bin/cbc

    To install all files under $HOME/cbc:

        $ ./install.sh $HOME/cbc
        $ ln -s ../cbc/bin/cbc $HOME/bin/cbc


Build
-----

    Edit build.properties for your environment and invoke make:

        $ vi build.properties
        $ make


Test
----

    Invoke "make test":

        $ make test

    Note that you need bash (not bourne sh) to run test scripts.
    ksh or zsh may work.


Usage
-----

    $ cbc --help


License
-------

    Modified BSD license.
    For details of modified BSD license, see following:

    Copyright (c) 2007-2009  Minero Aoki.  All rights reserved.

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions
    are met:

        * Redistributions of source code must retain the above copyright
          notice, this list of conditions and the following disclaimer.
        * Redistributions in binary form must reproduce the above
          copyright notice, this list of conditions and the following
          disclaimer in the documentation and/or other materials provided
          with the distribution.
        * Neither the name of the Minero Aoki nor the names of its
          contributors may be used to endorse or promote products
          derived from this software without specific prior written
          permission.

    THIS SOFTWARE IS PROVIDED BY MINERO AOKI ``AS IS'' AND ANY EXPRESS
    OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
    WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL <copyright holder> BE LIABLE FOR ANY
    DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
    GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
    INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
    IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
    OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
    EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.


Contact
-------

    CbC produced by Minero Aoki <aamine@loveruby.net>.
