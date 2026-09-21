/* Decode an MP3 file with minimp3 (the real C library, compiled by cbc's
 * JVM backend) and hand the resulting PCM back to plain Java -- same
 * approach as demos/stb_image: no subprocess, no reflection, just
 * decode.$runtime() + the compiler's public runtime read/write API.
 *
 * [cbc-patch] Only minimp3.h (the core, single-buffer decoder) is used
 * here, not minimp3_ex.h's higher-level mp3dec_load_buf()/mp3dec_ex_*
 * streaming API: those go through an "mp3dec_io_t" of read/seek
 * function pointers (needed for real file/socket streaming, which this
 * demo has no use for -- the whole file is already in memory), and the
 * JVM backend can only call indirectly through a plain function-pointer
 * variable, not one reached through a struct member (see README's
 * "JVM backend limitations" section) -- the same limitation the
 * stb_image.h demo works around by calling the stdio backend directly
 * instead of through stbi_io_callbacks.
 *
 * The whole song's decoded PCM would also be far bigger than a single
 * decode frame lets you assume: rather than accumulate it inside cbc's
 * own simulated heap (sized for typical C programs, not for holding an
 * entire song's worth of audio at once), Java pulls one frame at a
 * time -- decodeNextFrame() advances the decoder and reports how many
 * samples landed in a small, fixed, reused frame buffer, and Java's own
 * (practically unbounded) heap owns growing the final PCM byte array. */
#define MINIMP3_IMPLEMENTATION
#define MINIMP3_NO_SIMD
#include "minimp3.h"

static mp3dec_t g_dec;
static mp3dec_frame_info_t g_info;
static mp3d_sample_t g_frame_pcm[MINIMP3_MAX_SAMPLES_PER_FRAME];
static unsigned char *g_buf;
static int g_remaining;

long
allocBuffer(int size)
{
    return (long)malloc((size_t)size);
}

void
initDecoder(unsigned char *buf, int len)
{
    mp3dec_init(&g_dec);
    g_buf = buf;
    g_remaining = len;
}

/* Decodes forward until a frame actually yields samples (skipping
 * ID3/garbage frames that decode to zero samples, same as minimp3's own
 * canonical "do { ... } while (info.frame_bytes);" usage), and returns
 * the sample count (all channels interleaved) written to g_frame_pcm --
 * or 0 once the input is exhausted. */
int
decodeNextFrame(void)
{
    int samples;

    if (g_remaining <= 0)
        return 0;
    do
    {
        samples = mp3dec_decode_frame(&g_dec, g_buf, g_remaining, g_frame_pcm, &g_info);
        g_buf      += g_info.frame_bytes;
        g_remaining -= g_info.frame_bytes;
    } while (!samples && g_info.frame_bytes && g_remaining > 0);
    return samples * g_info.channels;
}

long
frameBufferAddr(void)
{
    return (long)g_frame_pcm;
}

int
frameChannels(void)
{
    return g_info.channels;
}

int
frameSampleRate(void)
{
    return g_info.hz;
}

int
main(void)
{
    return 0;
}
