CbC - Cflat Compiler (the ubuntu 64bit version)
====================

The ubuntu 64-bit version of the cbc compiler implemented in the [book "Homemade Compilers."](http://www.ituring.com.cn/book/1308). This project mainly solves the problem of unable to compile and run [cbc](https://github.com/aamine/cbc) on 64-bit machines. It has since grown a second, x86-independent backend that compiles straight to a JVM class file (`-arch=jvm`; see its own section below) -- the simplest way to try cbc today, since it needs only a JDK and no 32-bit toolchain at all.

## Building from source

```shell
git clone <this repository's URL>
cd cbc
bin/build.sh   # compiles src/ into build/classes (see "Command-line
               # build & test scripts" below for the details)
bin/cbc test/hello.c -arch=jvm -o hello
java -cp build/classes:lib/asm-9.7.1.jar hello
> Hello, World!
```

### Installing system-wide

```shell
bin/build.sh
./install.sh /usr/local/cbc   # or any other prefix; defaults to /usr/local/cbc
export PATH="/usr/local/cbc/bin:$PATH"
cbc -arch=jvm -I /usr/local/cbc/import test/hello.c -o hello
```

### Native x86 backend (`-arch=x86`, the default)

This needs a 32-bit assembler/linker even on a 64-bit host (different
distributions name these packages differently; on Ubuntu):

```shell
apt-get update && apt-get install -y gcc-multilib g++-multilib libc6-i386 lib32ncurses5 lib32stdc++6
```

Unlike the original cbc, the 32-bit `-Wa,"--32" -Wl,"-melf_i386"` execution
parameters need to be added on a 64-bit system:

```shell
cbc -Wa,"--32" -Wl,"-melf_i386" test/hello.c
./hello
> Hello, World!
```

`install.sh` also needs `lib/libcbc.a` (the x86 backend's own tiny runtime
support library) built first: `(cd lib && make)`.

## Command-line build & test scripts

Three scripts under `bin/` cover the usual edit/build/test loop:

```shell
bin/build.sh   # compiles src/ into build/classes; regenerates the parser
               # from Parser.jj first if a javacc.jar can be found (set
               # $JAVACC_JAR to point at one), otherwise just recompiles
               # the parser sources already checked into the repo
bin/cbc ...    # runs the compiler just built, same CLI as an installed
               # cbc (test/test_cbc.sh already expects this exact path)
bin/test.sh    # builds, then runs both test suites below
```

`bin/test.sh` runs `test/run_jvm.sh` (see below) and then the original
native x86 suite (`test/test_cbc.sh`, via `test/run.sh`) -- but only the
latter if this machine actually has the 32-bit runtime objects
`GNULinker.java` needs to link a real x86 executable (`crt1.o`, the
32-bit dynamic linker, etc; see "Native x86 backend" above). On a
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
    can fold) for auto-increment to keep working after it. The tag is
    optional (C99 6.7.2.3): `enum { A, B, C };` with neither a tag nor a
    declared variable is real C's single most common way to just define
    a handful of named `int` constants.
  * **`switch` fallthrough**: a `case` clause no longer has to end in
    `break` -- omitting it falls through into the next clause (including
    into `default`), same as real C. `case 1: case 2: ...` (grouped
    labels sharing one body) already worked before and still does. A
    case label can be any integer constant expression, not just a bare
    literal (`case (1 << 3) | FLAG:`, `case COMBO(a,b):` for a macro
    expanding to one) -- evaluated the same way a global/static
    initializer's own constant expression already is.
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
    declaration appears. C99 6.7.8p22 explicitly allows one trailing `,`
    after the last element (`{1, 2, 3,}`), same as `enum` above.
    A global/static initializer element being a compile-time constant
    includes a bare function name (decaying to its own address, e.g. a
    struct of callback function pointers) and a basic-arithmetic
    expression of two further-constant operands (`1.0f/2.2f`), not just
    a literal.
  * **`const`/`volatile` qualifiers**: usable on local/global variables,
    function parameters, struct/union members, casts and `sizeof`, in
    any combination with pointers (`const char *s`, `const int x`, ...),
    and on either side of the base type name (`const char *s`/`char
    const *s` mean exactly the same thing). The qualifier always
    describes the base type, however many `*`/`[]` wrap it: `const char
    *s`/`char const *s` both mean "`s` points to a `const char`" (`s`
    itself stays freely reassignable, only `*s = ...` is rejected), and
    `const char *arr[10]` means "an array of pointers to `const char`" --
    never "`s`/`arr` itself is a const pointer" (real C's separate `char
    *const p` spelling, constifying the pointer instead of the pointee,
    isn't supported). Assigning to (or `++`/`--` on) a const-qualified
    value is a compile error. `volatile` only parses and propagates --
    neither backend reorders or caches memory accesses in a way it would
    need to suppress. Not supported: qualifying a function's own return
    type or a function pointer's parameter types, and qualifying a
    `typedef`'s target type directly (`const MyInt x;` after a plain
    `typedef int MyInt;` works fine, though). The original `const NAME =
    value;` top-level constant form (used well before this, including by
    `enum` above) still works exactly as before and takes priority when
    both could otherwise apply -- so a top-level `const TYPE NAME =
    value;` (with an initializer) is still that older, substituted-by-
    value form, never a real, independently-addressable variable; give
    it no initializer (`static const char *p;`, assigned to later) to
    get a real one instead.
  * **Comma operator** (C99 6.5.17): `a, b` evaluates `a` for its side
    effect only, then evaluates and yields `b` -- most often seen in a
    `for` loop's init/increment clauses (`for (i = 0, j = n; ...; i++,
    j--))`), or explicitly parenthesized (`x = (a = 1, b = 2, a + b);`).
    Deliberately left out of every spot a bare `,` already means
    something else (a function call's arguments, an initializer list, an
    array size, ...), matching real C's own grammar restricting those to
    one step down (`assignment-expression`) for the same reason.
  * **Multiple declarators in a struct/union member list**: `struct p {
    int x, y; };` -- each one after the first re-applies its own
    `*`/`[N]`/`[]`/`:width` to the same shared base type, exactly like a
    plain variable declaration's own `int *p, q;` (`q` is plain `int`,
    not `int*`) already worked.
  * **`extern` on a function definition**: `extern int f(void) { ... }`
    (as opposed to a bare declaration, `extern int f(void);`) -- a
    complete no-op, since a top-level function is externally linked by
    default anyway, but real-world headers write it purely to visually
    pair a definition with its own extern declaration elsewhere.
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
    arithmetic, comparisons, casts, everything, including mixed with a
    plain `int`/`long` in the same expression -- since the JVM's own
    `long` is already a native 64-bit type. (`usualArithmeticConversion`,
    the "which of these two operands' types should this expression's
    result use" step, used to have no notion of `long long` at all,
    silently falling back to plain 32-bit `int` -- discarding a
    `long long` operand's own upper 32 bits -- for *any* expression
    mixing one with anything else, even a small `int` constant; this was
    found and fixed by running a subset of the GCC c-torture execute
    tests through this compiler.) **The x86 backend rejects it at code
    generation time** (parses and type-checks fine, same as everywhere
    else, but a function using it as a parameter/return type, or in any
    expression, is a clean compile error), exactly like `float`/`double`
    on that backend: it's a 32-bit-only target with no multi-register/
    carry-chain 64-bit integer arithmetic, and a silently truncated
    `long long` would be a much worse outcome than a clean rejection.
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
  * **Array sizes as a constant expression**: `float overlap[2][9*32];`
    -- `[N]` in a declarator (or a cast/`sizeof` type's own `[N]`) used
    to require `N` to be a single raw integer literal token; it now
    accepts any compile-time integer constant expression, folded right
    at parse time by the same evaluator (`+ - * / % & | ^ << >>`, unary
    `+ - ~ !`, `&& || == != < <= > >=`, `?:`, arbitrarily nested) already
    used for a designated initializer's own `[index]` (see above). Found
    compiling minimp3.h's own `mdct_overlap[2][9*32]`.
  * **An explicit `U`/`u` suffix on a decimal integer literal** now
    permits the full unsigned 64-bit range, matching C99 6.4.4.1p5's
    table: `18446744073709551615ULL` (`UINT64_MAX`) used to crash the
    compiler outright (`NumberFormatException`) -- `integerValue()`'s
    "fall back to unsigned when the value doesn't fit a signed type"
    handling only ever looked at a hex/octal literal's *lack* of a
    suffix (where C99's own table allows that fallback regardless), never
    at a decimal literal's *explicit* `U` (which the same table also
    unconditionally allows, decimal or not) -- found compiling minimp3.h,
    which defines `UINT64_MAX` itself rather than relying on a
    (nonexistent, in this compiler) system `<stdint.h>`.

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
produces exactly one output line, but an `#include`'s lines are spliced
in inline, so the parser only ever sees one flattened token stream
spanning every file in the translation unit -- with no notion of file
boundaries of its own. To keep error messages naming the actual file and
line with the mistake (not just the top-level file being compiled), the
preprocessor builds a `LineMap` alongside that flattened text -- a table
of breakpoints recording, at each `#include`, `#line`, and return from an
`#include`, which output line starts reporting as which (file, line) --
and the parser consults it (see `Parser#location()`) to resolve every
token back to where it really came from. `#line` updates this map and
`__LINE__`/`__FILE__` together, so the two stay consistent.

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
    expressible at all: at the time it was written, this compiler had no
    comma operator (see below -- it does now, but the header's own
    workaround still works fine and was never revisited), so its usual
    `(expr) || (fprintf(...), abort(), 0)` form didn't work -- see the
    header's own comment for the (still library-free) workaround it uses
    instead. `<limits.h>`'s `LONG_MIN`/`LONG_MAX`/`ULONG_MAX` and
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
    real, freshly-built array of C strings. Indexing one level into a
    multi-dimension array (`row = matrix[i];`, giving another array,
    `T[M]`, rather than a scalar `T`) now correctly decays to that row's
    own address instead of trying to load it as if it were a scalar at
    that address -- `visit(ArefNode)` in `IRGenerator` was missing the
    same `isLoadable()` guard `visit(MemberNode)`/`visit(PtrMemberNode)`/
    `visit(DereferenceNode)` already had, so this silently read wrong
    values (or threw an out-of-bounds exception, depending on the address
    landed on) instead of ever raising a compile error; found decoding a
    real MP3 with minimp3.h (`demos/minimp3`), whose `mp3dec_scratch_t`
    struct has an `ist_pos[2][39]` member indexed by channel this way.
  * **Local variables/parameters/compiler-synthesized temporaries whose
    address is never taken are promoted to real JVM local variable
    slots** (`ILOAD`/`ISTORE`/...) instead of living at a frame-relative
    address in the simulated heap above -- see `EscapeAnalysis`. Every
    other local used to pay the same cost as a genuine pointer
    dereference on *every* access (`frameBase` reload, address
    arithmetic, a `ByteBuffer` call) even for a plain loop counter that
    C itself would keep in a register; a promoted one is a single, close
    to free bytecode instruction the JIT can freely optimize, exactly
    like `javac`'s own output. An entity is eligible whenever no `Addr`
    IR node referencing it appears anywhere except as the direct
    assignment target of a plain `x = ...;` (IRGenerator's own uniform
    lowering for *every* such assignment, regardless of whether the
    source ever wrote `&x`) -- struct/union and array entities are
    always excluded (they need a real address for whole-value `memcpy`/
    decay-to-pointer semantics). This is a pure optimization with no
    behavior change (confirmed identical decoded PCM, bit for bit,
    before and after, for `demos/minimp3`'s own real-MP3 decode) but a
    very large one in practice: decoding a real ~112s MP3 through
    `demos/minimp3` dropped from 56.8s to **1.36s** (a real C compiler,
    for comparison, does the same decode in ~0.11-0.13s) -- most of that
    gap was never the simulated heap's `ByteBuffer` access itself (a
    JIT-intrinsified `ByteBuffer.getInt`/`putInt` on a `HeapByteBuffer`
    is in fact *faster* than a hand-rolled byte-array reconstruction),
    but the sheer bytecode bulk every local access added: a function
    doing enough of them past HotSpot's default 8000-byte
    `-XX:HugeMethodLimit` (`mp3d_synth`, minimp3's polyphase synthesis
    filter, hit 8955 bytes) never gets JIT-compiled at all and runs
    permanently interpreted -- promotion alone shrank it to 4103 bytes,
    comfortably under that limit again, with no JVM flag required.
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
    A function pointer's own parameter list can name its parameters
    (`int (*read)(void *user, char *data, int size);`), purely as
    documentation -- nothing here ever binds one to a value, so a name
    is accepted and simply discarded, same as real C allows.
  * **`float`/`double`**: variables, parameters, returns, globals,
    struct/union members and array elements; arithmetic (`+ - * /`,
    unary `-`), comparisons (including correct IEEE 754 "unordered"
    NaN semantics -- any comparison against NaN is false except `!=`),
    casts to/from integers and between `float`/`double`, `++`/`--`, and
    `printf`'s `%f`/`%F` (field width/precision/flags are interpreted,
    same as `%d`/`%s`; see `<stdio.h>` below -- there is no `%e`/`%g`/
    `%a`). This is a JVM-only feature for now -- the x86 backend has no
    floating-point codegen at all (no FPU/SSE instructions are ever
    emitted) and rejects any use of `float`/`double` at code generation
    time instead of miscompiling it; since `<stdio.h>`'s own `printf()`
    now always needs a `double` internally to support `%f` at all (see
    `<stdio.h>` below), this in practice means the entire
    `printf`/`fprintf`/`sprintf`/`snprintf` family (and their `v...()`
    counterparts) can only be compiled for the JVM backend, even for a
    call site that never itself passes a `%f`. There is no `long double`
    (a plain or `L`-suffixed floating constant is just a `double`).
    Compound assignment (`+= -= *= /=`) on a `float`/`double` operand
    now works too (`y *= scale;`) -- `OpAssignNode`'s own type check used
    to unconditionally require an integer on both sides for every
    compound-assignment operator (pointer `+=`/`-=` was the one existing
    exception), where C99 only actually restricts `%= &= |= ^= <<= >>=`
    to integers; found compiling minimp3.h (`demos/minimp3`), whose own
    `L3_ldexp_q2` does exactly this (`y *= g_expfrac[...]`).
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

Remaining gaps: `putchar`/`puts`/`printf` and friends are no longer
compile-time intrinsics at all -- `<stdio.h>` gives every one of them a
real definition (see below) -- but a variadic function still has no real
address on this backend (see above), so `&printf` and friends are still
rejected. Lastly, `long` and every pointer type are real 8-byte JVM
`long`s here (see the class comment on `sysdep/jvm/CodeGenerator.java`),
so `sizeof(long)`/`sizeof(T*)` are 8, not 4 like on the (32-bit-only)
x86 backend.

### Calling external functions: StandardRuntime and NativeRuntime

The compiled program's own class `extends NativeRuntime`, a small,
human-readable `NativeRuntime.java` that `cbc` generates *as source*, next
to the `.class` file, and compiles with `javac` right after generating it
(using cbc's own runtime classpath, so it can resolve the class below);
`NativeRuntime` in turn `extends`
`net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime`, hand-written and
checked into cbc itself. Because of that chain, a call to a function
declared (e.g. via `#include`) but not defined in the same file --
anything other than `printf` (still a genuine compile-time intrinsic;
`putchar`/`puts` no longer are, now that `<stdio.h>` gives them a real
definition of their own -- see below), which the compiler already
knows about directly -- is simply a call to an *inherited* method:

  * **`StandardRuntime`** already implements a useful chunk of libc: a
    real first-fit free-list `malloc`/`calloc`/`realloc`/`free` (carved
    out of the same simulated address space everything else uses -- see
    below), the eight `<ctype.h>` functions that were there before
    `<string.h>`/`<stdlib.h>` gained real portable-C bodies of their own
    (`isdigit`, `isalpha`, `isalnum`, `isspace`, `isupper`, `islower`,
    `toupper`, `tolower`), the `<string.h>` functions in the same
    position (`strlen`, `strcpy`, `strncpy`, `strcat`, `strncat`,
    `strcmp`, `strncmp`, `strchr`, `memcpy`, `memmove`, `memset`,
    `memcmp`), and `<stdlib.h>`'s numeric conversions (`abs`, `labs`,
    `atoi`, `atol`, `atof`). Add a `public` instance method there to make
    another function available to every compiled program without
    regenerating anything: its parameters/return type are the cflat
    ones, mapped the way `CodeGenerator#buildDescriptor` always maps
    them (`int`/`long`/`float`/`double`); an implementation that needs to
    read/write memory directly uses the inherited `mem` field (this
    program's whole simulated address space -- a cflat pointer is a
    plain `long` byte offset into it, cast with `(int)` to index it),
    and one that doesn't simply never mentions it.
  * The rest of `<ctype.h>`/`<string.h>`/`<stdlib.h>` (`iscntrl` and
    friends; `memchr`, `strdup`, `strtok`, `strstr`, ... ; `div`/`ldiv`,
    `strtol`/`strtoul`/`strtod`, `rand`/`srand`, `qsort`/`bsearch`, ...)
    needs no host access at all, so it's written directly as portable
    `static` C in the header itself instead (see `<assert.h>`'s own doc
    comment for why `static`) -- this backend has no way to link two
    separately-compiled translation units together at all (see "JVM
    backend" below), so a real function *body* living in a header,
    spliced into whichever `.c` file `#include`s it, is the only way to
    give a function like this a real implementation shared by every
    program, on top of a real libc's own headers-are-declarations-only
    norm. This runs identically on both backends, with no JVM-specific
    implementation to keep in sync -- except for `long long`-based
    functions (`llabs`, `atoll`, `strtoll`, `strtoull`), left
    undeclared entirely: the x86 backend rejects `long long` outright
    (see above), so a real body using it here -- included everywhere,
    unconditionally -- would break every x86 build that includes the
    header, not just a program that actually calls one of them.
  * **`<stdio.h>` file I/O** (`fopen`/`fclose`/`fread`/`fwrite`/`fgets`/
    `fputc`/`fgetc`/`getchar`/`putchar`/`puts`/`feof`/`ftell`/`fseek`/
    `fflush`/`ferror`/`clearerr`/`fileno`/`perror`/`ungetc`/`gets`, and
    `stdin`/`stdout`/`stderr` themselves) is a real `FILE` struct
    wrapping an fd, and portable-C wrappers around seven new
    `StandardRuntime` primitives (`mir_sysio_open`/`_close`/`_read`/
    `_write`/`_seek`/`_tell`/`_feof`), each backed by a real
    `java.io.RandomAccessFile` (fd 0/1/2 go through `System.in`/`out`/
    `err` instead, the same streams `printf` already uses). `SEEK_SET`/
    `SEEK_CUR`/`SEEK_END` (C99 7.19.9.2's own `fseek()` "whence" values)
    are defined too, matching `mir_sysio_seek()`'s own 0/1/2 convention.
  * **`<stdio.h>` `printf`/`fprintf`/`sprintf`/`snprintf`** (and their
    `v...()` counterparts) are no longer intrinsics or bare externs --
    `printf` was the very last compile-time intrinsic left, and now has
    a real portable-C definition like everything else in this header,
    adapted from Marco Paland's MIT-licensed `printf.c`: a runtime-
    inspected format string (not just a compile-time literal), field
    width/precision/flags, and `%d`/`%i`/`%u`/`%x`/`%X`/`%o`/`%b`/`%c`/
    `%s`/`%p`/`%f`/`%F`/`%%` are all interpreted (not `%e`/`%g`/`%a`, or
    a true `long long`-sized argument -- `%lld`/`ll` is read the same as
    a plain `%ld`/`l`, correct only where `long` is already 64-bit, i.e.
    the JVM backend). `"..."` is walked one `va_arg_t` slot at a time
    (see `<stdarg.h>`) and reinterpreted by hand for each conversion
    (e.g. a `%f`'s bits are read back out as a `double`), since there is
    no type-directed `va_arg()` macro to lean on the way a real libc's
    own `vsnprintf()` has. **This entire family is JVM-only**: `%f`'s
    `double` arithmetic (see the `float`/`double` bullet above) means
    the x86 backend's blanket rejection of floating point applies here
    too, and unlike a genuinely unused function elsewhere in this header
    (see the dead-code pruning note just below), it can't be sidestepped
    by simply not calling `printf()` with a `%f` -- the format string is
    a runtime value in general, so the formatter that handles `%f` is
    always reachable the moment `printf`/`fprintf`/`sprintf`/`snprintf`
    is called *at all*, even with a literal, no-`%f` format string. So,
    as of this real implementation, **no program that calls any function
    in this family can be compiled for the x86 backend** -- a real,
    accepted regression from when they were bare `extern` declarations
    linked against the real libc's own working `printf` on x86; giving
    the JVM backend a real implementation isn't compatible with keeping
    that x86 fallback, since both must now share the one definition in
    this header (see this project's own no-per-backend-headers stance).
  * The x86 backend also now prunes dead code: it only ever type-checks
    the floating-point/struct-by-value-return corners it flatly rejects
    for a `DefinedFunction`/`DefinedVariable` actually reachable (by
    call, name, or address-of) from this file's own externally-visible
    surface (a non-`static` function/variable, or `main`) -- see
    `computeReachableEntities` in `sysdep/x86/CodeGenerator.java`.
    Without it, merely `#include`ing `<stdlib.h>` (which unconditionally
    defines `div()`/`ldiv()`, returning a struct by value, and
    `strtod()`/`atof()`, returning `double`) would break x86 compilation
    for every program, whether or not it actually calls any of those
    three. This is what makes `div`/`ldiv`/`strtod`/`atof` usable on x86
    (for a program that doesn't itself call them) despite the backend's
    own float/struct-by-value limitations -- it just can't rescue
    `printf` and friends, per the previous bullet's own reachability
    argument.
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

### Embedding a compiled program in plain Java

Every non-`static` (in the C sense) top-level function in a `-arch=jvm`
compiled program is already a `public static` method on its generated
class -- exactly like `main` itself, and every public `stdio.h`
function -- so ordinary Java code sharing the same JVM (a GUI, a test
harness, a larger application embedding the compiled program, ...) can
call one directly, no different from calling a method on any other
object, with no subprocess and no native code. A cflat pointer argument
or return value is just the `long` byte-offset it always is on this
backend (see below); a fixed-width `int`/`long`/`float`/`double` maps
the same way `CodeGenerator#buildDescriptor` always maps it.

The one thing about a generated class that *isn't* public is its own
simulated address space (a `private static byte[]` field, `$mem`,
generated fresh with no accessor of its own -- every C function reaches
it directly, needing none). `net.loveruby.cflat.sysdep.jvm.runtime.
StandardRuntime` (which every compiled program's class extends, via
`NativeRuntime` -- see above) has a "Public Java API" section for
exactly this: `readByte`/`readUnsignedByte`/`readShort`/
`readUnsignedShort`/`readInt`/`readIntLE`/`readUnsignedInt`/`readLong`/
`readLongLE`/`readFloat`/`readDouble`/`readPointer` (an alias for
`readLong`, for code specifically walking pointers) and their
`write*()` counterparts, plus `readBytes(addr, length)`/
`writeBytes(addr, data)` for a whole block at once, `readCString(addr)`/
`newString(s)` for a NUL-terminated C string (UTF-8, matching how this
backend's own string literals are encoded), and `memory()` for the
backing `byte[]` itself when bulk array access is specifically what's
needed. Every one of them reads/writes memory in exactly the layout a
compiled program's own generated code already uses (little-endian),
so a value either side writes is always read correctly by the other.

Every compiled class also has a generated `public static
StandardRuntime $runtime()`, returning its own one shared instance (see
`CodeGenerator#emitRuntimeAccessor`) -- `MyProgram.$runtime().
readInt(addr)` reaches it with no reflection needed anywhere. (The `$`
prefix, like every other compiler-synthesized member here -- `$mem`,
`$alloc`, ... -- just means "generated, can't collide with a real C
identifier"; unlike those, this one is deliberately public.)

```java
// A compiled decode.c (cbc -arch=jvm -o decode decode.c) exposing:
//   long allocBuffer(int size);  // wraps malloc()
//   unsigned char *decodeFromMemory(unsigned char *buf, int len,
//                                   int *outW, int *outH, int *outChannels);
StandardRuntime rt = decode.$runtime();
long inputAddr = decode.allocBuffer(fileBytes.length);
rt.writeBytes(inputAddr, fileBytes);
long scratch = decode.allocBuffer(12);
long dataAddr = decode.decodeFromMemory(inputAddr, fileBytes.length,
        scratch, scratch + 4, scratch + 8);
int w = rt.readInt(scratch), h = rt.readInt(scratch + 4);
byte[] pixels = rt.readBytes(dataAddr, w * h * rt.readInt(scratch + 8));
```

Original descrition
====================


    This is the CbC, Cflat programming language compiler.

(Its original Requirements/Installation/Build/Test instructions --
an `ant`+`make`-based workflow -- have been superseded by the
up-to-date ones at the top of this README; see "Building from source"
and "Command-line build & test scripts" above.)

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
