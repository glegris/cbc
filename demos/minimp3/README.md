# MP3 decoding with minimp3 via cbc (JVM backend) -- 100% Java, no reflection

`minimp3.h` (the real C library, barely modified) is compiled by
`cbc -arch=jvm`, then called directly in pure Java, with no subprocess
and no reflection -- exactly the same approach as `demos/stb_image`.

## How it works

`decode.c` exposes three small functions on top of minimp3's core API
(`mp3dec_init`/`mp3dec_decode_frame`):

- `long allocBuffer(int size)` -- wraps `malloc()`.
- `void initDecoder(unsigned char *buf, int len)` -- initializes the
  decoder over a buffer Java has already filled.
- `int decodeNextFrame(void)` -- advances one MP3 frame and returns the
  number of decoded samples (all channels combined), or 0 at end of file.
- `frameBufferAddr()`/`frameChannels()`/`frameSampleRate()` -- access to
  the small PCM buffer (reused every frame) and its metadata.

`PlayMp3.java` loops over `decode.decodeNextFrame()`, reads each frame
via `decode.$runtime().readBytes(...)`, accumulates the PCM in a Java
`ByteArrayOutputStream`, then writes a `.wav` (`AudioSystem.write`) and
attempts live playback (`Clip`) -- falling back cleanly to "playback
skipped" when the environment has no audio device (as is the case in
this sandbox).

**Why frame by frame rather than `mp3dec_load_buf` (minimp3_ex.h's
"all-in-one" API)?** That API goes through an `mp3dec_io_t` of
read/seek function pointers (useful for real file/network streaming,
pointless here since the file is already in memory), and the JVM
backend can only call indirectly through a *plain* function-pointer
variable, not a struct member (the same limitation already documented
for `demos/stb_image`). Decoding frame by frame also avoids
accumulating an entire song's PCM (tens of MB) in cbc's own simulated
heap, sized for ordinary C programs -- Java, with its own heap, takes
care of that instead.

## Files

- `minimp3.h` -- local copy of the attached library, unmodified (only
  the core `mp3dec_init`/`mp3dec_decode_frame` is used; no patch needed
  here, unlike `stb_image.h`).
- `decode.c` -- the 5 functions above plus an empty `main()`.
- `PlayMp3.java` -- an ordinary Java program, zero reflection, zero
  subprocess.
- `Padords.mp3` -- the file the user attached.

## To replay

```sh
cbc -arch=jvm -I <cbc>/import -o decode decode.c
javac -cp .:<cbc>/build/classes PlayMp3.java
java -cp .:<cbc>/build/classes PlayMp3 Padords.mp3 out.wav
```

## What this needed on the compiler side

This demo surfaced and fixed four real cbc gaps/bugs while compiling
minimp3.h and decoding an actual file:

1. **Non-literal array size** (`float mdct_overlap[2][9*32]`) --
   `arraySuffix()`/`typePostfix()` only accepted a raw `<INTEGER>`
   token between brackets; they now accept any compile-time integer
   constant expression (folded at parse time by the same evaluator
   designated-initializer indices already use, `evalConstIndex`).
2. **`U` suffix ignored on a decimal literal** -- `UINT64_MAX`
   (`18446744073709551615ULL`) crashed the compiler outright
   (`NumberFormatException`): `integerValue()` only applied the
   "fall back to unsigned" treatment (for values beyond
   `Long.MAX_VALUE`) to hex/octal constants, never to decimal ones,
   even with an explicit `U` suffix -- fixed to follow C99 6.4.4.1p5's
   table (an explicit `U` suffix always permits the full unsigned
   64-bit range, whatever the base).
3. **`float *= expr;` (and `+=`/`-=`/`/=`) rejected** -- `L3_ldexp_q2`'s
   `y *= g_expfrac[...]` failed with *"wrong operand type for \*:
   float"*: `TypeChecker`'s `visit(OpAssignNode)` only accepted integer
   operands for these four operators, even though they're valid on
   float types in C99 (only `%`, `&`, `|`, `^`, `<<`, `>>` stay
   integer-only) -- fixed to follow the same "arithmetic" path as the
   ordinary binary `+`/`-`/`*`/`/` operator.
4. **Real bug: wrong pointer decay for a multi-dimension array** --
   `unsigned char *p = s.ist_pos[ch];` (where `ist_pos` is
   `uint8_t[2][39]`) compiled without error but read memory at random
   (silently, or with an `IndexOutOfBoundsException`, depending on the
   address landed on): `IRGenerator`'s `visit(MemberNode)`/
   `visit(PtrMemberNode)`/`visit(DereferenceNode)` correctly decay an
   array-typed result to its own address (instead of "loading" it like
   a scalar), but `visit(ArefNode)` never did that check -- fixed to
   follow the same `isLoadable()` guard as the other three.

No patch was needed on `minimp3.h` itself then (unlike `stb_image.h`,
whose JVM backend can't call some struct-member function pointers): the
four fixes above were all genuine compiler gaps/bugs, not architectural
limitations to work around.

One last subtlety, on the demo's side this time (not a cbc bug):
`PlayMp3.java` allocates the input buffer with 32 bytes of slack past
the real end of the file (never included in the length passed to
`initDecoder()`). minimp3's bitstream reader (`L3_huffman`) prefetches
up to 4 bytes past its logical position by construction -- harmless on
real hardware, where the last MP3 frame is followed by whatever else
shares the process's address space, but a hard exception against cbc's
own bounded simulated heap at the very end of the file without that
slack.

## Decoding speed: 56.8s to 1.36s

Decoding the whole of `Padords.mp3` (112s of music) first took **56.8s**
via the JVM backend -- compared to ~0.11-0.13s for the same library
compiled to native C (gcc -O2), a ~425x factor. Digging in (profiling,
reading the actual bytecode via `javap -c`) showed the dominant cause
was neither `ByteBuffer` (in fact faster than a hand-rolled `byte[]`
array -- HotSpot intrinsifies it) nor the simulated memory model itself,
but two things combined:

1. **`mp3d_synth`** (minimp3's polyphase synthesis filter) generated
   **8955 bytes** of bytecode -- just over HotSpot's default method
   compilation limit (`-XX:HugeMethodLimit=8000`): this function ran
   permanently interpreted as a result.
2. Every local variable (even a plain loop counter) lived in cbc's own
   simulated heap, with a full address recomputation (`frameBase` +
   offset + a `ByteBuffer` call) on every single access, with no reuse
   at all -- exactly what bloated `mp3d_synth` past that limit in the
   first place.

The compiler now promotes any local variable/parameter/temporary whose
address is never taken in its own function to a real JVM local variable
slot (`ILOAD`/`ISTORE`; see the corresponding section in the main
README and `EscapeAnalysis`). Result on this same file, with nothing
else changed: **1.36s** (best of 3 runs, no special JVM flag needed) --
a ~42x factor, with the decoded audio confirmed bit-for-bit identical
(same PCM SHA-256) before and after. `mp3d_synth` itself dropped back to
4103 bytes, back under the JIT compilation limit. It remains ~10-12x
slower than native C, mostly inherent to this backend having no SIMD
and to the normal gap between JIT-compiled bytecode and native machine
code.
