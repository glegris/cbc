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

`test/run_jvm.sh` is a from-scratch port of the same `test/*.cb` files to
the JVM backend (`-arch=jvm`), and is the main way to test cbc itself in
an environment that only has a JDK and no x86 toolchain at all. A fixed
set of tests are reported as `KNOWN-DIFF` rather than pass/fail, for
reasons that are architectural rather than bugs (see the JVM backend
section below for the underlying cause in each case): `usertype`,
`addressof`, `ptrdiff`, `sizeof-type`, `sizeof-expr`, `implicitaddr`,
`funcptr`, `gvar`, `assign`, `varargs`.

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
    given wins. Only a single, non-chained designator per element
    (`.member` or `[index]`, never `.a.b`, `[i].a`, or both at once), and
    `[index]` needs a plain integer literal, not a general constant
    expression. Two more scope limits, regardless of designators: the
    array's size must be given explicitly (inferring it from the
    initializer list, like C's `int a[] = {1,2,3};`, isn't supported),
    and a global (or `static` local)'s initializer elements must be
    compile-time constants, same as plain C requires at file scope; a
    non-static local's can be arbitrary runtime expressions, lowered to
    ordinary element-by-element assignments run where the declaration
    appears.
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

## Preprocessor

Every `.cb`/`.hb` file is now run through a real C-style preprocessor
before it reaches the parser, on both backends. It's a token-based pass
(not raw text substitution), so it gets macro-argument handling, `#`/`##`
and recursive macro expansion right rather than approximately right:

  * **Object-like and function-like macros**: `#define VERSION 42` and
    `#define ADD(a, b) ((a) + (b))`. Arguments are macro-expanded before
    substitution (unless adjacent to `#`/`##`), and a macro never expands
    through its own invocation (directly or via another macro that calls
    back into it), so `#define X X + 1` just adds one `X` to the output
    instead of looping forever. `#undef` removes a definition.
  * **Stringification and token pasting**: `#define STR(x) #x` and
    `#define CONCAT(a, b) a ## b`, per C99 semantics (escaping embedded
    `"`/`\` when stringifying, retokenizing the pasted result).
  * **Conditional compilation**: `#ifdef`/`#ifndef`/`#if`/`#elif`/`#else`/
    `#endif`, with `#if`/`#elif` backed by a real constant-expression
    evaluator supporting the full C operator set (arithmetic, bitwise,
    logical, comparison, shifts, `?:`, `defined(NAME)`/`defined NAME`) at
    the usual precedence.
  * **`#include "..."`/`#include <...>`**, searched next to the including
    file (quoted form only) and then on the same `-I` path used for
    `import`; `#error "message"` aborts the compile with that message,
    and `#pragma` is accepted and silently ignored.
  * **`-E`**: prints the preprocessed output and stops, like `gcc -E`,
    for inspecting/debugging macro expansion independent of the rest of
    the pipeline.

Not supported, scoped out: variadic macros (`...`/`__VA_ARGS__`),
predefined macros (`__LINE__`, `__FILE__`, `__DATE__`, ...), `#line`, and
spreading one function-like macro call across multiple lines (the whole
argument list must be on one logical line, after backslash-newline
splicing). Every source line still produces exactly one output line so
that error messages elsewhere in the compiler keep pointing at the right
line, except across an `#include`: the included file's lines are spliced
in inline, so line numbers in the including file *after* the `#include`
shift by however many lines that added (there's no `#line`-style fixup
for it).

## JVM backend (`-arch=jvm`)

In addition to native x86 assembly, cbc can compile a cflat source file
directly to a JVM class file, using the [ASM](https://asm.ow2.io/) bytecode
library. There is no separate assemble/link step for this target: the
`.class` file it produces is already runnable.

```shell
cbc -arch=jvm test/add.cb
java add
```

(`--target=jvm` is accepted as a longer alias for `-arch=jvm`.) The
produced class is named after the source file, sanitized into a valid
Java identifier (e.g. `while-break.cb` becomes class `while_break`), so
that the file name and the class name it contains always match.

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

Remaining gaps: calling a function that isn't defined in the same source
file is rejected, except for three libc intrinsics translated to real JVM
calls so printf-based programs work: `putchar(int)`, `puts(char*)` and
`printf(char*, ...)` -- the format string itself must still be a
compile-time literal, but `puts`/`%s` now accept any `char*` expression,
not just literals. These three intrinsics have no real address either,
so `&putchar` and friends are rejected too. Lastly, `long` and every
pointer type are real 8-byte JVM `long`s here (see the class comment on
`sysdep/jvm/CodeGenerator.java`), so `sizeof(long)`/`sizeof(T*)` are 8,
not 4 like on the (32-bit-only) x86 backend.

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
