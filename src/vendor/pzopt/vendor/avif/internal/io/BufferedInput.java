// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.io;


import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Little-endian primitive reader backed by a reusable staging buffer.
///
/// The decoder frequently mixes scalar reads with byte-array reads that cross underlying I/O
/// boundaries. `BufferedInput` keeps unread bytes in an internal buffer so callers can consume
/// the stream as a sequence of little-endian primitives without worrying about partial reads.
///
/// The class is not thread-safe.

public abstract class BufferedInput implements ReadableByteChannel {
    /// The default staging-buffer capacity in bytes.
    private static final int DEFAULT_BUFFER_SIZE = 8192;

    /// The little-endian staging buffer containing unread source bytes.
    protected final ByteBuffer buffer;
    /// Whether this input has been closed.
    protected boolean closed = false;

    /// Creates an input with the default heap-backed staging buffer.
    protected BufferedInput() {
        this(DEFAULT_BUFFER_SIZE, false);
    }

    /// Creates an input with a staging buffer of the given size and kind.
    ///
    /// @param bufferSize the staging buffer size in bytes; must be positive
    /// @param direct whether the staging buffer should be a direct `ByteBuffer`
    protected BufferedInput(int bufferSize, boolean direct) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize <= 0: " + bufferSize);
        }

        this.buffer = direct ? ByteBuffer.allocateDirect(bufferSize) : ByteBuffer.allocate(bufferSize);
        this.buffer.order(ByteOrder.LITTLE_ENDIAN);
        this.buffer.limit(0);
    }

    /// Creates an input that reads directly from an existing little-endian buffer.
    ///
    /// @param buffer the backing buffer, already configured for little-endian reads
    protected BufferedInput(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.order() != ByteOrder.LITTLE_ENDIAN) {
            throw new IllegalArgumentException("buffer order must be little-endian");
        }

        this.buffer = buffer;
    }

    /// Fails when the input has already been closed.
    ///
    /// @throws IOException if this input is closed
    protected final void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("BufferedInput is closed");
        }
    }

    /// Ensures that the staging buffer exposes at least `required` unread bytes.
    ///
    /// @param required the minimum unread byte count
    /// @throws IOException if the underlying source is truncated, closed, or unreadable
    public final void ensureBufferRemaining(int required) throws IOException {
        ensureOpen();
        if (required < 0) {
            throw new IllegalArgumentException("required < 0: " + required);
        }

        if (buffer.remaining() < required) {
            fillBuffer(required);

            if (buffer.remaining() < required) {
                throw unexpectedEndOfInput();
            }
        }
    }

    /// Refills the internal buffer until at least `required` unread bytes remain.
    ///
    /// Implementations may assume that `required` is non-negative and larger than the current
    /// unread byte count. Stream-backed implementations should preserve existing unread bytes.
    ///
    /// @param required the minimum unread byte count needed by the caller
    /// @throws IOException if the underlying source is truncated, closed, or unreadable
    protected abstract void fillBuffer(int required) throws IOException;

    /// Compacts the staging buffer and returns it in write mode for a refill.
    ///
    /// @param required the required unread byte count after filling
    /// @return the staging buffer in write mode
    /// @throws IOException if this input is closed
    protected ByteBuffer prepareForFill(int required) throws IOException {
        ensureOpen();
        ByteBuffer buffer = this.buffer;
        if (required > buffer.capacity()) {
            throw new IllegalArgumentException(
                    "required exceeds internal buffer capacity: " + required + " > " + buffer.capacity()
            );
        }
        buffer.compact();
        return buffer;
    }

    /// Skips up to the requested number of currently buffered bytes.
    ///
    /// @param len the maximum number of buffered bytes to skip
    /// @return the number of bytes skipped
    protected final long skipBufferedBytes(long len) {
        int skipped = (int) Math.min(len, buffer.remaining());
        buffer.position(buffer.position() + skipped);
        return skipped;
    }

    /// Discards every byte currently retained in the staging buffer.
    protected final void clearBuffer() {
        buffer.position(0);
        buffer.limit(0);
    }

    private static EOFException unexpectedEndOfInput() {
        return new EOFException("Unexpected end of input");
    }

    private static void skipFully(InputStream input, long length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }

            if (input.read() < 0) {
                throw unexpectedEndOfInput();
            }
            remaining--;
        }
    }

    /// Reads a fixed number of bytes into a newly allocated array.
    ///
    /// @param len the number of bytes to read
    /// @return a newly allocated byte array
    /// @throws IOException if the source is truncated, closed, or unreadable
    public byte[] readByteArray(int len) throws IOException {
        if (len < 0 || len >= Integer.MAX_VALUE - 8) {
            throw new IOException("Array length too large: " + len);
        }

        if (len == 0) {
            return new byte[0];
        }

        ensureOpen();

        byte[] result = new byte[len];
        int offset = 0;
        while (offset < len) {
            if (!buffer.hasRemaining()) {
                int request = buffer.capacity() == 0 ? 1 : Math.min(len - offset, buffer.capacity());
                ensureBufferRemaining(request);
            }

            int chunk = Math.min(buffer.remaining(), len - offset);
            buffer.get(result, offset, chunk);
            offset += chunk;
        }
        return result;
    }

    /// Skips exactly `len` bytes.
    ///
    /// Unlike `InputStream.skip(long)`, this method guarantees that either all requested bytes
    /// are discarded or an exception is thrown.
    ///
    /// @param len the number of bytes to skip
    /// @throws IOException if the source is truncated, closed, or unreadable
    public void skip(long len) throws IOException {
        if (len < 0) {
            throw new IllegalArgumentException("len < 0: " + len);
        }
        if (len == 0) {
            ensureOpen();
            return;
        }

        ensureOpen();

        long remaining = len - skipBufferedBytes(len);
        while (remaining > 0) {
            if (!buffer.hasRemaining()) {
                int request = buffer.capacity() == 0 ? 1 : (int) Math.min(remaining, buffer.capacity());
                ensureBufferRemaining(request);
            }

            remaining -= skipBufferedBytes(remaining);
        }
    }

    /// Reads a signed byte.
    ///
    /// @return the next byte
    /// @throws IOException if the source is truncated, closed, or unreadable
    public byte readByte() throws IOException {
        ensureBufferRemaining(Byte.BYTES);
        return buffer.get();
    }

    /// Reads an unsigned byte.
    ///
    /// @return the next byte widened to `int`
    /// @throws IOException if the source is truncated, closed, or unreadable
    public int readUnsignedByte() throws IOException {
        return Byte.toUnsignedInt(readByte());
    }

    /// Reads a signed 16-bit little-endian integer.
    ///
    /// @return the next short value
    /// @throws IOException if the source is truncated, closed, or unreadable
    public short readShortLE() throws IOException {
        ensureBufferRemaining(Short.BYTES);
        return buffer.getShort();
    }

    /// Reads an unsigned 16-bit little-endian integer.
    ///
    /// @return the next unsigned short widened to `int`
    /// @throws IOException if the source is truncated, closed, or unreadable
    public int readUnsignedShortLE() throws IOException {
        return Short.toUnsignedInt(readShortLE());
    }

    /// Reads an unsigned 24-bit little-endian integer.
    ///
    /// @return the next unsigned 24-bit value widened to `int`
    /// @throws IOException if the source is truncated, closed, or unreadable
    public int readUnsignedInt24LE() throws IOException {
        ensureBufferRemaining(3);
        return Byte.toUnsignedInt(buffer.get())
                | (Byte.toUnsignedInt(buffer.get()) << 8)
                | (Byte.toUnsignedInt(buffer.get()) << 16);
    }

    /// Reads a signed 32-bit little-endian integer.
    ///
    /// @return the next int value
    /// @throws IOException if the source is truncated, closed, or unreadable
    public int readIntLE() throws IOException {
        ensureBufferRemaining(Integer.BYTES);
        return buffer.getInt();
    }

    /// Reads an unsigned 32-bit little-endian integer.
    ///
    /// @return the next unsigned int widened to `long`
    /// @throws IOException if the source is truncated, closed, or unreadable
    public long readUnsignedIntLE() throws IOException {
        return Integer.toUnsignedLong(readIntLE());
    }

    /// Reads a signed 64-bit little-endian integer.
    ///
    /// @return the next long value
    /// @throws IOException if the source is truncated, closed, or unreadable
    public long readLongLE() throws IOException {
        ensureBufferRemaining(Long.BYTES);
        return buffer.getLong();
    }

    /// Returns the unread byte count in the current externally framed input unit, when known.
    ///
    /// A non-negative result is an exact snapshot. `-1` indicates that the backing source does
    /// not expose an external boundary for the current unit.
    ///
    /// @return the unread byte count in the current unit, or `-1` when unknown
    /// @throws IOException if the input is closed or its boundary cannot be queried
    public long currentUnitRemaining() throws IOException {
        ensureOpen();
        return -1L;
    }

    /// Reads available bytes into a destination buffer.
    ///
    /// The method returns after transferring the currently buffered bytes and does not attempt to
    /// fill the destination completely. A zero-length destination produces zero without advancing
    /// the input.
    ///
    /// @param destination the destination buffer
    /// @return the number of bytes read, zero when the destination has no remaining space, or `-1`
    ///         at end-of-input
    /// @throws IOException if the input is closed or its backing source is unreadable
    @Override
    public final int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (!destination.hasRemaining()) {
            return 0;
        }
        if (!buffer.hasRemaining()) {
            try {
                ensureBufferRemaining(1);
            } catch (EOFException ignored) {
                return -1;
            }
        }

        int count = Math.min(destination.remaining(), buffer.remaining());
        int originalLimit = buffer.limit();
        buffer.limit(buffer.position() + count);
        try {
            destination.put(buffer);
        } finally {
            buffer.limit(originalLimit);
        }
        return count;
    }

    /// Returns whether this input remains open.
    ///
    /// @return `true` until [#close()] is called
    @Override
    public final boolean isOpen() {
        return !closed;
    }

    /// `BufferedInput` backed by an `InputStream`.
    ///
    /// The implementation uses a heap buffer so bytes can be read directly into the buffer's
    /// backing array without an extra copy.
    public static final class OfInputStream extends BufferedInput {
        private final InputStream input;

        /// Creates a buffered view of the supplied stream.
        ///
        /// @param input the stream to read from
        public OfInputStream(InputStream input) {
            super();
            this.input = Objects.requireNonNull(input, "input");
        }

        @Override
        protected void fillBuffer(int required) throws IOException {
            ByteBuffer buffer1 = prepareForFill(required);
            if (!buffer1.hasArray()) {
                throw new IllegalStateException("InputStream refill requires an array-backed buffer");
            }

            byte[] array = buffer1.array();
            int arrayOffset = buffer1.arrayOffset();

            try {
                while (buffer1.position() < required) {
                    int read = input.read(array, arrayOffset + buffer1.position(), buffer1.remaining());
                    if (read < 0) {
                        throw unexpectedEndOfInput();
                    }

                    if (read == 0) {
                        int value = input.read();
                        if (value < 0) {
                            throw unexpectedEndOfInput();
                        }
                        array[arrayOffset + buffer1.position()] = (byte) value;
                        buffer1.position(buffer1.position() + 1);
                        continue;
                    }

                    buffer1.position(buffer1.position() + read);
                }
            } finally {
                buffer1.flip();
            }
        }

        @Override
        public void skip(long len) throws IOException {
            if (len < 0) {
                throw new IllegalArgumentException("len < 0: " + len);
            }
            if (len == 0) {
                ensureOpen();
                return;
            }

            ensureOpen();

            long remaining = len - skipBufferedBytes(len);
            if (remaining == 0) {
                return;
            }

            clearBuffer();
            skipFully(input, remaining);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            input.close();
        }
    }

    /// `BufferedInput` backed by a `ReadableByteChannel`.
    ///
    /// The implementation uses a direct buffer so channel reads can write into native memory
    /// without a temporary heap array.
    public static final class OfByteChannel extends BufferedInput {
        private final ReadableByteChannel channel;

        /// Creates a buffered view of the supplied channel.
        ///
        /// @param channel the channel to read from
        /// @throws IllegalArgumentException if `channel` is selectable and configured as
        ///                                  non-blocking
        public OfByteChannel(ReadableByteChannel channel) {
            super(DEFAULT_BUFFER_SIZE, true);
            ReadableByteChannel checkedChannel = Objects.requireNonNull(channel, "channel");
            if (checkedChannel instanceof SelectableChannel selectableChannel
                    && !selectableChannel.isBlocking()) {
                throw new IllegalArgumentException("channel must be in blocking mode");
            }
            this.channel = checkedChannel;
        }

        @Override
        protected void fillBuffer(int required) throws IOException {
            ByteBuffer buffer1 = prepareForFill(required);

            try {
                while (buffer1.position() < required) {
                    int read = channel.read(buffer1);
                    if (read < 0) {
                        throw unexpectedEndOfInput();
                    }
                    if (read == 0) {
                        throw new IOException("ReadableByteChannel made no progress while filling the buffer");
                    }
                }
            } finally {
                buffer1.flip();
            }
        }

        @Override
        public long currentUnitRemaining() throws IOException {
            ensureOpen();
            if (!(channel instanceof SeekableByteChannel seekableChannel)) {
                return -1L;
            }

            long remaining = seekableChannel.size() - seekableChannel.position();
            return buffer.remaining() + Math.max(remaining, 0L);
        }

        @Override
        public void skip(long len) throws IOException {
            if (len < 0) {
                throw new IllegalArgumentException("len < 0: " + len);
            }
            if (len == 0) {
                ensureOpen();
                return;
            }

            ensureOpen();

            long remaining = len - skipBufferedBytes(len);
            if (remaining == 0) {
                return;
            }

            if (channel instanceof SeekableByteChannel seekableChannel) {
                long position = seekableChannel.position();
                long size = seekableChannel.size();
                long available = size - position;
                if (available < 0 || remaining > available) {
                    throw unexpectedEndOfInput();
                }

                clearBuffer();
                seekableChannel.position(position + remaining);
                return;
            }

            ByteBuffer discardBuffer = buffer;
            discardBuffer.clear();
            try {
                while (remaining > 0) {
                    discardBuffer.limit((int) Math.min(remaining, discardBuffer.capacity()));

                    int read = channel.read(discardBuffer);
                    if (read < 0) {
                        throw unexpectedEndOfInput();
                    }
                    if (read == 0) {
                        throw new IOException("ReadableByteChannel made no progress while skipping bytes");
                    }

                    remaining -= read;
                    discardBuffer.clear();
                }
            } finally {
                clearBuffer();
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            channel.close();
        }
    }

    /// `BufferedInput` backed directly by an existing `ByteBuffer`.
    ///
    /// The wrapper reads from a little-endian slice of the supplied buffer, so consuming bytes
    /// does not mutate the caller's `position()` or `limit()`.
    public static final class OfByteBuffer extends BufferedInput {
        /// Creates a buffered view of the supplied byte buffer.
        ///
        /// @param buffer the buffer to read from starting at its current position
        public OfByteBuffer(ByteBuffer buffer) {
            super(Objects.requireNonNull(buffer, "buffer").slice().order(ByteOrder.LITTLE_ENDIAN));
        }

        @Override
        protected void fillBuffer(int required) throws IOException {
            throw unexpectedEndOfInput();
        }

        @Override
        public long currentUnitRemaining() throws IOException {
            ensureOpen();
            return buffer.remaining();
        }

        @Override
        public void skip(long len) throws IOException {
            if (len < 0) {
                throw new IllegalArgumentException("len < 0: " + len);
            }
            if (len == 0) {
                ensureOpen();
                return;
            }

            ensureOpen();

            if (len > buffer.remaining()) {
                throw unexpectedEndOfInput();
            }

            buffer.position(buffer.position() + Math.toIntExact(len));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /// `BufferedInput` backed by a sequence of existing `ByteBuffer` instances.
    ///
    /// The wrapper copies only into its reusable staging buffer as scalar reads require data.
    /// It does not concatenate the source buffers into a separate byte array or byte buffer.
    /// Each supplied buffer remains an externally framed unit for [#currentUnitRemaining()].
    public static final class OfByteBuffers extends BufferedInput {
        /// Source buffers read in order.
        private final ByteBuffer [] sources;
        /// Exclusive logical end offset of each externally framed source buffer.
        private final long [] sourceEndOffsets;
        /// Total logical byte length of all source buffers.
        private final long totalSize;
        /// Index of the source buffer currently being consumed.
        private int sourceIndex;

        /// Creates a buffered view of the supplied byte buffers.
        ///
        /// @param buffers the buffers to read in order, starting at each buffer's current position
        public OfByteBuffers(ByteBuffer [] buffers) {
            super();
            Objects.requireNonNull(buffers, "buffers");
            this.sources = new ByteBuffer[buffers.length];
            this.sourceEndOffsets = new long[buffers.length];
            long endOffset = 0L;
            for (int i = 0; i < buffers.length; i++) {
                this.sources[i] = Objects.requireNonNull(buffers[i], "buffers[" + i + "]")
                        .slice()
                        .order(ByteOrder.LITTLE_ENDIAN);
                endOffset += this.sources[i].remaining();
                sourceEndOffsets[i] = endOffset;
            }
            this.totalSize = endOffset;
        }

        @Override
        protected void fillBuffer(int required) throws IOException {
            ByteBuffer target = prepareForFill(required);
            try {
                while (target.position() < required && sourceIndex < sources.length) {
                    ByteBuffer source = sources[sourceIndex];
                    if (!source.hasRemaining()) {
                        sourceIndex++;
                        continue;
                    }

                    int chunk = Math.min(target.remaining(), source.remaining());
                    int sourceLimit = source.limit();
                    source.limit(source.position() + chunk);
                    try {
                        target.put(source);
                    } finally {
                        source.limit(sourceLimit);
                    }
                }
            } finally {
                target.flip();
            }
        }

        @Override
        public long currentUnitRemaining() throws IOException {
            ensureOpen();

            long unreadBytes = buffer.remaining();
            if (sourceIndex < sources.length) {
                unreadBytes += sources[sourceIndex].remaining();
                unreadBytes += totalSize - sourceEndOffsets[sourceIndex];
            }
            long logicalOffset = totalSize - unreadBytes;
            int unitIndex = Arrays.binarySearch(sourceEndOffsets, logicalOffset + 1L);
            if (unitIndex < 0) {
                unitIndex = -unitIndex - 1;
            }
            return unitIndex < sourceEndOffsets.length
                    ? sourceEndOffsets[unitIndex] - logicalOffset
                    : 0L;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
