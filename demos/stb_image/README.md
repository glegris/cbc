# Image decoding with stb_image.h via cbc (JVM backend) -- 100% Java, no reflection

`stb_image.h` (the real C library, barely modified) is compiled by
`cbc -arch=jvm`, then **called directly, in pure Java, with no
subprocess and no reflection**: `ShowImage.java` calls public static
methods on the compiled C program, plus a new public `cbc` API to
read/write its simulated memory.

## How it works

`cbc` compiles every top-level C function (not C `static`) into a
`public static` Java method -- already true of `main()` and of every
public function in `stb_image.h` (`stbi_load_from_memory`,
`stbi_failure_reason`, `stbi_image_free`, ...). `decode.c` adds two
small functions in that same spirit:

- `long allocBuffer(int size)` -- wraps `malloc()`.
- `unsigned char *decodeFromMemory(...)` -- wraps `stbi_load_from_memory()`.

New on the compiler side (built for this demo): `cbc` now exposes a
real **public Java API** to read/write a compiled program's simulated
memory, with no reflection:

- Every generated class has a `public static StandardRuntime
  $runtime()` method returning its shared runtime instance.
- `StandardRuntime` has a "Public Java API" section:
  `readByte`/`readUnsignedByte`/`readShort`/`readUnsignedShort`/
  `readInt`/`readIntLE`/`readUnsignedInt`/`readLong`/`readLongLE`/
  `readFloat`/`readDouble`/`readPointer`, their `write*()` counterparts,
  `readBytes`/`writeBytes` for a whole block, `readCString`/
  `newString` for a C string, and `memory()` for the raw array.

`ShowImage.java` therefore uses NO reflection at all: `decode.$runtime()`
plus the `read*`/`write*` methods are enough.

## Files

- `stb_image.h` -- local copy of the attached library, with a few
  minimal, documented patches (`[cbc-patch]`): BMP-only support, a
  simplified static-validation `typedef`, and the indirect calls
  `s->io.read/skip/eof(...)` replaced with direct calls (cbc can't yet
  call indirectly through a function pointer stored in a struct member).
- `decode.c` -- `main()` (CLI, unchanged) + `allocBuffer`/
  `decodeFromMemory` designed for Java.
- `ShowImage.java` -- an ordinary Java program, zero reflection, zero
  subprocess.
- `mascot.bmp` -- the image the user attached (resized), converted to BMP.

## To replay

```sh
cbc -arch=jvm -I <cbc>/import -o decode decode.c
javac -cp .:<cbc>/build/classes ShowImage.java
java -cp .:<cbc>/build/classes ShowImage mascot.bmp out.png
```

## What this needed on the compiler side

Two waves of changes to the cbc repo itself (`claude/elegant-bohr-63jhe2`):

1. **`e914866`** -- around a dozen real C-language gaps found while
   compiling stb_image.h: comma operator, anonymous enum, "east const",
   multiple declarators in a struct member, trailing comma in an
   aggregate literal, a function name as a static-initializer constant,
   `extern` function definitions, a constant expression in a `case`,
   plus two genuine bugs (pointer-to-const confused with a const
   pointer; a crash on an integer `typedef` type).
2. **`508e7b5`** -- the new public Java API (`$runtime()` +
   `StandardRuntime`'s "Public Java API") described above, built
   directly in response to the request to avoid reflection.

`stb_image.h` itself isn't meant to join cbc's permanent test suite (an
8000-line third-party library unrelated to the compiler) -- this folder
is just the demo; the new Java API, on the other hand, is tested
permanently via `test/pubapi-runtime.c` + `test/PubapiRuntimeTest.java`
in the cbc repo.
