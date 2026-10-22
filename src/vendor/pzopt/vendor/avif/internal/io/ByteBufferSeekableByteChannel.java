// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.io;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Provides a read-only seekable channel over a borrowed byte-buffer region.
///
/// The channel captures the supplied buffer's remaining region without copying its contents and
/// maintains an independent channel position. Closing the channel does not affect the supplied
/// buffer. Instances are not thread-safe.

final class ByteBufferSeekableByteChannel implements SeekableByteChannel {
    /// The borrowed region exposed through an internal read-only view.
    private final ByteBuffer content;
    /// The current channel position.
    private long position;
    /// Whether the channel is open.
    private boolean open = true;

    /// Creates a channel over the supplied buffer's current position-to-limit region.
    ///
    /// @param source the buffer region to borrow
    ByteBufferSeekableByteChannel(ByteBuffer source) {
        this.content = Objects.requireNonNull(source, "source").slice().asReadOnlyBuffer();
    }

    /// Reads bytes at the current channel position and advances it by the returned count.
    ///
    /// @param destination the destination buffer
    /// @return the number of bytes read, `0` when the destination has no remaining space, or `-1`
    ///         when positioned at or beyond the end of the captured region
    /// @throws IOException if the channel is closed
    @Override
    public int read(ByteBuffer destination) throws IOException {
        ensureOpen();
        ByteBuffer checkedDestination = Objects.requireNonNull(destination, "destination");
        if (!checkedDestination.hasRemaining()) {
            return 0;
        }
        if (position >= content.capacity()) {
            return -1;
        }

        int start = (int) position;
        int length = Math.min(checkedDestination.remaining(), content.capacity() - start);
        content.limit(start + length).position(start);
        try {
            checkedDestination.put(content);
        } finally {
            content.clear();
        }
        position += length;
        return length;
    }

    /// Rejects writes because this channel is read-only.
    ///
    /// @param source the unused source buffer
    /// @return this method does not return normally
    /// @throws IOException if the channel is closed
    /// @throws NonWritableChannelException always when the channel is open
    @Override
    public int write(ByteBuffer source) throws IOException {
        ensureOpen();
        Objects.requireNonNull(source, "source");
        throw new NonWritableChannelException();
    }

    /// Returns the current channel position.
    ///
    /// @return the non-negative channel position
    /// @throws IOException if the channel is closed
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Sets the channel position without changing the captured region.
    ///
    /// Positions beyond the end are permitted and cause subsequent reads to report end-of-input.
    ///
    /// @param newPosition the new non-negative channel position
    /// @return this channel
    /// @throws IllegalArgumentException if `newPosition` is negative
    /// @throws IOException if the channel is closed
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition < 0: " + newPosition);
        }
        this.position = newPosition;
        return this;
    }

    /// Returns the fixed size of the captured region.
    ///
    /// @return the captured region size in bytes
    /// @throws IOException if the channel is closed
    @Override
    public long size() throws IOException {
        ensureOpen();
        return content.capacity();
    }

    /// Rejects truncation because this channel is read-only.
    ///
    /// @param size the unused requested size
    /// @return this method does not return normally
    /// @throws IllegalArgumentException if `size` is negative
    /// @throws IOException if the channel is closed
    /// @throws NonWritableChannelException always when the channel is open
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0) {
            throw new IllegalArgumentException("size < 0: " + size);
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether this channel remains open.
    ///
    /// @return whether [#close()] has not been called
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel without affecting the borrowed buffer.
    @Override
    public void close() {
        open = false;
    }

    /// Ensures that this channel is open.
    ///
    /// @throws ClosedChannelException if the channel is closed
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
