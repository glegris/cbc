import net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.*;

/** Decodes an MP3 with minimp3 (the real C library, compiled by cbc's JVM
 *  backend) and plays/saves it -- 100% pure Java, zero reflection, zero
 *  subprocess, same approach as demos/stb_image/ShowImage.java: just
 *  decode.$runtime() plus the compiler's public runtime read/write API.
 *
 *  decode.c pulls the song apart one MP3 frame at a time rather than
 *  decoding the whole thing into cbc's own simulated heap (which, unlike
 *  a real OS process, is a fixed-size array sized for typical C
 *  programs, not for holding an entire song's PCM at once) -- so this
 *  class owns growing the final PCM buffer itself, in genuine JVM heap,
 *  pulling one small frame at a time via decodeNextFrame(). */
public class PlayMp3 {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PlayMp3 <file.mp3> [out.wav]");
            System.exit(1);
        }
        byte[] fileBytes = Files.readAllBytes(Paths.get(args[0]));

        StandardRuntime rt = decode.$runtime();
        // minimp3's bitstream reader (L3_huffman's "bs_cache" prefetch)
        // unconditionally reads up to 4 bytes ahead of its logical
        // position -- harmless on real hardware, where the last MP3
        // frame is followed by whatever else shares the process's
        // address space, but a hard out-of-bounds exception against
        // cbc's own bounds-checked simulated heap once decoding reaches
        // the very last frame with no such bytes to spare. A few extra
        // zeroed bytes past the real file content (never included in
        // the length handed to initDecoder(), so the decoder's own
        // frame boundaries are unaffected) gives it the same harmless
        // slack a real allocator would.
        int pad = 32;
        long inputAddr = decode.allocBuffer(fileBytes.length + pad);
        rt.writeBytes(inputAddr, fileBytes);
        decode.initDecoder(inputAddr, fileBytes.length);

        ByteArrayOutputStream pcm = new ByteArrayOutputStream(fileBytes.length * 8);
        int channels = 0, hz = 0, frames = 0;
        int interleavedSamples;
        while ((interleavedSamples = decode.decodeNextFrame()) > 0) {
            if (channels == 0) {
                channels = decode.frameChannels();
                hz = decode.frameSampleRate();
            }
            long frameAddr = decode.frameBufferAddr();
            pcm.write(rt.readBytes(frameAddr, interleavedSamples * 2));
            frames++;
        }
        byte[] pcmBytes = pcm.toByteArray();
        int totalSamples = pcmBytes.length / 2;
        System.out.println("Decoded " + frames + " MP3 frames, "
                + totalSamples + " samples, " + channels + " ch, " + hz + " Hz ("
                + String.format("%.1f", totalSamples / (double) channels / hz) + "s)");

        AudioFormat format = new AudioFormat(hz, 16, channels, true, false);

        File out = new File(args.length > 1 ? args[1] : "out.wav");
        try (AudioInputStream ais = new AudioInputStream(
                new ByteArrayInputStream(pcmBytes), format, totalSamples / channels)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        }
        System.out.println("Wrote " + out);

        try {
            Clip clip = AudioSystem.getClip();
            clip.open(format, pcmBytes, 0, pcmBytes.length);
            clip.start();
            Thread.sleep(clip.getMicrosecondLength() / 1000 + 200);
            clip.close();
            System.out.println("Playback finished.");
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // getClip() throws LineUnavailableException when a mixer
            // exists but has no free line, and IllegalArgumentException
            // when there is no audio hardware/mixer at all (e.g. this
            // container) -- both mean the same thing here: nothing to
            // play through, so fall back to the WAV file already saved.
            System.out.println("(no audio output device in this environment; "
                    + "playback skipped -- open " + out + " locally to listen)");
        }
    }
}
