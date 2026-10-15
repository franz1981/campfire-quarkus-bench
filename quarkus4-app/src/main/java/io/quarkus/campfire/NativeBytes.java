package io.quarkus.campfire;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

import campfire.kit.Bytes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * Kept bytes ({@link Bytes}: cached fragments' HTML and compressed pieces) in native memory, one
 * allocation each (Netty's unpooled allocator: {@code malloc}, and {@code free} on the last release,
 * so the process holds what the caches hold), outside the garbage-collected heap: a cache's entries live long enough to be promoted and
 * then die, which on the heap costs old-generation collections (NOTES.md, "The fragment cache, the deflate and the fragments' memory"). Freed when
 * the last reference is released; {@link #liveBytes()} counts what is held, to find leaks.
 */
final class NativeBytes extends Bytes {
    /** The store: a direct buffer of its own per copy. */
    static final Store STORE = NativeBytes::copy;

    private static final LongAdder LIVE_BYTES = new LongAdder();
    private static final LongAdder LIVE_BUFFERS = new LongAdder();

    private final ByteBuf buf;
    private final int size;

    private NativeBytes(ByteBuf buf) {
        this.buf = buf;
        this.size = buf.readableBytes();
    }

    private static Bytes copy(ByteBuffer src) {
        int n = src.remaining();
        ByteBuf buf = UnpooledByteBufAllocator.DEFAULT.directBuffer(n, n);
        buf.writeBytes(src);
        LIVE_BYTES.add(n);
        LIVE_BUFFERS.increment();
        return new NativeBytes(buf);
    }

    /** The bytes held now in native memory, and in how many buffers. */
    static long liveBytes() {
        return LIVE_BYTES.sum();
    }

    static long liveBuffers() {
        return LIVE_BUFFERS.sum();
    }

    /** A view for Netty to write, valid while this is held (no reference of its own). */
    ByteBuf view() {
        return buf.duplicate();
    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public ByteBuffer buffer() {
        return buf.nioBuffer(0, size).asReadOnlyBuffer();
    }

    @Override
    public byte[] array() {
        byte[] out = new byte[size];
        buf.getBytes(0, out);
        return out;
    }

    @Override
    public Bytes retain() {
        buf.retain();
        return this;
    }

    @Override
    public void release() {
        if (buf.release()) {
            LIVE_BYTES.add(-size);
            LIVE_BUFFERS.decrement();
        }
    }
}
