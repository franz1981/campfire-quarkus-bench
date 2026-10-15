package io.quarkus.campfire;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import campfire.kit.Bytes;
import campfire.kit.PageParts;

/**
 * {@link PageParts.Deflate} through the system's zlib ({@code libz.so.1}: zlib-ng on Fedora 43) called
 * with the FFM API instead of the JDK's own zlib behind {@code java.util.zip.Deflater}: the same raw
 * deflate at level 6 with a preset dictionary, sync-flushed, reading direct buffers in place and a
 * heap-backed dictionary pinned. The compressed bytes differ between zlib builds; the content they
 * inflate to doesn't.
 */
final class FfmZlib implements PageParts.Deflate {
    static final FfmZlib INSTANCE = new FfmZlib();

    private static final int WINDOW = 32 * 1024;
    private static final int Z_OK = 0, Z_DATA_ERROR = -3, Z_BUF_ERROR = -5, Z_SYNC_FLUSH = 2, Z_DEFLATED = 8;
    /** {@code z_stream} on x86-64 (zlib and zlib-ng's compatible layout): the fields set and read here. */
    private static final int STREAM_SIZE = 112, NEXT_IN = 0, AVAIL_IN = 8, NEXT_OUT = 24, AVAIL_OUT = 32;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBZ = libz();
    /**
     * Every call is critical (no thread-state transition: short, no callbacks); the dictionary's goes
     * further and takes a heap segment pinned for the call ({@code critical(true)}), so a heap-backed
     * dictionary isn't copied. The input can't: {@code deflate} reads it through {@code z_stream.next_in},
     * a pointer in the struct, which a heap array can't supply.
     */
    private static final Linker.Option CRITICAL = Linker.Option.critical(false), CRITICAL_HEAP = Linker.Option.critical(true);
    private static final MethodHandle DEFLATE_INIT2 = LINKER.downcallHandle(LIBZ.findOrThrow("deflateInit2_"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle DEFLATE_SET_DICTIONARY = LINKER.downcallHandle(LIBZ.findOrThrow("deflateSetDictionary"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), CRITICAL_HEAP);
    private static final MethodHandle DEFLATE = LINKER.downcallHandle(LIBZ.findOrThrow("deflate"), FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), CRITICAL);
    private static final MethodHandle DEFLATE_END = LINKER.downcallHandle(LIBZ.findOrThrow("deflateEnd"), FunctionDescriptor.of(JAVA_INT, ADDRESS), CRITICAL);
    private static final MethodHandle ZLIB_VERSION = LINKER.downcallHandle(LIBZ.findOrThrow("zlibVersion"), FunctionDescriptor.of(ADDRESS));
    /** The library's own version string, which {@code deflateInit2_} checks against the caller's. */
    private static final MemorySegment VERSION = versionString();

    private FfmZlib() {
    }

    private static SymbolLookup libz() {
        try {
            return SymbolLookup.libraryLookup("libz.so.1", Arena.global());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("libz.so.1 (the system zlib) was not found for CAMPFIRE_DEFLATE=ffm; CAMPFIRE_DEFLATE=jdk uses the JDK's own zlib", e);
        }
    }

    private static MemorySegment versionString() {
        try {
            return ((MemorySegment) ZLIB_VERSION.invokeExact()).reinterpret(64);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** The library's version string, for the log. */
    static String version() {
        return VERSION.getString(0);
    }

    /**
     * {@code deflateEnd} answers {@code Z_DATA_ERROR} for a stream ended before {@code Z_FINISH}, as
     * every piece's is (the JDK's {@code Deflater.end} ignores it too); any other failure of it is
     * reported only when the deflate itself succeeded, so that it never hides the deflate's error.
     */
    @Override
    public Bytes deflate(ByteBuffer dictionary, ByteBuffer input, Bytes.Store store) {
        int length = input.remaining();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment stream = arena.allocate(STREAM_SIZE, 8);
            check((int) DEFLATE_INIT2.invokeExact(stream, 6, Z_DEFLATED, -15, 8, 0, VERSION, STREAM_SIZE), "deflateInit2");
            boolean deflated = false;
            try {
                if (dictionary != null && dictionary.hasRemaining()) {
                    ByteBuffer window = dictionary.duplicate();
                    window.position(Math.max(window.position(), window.limit() - WINDOW));
                    MemorySegment preset = MemorySegment.ofBuffer(window);
                    check((int) DEFLATE_SET_DICTIONARY.invokeExact(stream, preset, (int) preset.byteSize()), "deflateSetDictionary");
                }
                MemorySegment in = segment(input.duplicate(), arena);
                stream.set(ADDRESS, NEXT_IN, in);
                stream.set(JAVA_INT, AVAIL_IN, length);
                long capacity = length + (length >> 3) + 128;
                MemorySegment out = arena.allocate(capacity);
                long produced = 0;
                while (true) {
                    if (produced == capacity) {
                        MemorySegment bigger = arena.allocate(capacity * 2);
                        MemorySegment.copy(out, 0, bigger, 0, produced);
                        out = bigger;
                        capacity *= 2;
                    }
                    stream.set(ADDRESS, NEXT_OUT, out.asSlice(produced));
                    stream.set(JAVA_INT, AVAIL_OUT, (int) (capacity - produced));
                    int rc = (int) DEFLATE.invokeExact(stream, Z_SYNC_FLUSH);
                    if (rc != Z_OK && rc != Z_BUF_ERROR) throw new IllegalStateException("deflate: " + rc);
                    int availOut = stream.get(JAVA_INT, AVAIL_OUT);
                    produced = capacity - availOut;
                    if (stream.get(JAVA_INT, AVAIL_IN) == 0 && availOut > 0) break;
                }
                Bytes kept = store.copyOf(out.asSlice(0, produced).asByteBuffer());
                deflated = true;
                return kept;
            } finally {
                int rc = (int) DEFLATE_END.invokeExact(stream);
                if (deflated && rc != Z_OK && rc != Z_DATA_ERROR) throw new IllegalStateException("deflateEnd: " + rc);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** {@code buffer}'s remaining bytes as native memory for {@code next_in}: a direct buffer in place, a heap one copied. */
    private static MemorySegment segment(ByteBuffer buffer, Arena arena) {
        MemorySegment of = MemorySegment.ofBuffer(buffer);
        if (buffer.isDirect()) return of;
        MemorySegment copy = arena.allocate(of.byteSize());
        MemorySegment.copy(of, 0, copy, 0, of.byteSize());
        return copy;
    }

    private static void check(int rc, String call) {
        if (rc != Z_OK) throw new IllegalStateException(call + ": " + rc);
    }
}
