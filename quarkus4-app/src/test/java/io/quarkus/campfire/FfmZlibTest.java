package io.quarkus.campfire;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.Inflater;

import org.junit.jupiter.api.Test;

import campfire.kit.Bytes;
import campfire.kit.JdkDeflate;

/** The system zlib through FFM compresses to what the JDK's does: the same content back, with and without a dictionary. */
class FfmZlibTest {
    private static byte[] inflate(byte[] deflated, byte[] dictionary, int length) throws Exception {
        Inflater inflater = new Inflater(true);
        try {
            if (dictionary != null) inflater.setDictionary(dictionary, Math.max(0, dictionary.length - 32 * 1024), Math.min(dictionary.length, 32 * 1024));
            inflater.setInput(deflated);
            byte[] out = new byte[length];
            int n = 0;
            while (n < length) {
                int got = inflater.inflate(out, n, length - n);
                if (got == 0 && (inflater.finished() || inflater.needsInput())) break;
                n += got;
            }
            assertTrue(n == length, "inflated " + n + " of " + length);
            return out;
        } finally {
            inflater.end();
        }
    }

    private static ByteBuffer direct(byte[] bytes) {
        return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip().asReadOnlyBuffer();
    }

    @Test
    void sameContentAsTheJdk() throws Exception {
        Random random = new Random(7);
        for (int size : new int[] {0, 1, 100, 1500, 40_000, 200_000}) {
            StringBuilder text = new StringBuilder();
            while (text.length() < size) text.append("<div class=\"message\" id=\"m").append(random.nextInt(1000)).append("\">hello ").append(random.nextInt()).append("</div>");
            byte[] input = text.substring(0, size).getBytes(StandardCharsets.UTF_8);
            byte[] dictionary = ("<html><body>" + text.substring(0, Math.min(size, 50_000))).getBytes(StandardCharsets.UTF_8);
            for (ByteBuffer dict : new ByteBuffer[] {null, ByteBuffer.wrap(dictionary), ByteBuffer.wrap(dictionary).asReadOnlyBuffer(), direct(dictionary)}) {
                for (ByteBuffer in : new ByteBuffer[] {ByteBuffer.wrap(input), direct(input)}) {
                    byte[] ffm = FfmZlib.INSTANCE.deflate(dict, in, Bytes.Companion.getHEAP()).array();
                    byte[] jdk = JdkDeflate.INSTANCE.deflate(dict, in, Bytes.Companion.getHEAP()).array();
                    byte[] preset = dict == null ? null : dictionary;
                    assertArrayEquals(input, inflate(ffm, preset, size), "ffm, size " + size);
                    assertArrayEquals(input, inflate(jdk, preset, size), "jdk, size " + size);
                    assertTrue(size < 1500 || ffm.length < size / 2, "compressed " + ffm.length + " of " + size);
                }
            }
        }
    }
}
