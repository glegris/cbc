#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_BMP
#define STBI_NO_LINEAR
#define STBI_NO_THREAD_LOCALS
#define STBI_ASSERT(x) ((void)0)
#include "stb_image.h"

/* Entry points meant to be called in-process from plain Java (see
 * ShowImage.java), not from the command line -- the JVM backend
 * compiles a non-"static" top-level function like this one to a
 * "public static" method on the generated class (exactly like main()
 * and every public stb_image.h function already is; only a C "static"
 * function becomes "private"), so ShowImage.java can call these
 * directly as ordinary Java, with no subprocess and no native code.
 * The only thing about the generated class that isn't public is its
 * simulated memory array ("$mem"), reached with one reflective field
 * access -- see ShowImage.java's own comment. */

/* A public, callable-from-outside wrapper around malloc(): Java has no
 * other way to get a valid, non-colliding address in this program's
 * simulated memory to copy bytes into (write the BMP file's own bytes
 * into, or reserve scratch space for stbi_load_from_memory()'s three
 * output ints), since malloc() itself is inherited from StandardRuntime
 * rather than compiled from this file (so it isn't in the "every
 * top-level function becomes a method" list at all). */
long
allocBuffer(int size)
{
    return (long)malloc((size_t)size);
}

/* Thin wrapper so Java only has to pass the already-in-memory BMP
 * bytes (which it wrote there itself via allocBuffer()+the reflective
 * "$mem" access) and three scratch-int addresses, instead of also
 * having to build a filesystem path -- and so this whole demo works
 * the same way whether "decode" is invoked from Java or the CLI, since
 * both ultimately reach the same stb_image.h decoder. */
unsigned char *
decodeFromMemory(unsigned char *buffer, int len, int *outW, int *outH, int *outChannels)
{
    return stbi_load_from_memory(buffer, len, outW, outH, outChannels, 3);
}

int
main(int argc, char **argv)
{
    int w, h, channels;
    unsigned char *data = stbi_load(argv[1], &w, &h, &channels, 3);
    if (data == NULL) {
        printf("decode failed: %s\n", stbi_failure_reason());
        return 1;
    }
    printf("%d %d %d\n", w, h, channels);
    int i;
    int n = w * h * 3;
    for (i = 0; i < n; i++) {
        printf("%d ", data[i]);
    }
    printf("\n");
    stbi_image_free(data);
    return 0;
}
