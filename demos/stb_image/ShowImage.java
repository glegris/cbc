import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import net.loveruby.cflat.sysdep.jvm.runtime.StandardRuntime;

/**
 * Plain Java (javac-compiled, not cbc) program that decodes a BMP file
 * using the real stb_image.h C code, compiled by cbc to the JVM, and
 * renders the result as a PNG -- entirely in-process, in one JVM, with
 * no subprocess, no native code, and (as of cbc's new public runtime
 * API -- see StandardRuntime's own doc comment on its "Public Java
 * API" section) no reflection either.
 *
 * "decode.class" (produced by "cbc -arch=jvm -o decode decode.c") is a
 * public class whose every non-C-"static" top-level function --
 * including the two written for exactly this purpose,
 * allocBuffer()/decodeFromMemory(), and every public stb_image.h
 * function itself (stbi_load_from_memory(), stbi_failure_reason(), ...)
 * -- is already a public static method callable directly as ordinary
 * Java. "decode.$runtime()" (generated on every compiled class, always
 * under that exact, collision-proof name -- a "$" can never appear in a
 * real C identifier) returns the program's own StandardRuntime
 * instance, whose public readInt()/writeBytes()/memory()/... let this
 * class inspect and modify the compiled program's simulated memory
 * directly, with no more privilege than any of its own compiled C code
 * already has over that same memory.
 */
public class ShowImage {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java ShowImage <input.bmp> <output.png>");
            System.exit(1);
        }
        String bmpPath = args[0];
        String outPath = args[1];

        byte[] fileBytes = Files.readAllBytes(Paths.get(bmpPath));

        // The first call into "decode" (any of them) triggers the
        // JVM's ordinary class-initialization (<clinit>), which is
        // what sets up the simulated memory/heap allocator in the
        // first place -- so by the time this returns, rt's own
        // read/write methods are already safe to use.
        StandardRuntime rt = decode.$runtime();

        long inputAddr = decode.allocBuffer(fileBytes.length);
        if (inputAddr == 0) {
            throw new RuntimeException("allocBuffer(" + fileBytes.length + ") failed (out of simulated memory)");
        }
        long scratchAddr = decode.allocBuffer(12); // 3 ints: width, height, channels
        if (scratchAddr == 0) {
            throw new RuntimeException("allocBuffer(12) failed (out of simulated memory)");
        }

        // Copy the BMP file's own bytes into the compiled program's
        // memory at the address it just gave us for them.
        rt.writeBytes(inputAddr, fileBytes);

        long dataAddr = decode.decodeFromMemory(inputAddr, fileBytes.length,
                scratchAddr, scratchAddr + 4, scratchAddr + 8);
        if (dataAddr == 0) {
            System.err.println("decode failed: " + rt.readCString(decode.stbi_failure_reason()));
            System.exit(1);
        }

        int w = rt.readInt(scratchAddr);
        int h = rt.readInt(scratchAddr + 4);
        int channels = rt.readInt(scratchAddr + 8);

        byte[] pixels = rt.readBytes(dataAddr, w * h * channels);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int p = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = pixels[p] & 0xFF;
                int g, b;
                if (channels >= 3) {
                    g = pixels[p + 1] & 0xFF;
                    b = pixels[p + 2] & 0xFF;
                } else {
                    g = r;
                    b = r;
                }
                img.setRGB(x, y, (r << 16) | (g << 8) | b);
                p += channels;
            }
        }

        decode.stbi_image_free(dataAddr);

        ImageIO.write(img, "png", new File(outPath));
        System.out.println("Wrote " + outPath + " (" + w + "x" + h + ", " + channels + " channels)");
    }
}
