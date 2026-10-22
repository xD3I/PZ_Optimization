// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.io;

import pzopt.vendor.avif.AvifDecodeException;
import pzopt.vendor.avif.AvifErrorCode;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/// Provides bounded positional reads over seekable or progressive AVIF input.
///
/// Seekable instances own their channel and expose its exact size. Progressive instances borrow a
/// readable channel, retain a bounded recent window, and expose the maximum permitted source
/// position as their limit. Instances are not thread-safe.

public abstract sealed class AvifDataSource implements Closeable {
    /// The byte count retained for scalar seekable reads and progressive reads.
    private static final int READ_CACHE_SIZE = 64 * 1024;

    /// Creates a data source.
    AvifDataSource() {
    }

    /// Creates a seekable source that borrows a byte array without copying it.
    ///
    /// The caller must not modify the array while the returned source is in use.
    ///
    /// @param bytes the bytes to retain
    /// @return the seekable memory source
    public static AvifDataSource ofBytes(byte[] bytes) {
        byte[] checkedBytes = Objects.requireNonNull(bytes, "bytes");
        return new SeekableSource(
                new ByteBufferSeekableByteChannel(ByteBuffer.wrap(checkedBytes)),
                checkedBytes.length,
                1
        );
    }

    /// Creates a seekable source that borrows a byte buffer's remaining region without copying it.
    ///
    /// The captured region starts at the buffer's current position and ends at its current limit.
    /// The source has an independent channel position and does not change the supplied buffer's
    /// position or limit. The caller must not modify the region through any alias while the source
    /// is in use.
    ///
    /// @param buffer the buffer whose remaining region is retained
    /// @return the seekable memory source
    public static AvifDataSource ofByteBuffer(ByteBuffer buffer) {
        ByteBuffer checkedBuffer = Objects.requireNonNull(buffer, "buffer");
        return new SeekableSource(
                new ByteBufferSeekableByteChannel(checkedBuffer),
                checkedBuffer.remaining(),
                1
        );
    }

    /// Opens a persistent file as an owned seekable source.
    ///
    /// @param path the file to open
    /// @return the seekable file source
    /// @throws IOException if the file cannot be opened or its size cannot be queried
    public static AvifDataSource open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(Objects.requireNonNull(path, "path"), StandardOpenOption.READ);
        try {
            return new SeekableSource(channel, channel.size(), READ_CACHE_SIZE);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                channel.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Creates a progressive source that borrows a readable channel.
    ///
    /// The source never closes the channel. Attempts to revisit bytes outside its retained window
    /// fail instead of buffering the complete input or using a temporary file.
    ///
    /// @param channel the readable channel to borrow
    /// @param maximumPosition the maximum permitted exclusive source position
    /// @return the progressive source
    /// @throws IllegalArgumentException if `maximumPosition` is not positive or `channel` is
    ///                                  selectable and configured as non-blocking
    public static AvifDataSource progressive(ReadableByteChannel channel, long maximumPosition) {
        ReadableByteChannel checkedChannel = Objects.requireNonNull(channel, "channel");
        if (checkedChannel instanceof SelectableChannel selectableChannel
                && !selectableChannel.isBlocking()) {
            throw new IllegalArgumentException("channel must be in blocking mode");
        }
        return new ProgressiveSource(checkedChannel, maximumPosition);
    }

    /// Returns the exclusive source-position limit.
    ///
    /// This is the exact size for seekable input and the configured maximum position for
    /// progressive input.
    ///
    /// @return the exclusive source-position limit in bytes
    public abstract long limit();

    /// Returns whether arbitrary source positions can be revisited.
    ///
    /// @return whether this source is seekable
    public abstract boolean isSeekable();

    /// Reads one byte at an absolute source position.
    ///
    /// @param position the zero-based absolute byte position
    /// @return the byte at the supplied position
    /// @throws IOException if the source is closed, cannot supply the position, or cannot be read
    public abstract byte readByte(long position) throws IOException;

    /// Reads a complete absolute source range into a destination buffer.
    ///
    /// The destination's position advances by its original remaining byte count. Its limit is not
    /// changed.
    ///
    /// @param position the zero-based absolute source position
    /// @param destination the destination buffer
    /// @throws IOException if the source is closed, cannot supply the range, or cannot be read
    public abstract void readFully(long position, ByteBuffer destination) throws IOException;

    /// Reads one source range into a newly allocated array.
    ///
    /// @param position the zero-based absolute source position
    /// @param length the requested byte count
    /// @return the copied source bytes
    /// @throws IOException if the source cannot supply the complete range
    public final byte[] readBytes(long position, int length) throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("length < 0: " + length);
        }
        byte[] result = new byte[length];
        readFully(position, ByteBuffer.wrap(result));
        return result;
    }

    /// Creates a failure indicating that discarded progressive input is required again.
    ///
    /// @param position the requested absolute source position
    /// @return the seekability failure
    private static AvifDecodeException seekableSourceRequired(long position) {
        return new AvifDecodeException(
                AvifErrorCode.SEEKABLE_SOURCE_REQUIRED,
                "AVIF input requires backward access at byte offset " + position
                        + "; use Path, byte[], or ByteBuffer input",
                position
        );
    }

    /// Implements positional reads over an owned seekable channel.
    
    private static final class SeekableSource extends AvifDataSource {
        /// The owned seekable channel.
        private final SeekableByteChannel channel;
        /// The exact fixed channel size.
        private final long size;
        /// The reusable scalar-read cache.
        private final byte[] cache;
        /// A reusable channel destination over [#cache].
        private final ByteBuffer cacheBuffer;
        /// The absolute source position corresponding to the first cached byte.
        private long cacheOffset;
        /// The number of valid cached bytes.
        private int cacheLength;

        /// Creates a source over an owned seekable channel with a known size.
        ///
        /// @param channel the owned channel
        /// @param size the exact channel size in bytes
        /// @param maximumCacheSize the positive maximum scalar-read cache size
        private SeekableSource(SeekableByteChannel channel, long size, int maximumCacheSize) {
            if (size < 0) {
                throw new IllegalArgumentException("size < 0: " + size);
            }
            if (maximumCacheSize <= 0) {
                throw new IllegalArgumentException("maximumCacheSize <= 0: " + maximumCacheSize);
            }
            this.channel = Objects.requireNonNull(channel, "channel");
            this.size = size;
            this.cache = new byte[(int) Math.min(maximumCacheSize, Math.max(1L, size))];
            this.cacheBuffer = ByteBuffer.wrap(cache);
        }

        /// Returns the exact channel size.
        ///
        /// @return the channel size in bytes
        @Override
        public long limit() {
            return size;
        }

        /// Returns that arbitrary positions can be revisited.
        ///
        /// @return `true`
        @Override
        public boolean isSeekable() {
            return true;
        }

        /// Reads one byte, using the reusable cache for scalar access.
        ///
        /// @param position the absolute source position
        /// @return the byte at the supplied position
        /// @throws IOException if the source is closed, outside the channel, or unreadable
        @Override
        public byte readByte(long position) throws IOException {
            ensureRange(position, 1L);
            if (position < cacheOffset || position >= cacheOffset + cacheLength) {
                refillCache(position);
            }
            return cache[(int) (position - cacheOffset)];
        }

        /// Reads a complete source range from the seekable channel.
        ///
        /// @param position the absolute source position
        /// @param destination the destination buffer
        /// @throws IOException if the source is closed, outside the channel, or unreadable
        @Override
        public void readFully(long position, ByteBuffer destination) throws IOException {
            ByteBuffer checkedDestination = Objects.requireNonNull(destination, "destination");
            int length = checkedDestination.remaining();
            ensureRange(position, length);
            if (length == 0) {
                return;
            }
            if (position >= cacheOffset && position + length <= cacheOffset + cacheLength) {
                checkedDestination.put(cache, (int) (position - cacheOffset), length);
                return;
            }

            channel.position(position);
            while (checkedDestination.hasRemaining()) {
                int read = channel.read(checkedDestination);
                if (read < 0) {
                    throw new EOFException("Unexpected end of seekable AVIF input");
                }
                if (read == 0) {
                    throw new IOException("SeekableByteChannel made no progress while reading AVIF input");
                }
            }
        }

        /// Closes the owned channel.
        ///
        /// @throws IOException if the channel cannot be closed
        @Override
        public void close() throws IOException {
            channel.close();
        }

        /// Refills the scalar-read cache at an absolute source position.
        ///
        /// @param position the first position to cache
        /// @throws IOException if the channel cannot be read
        private void refillCache(long position) throws IOException {
            int requested = (int) Math.min(cache.length, size - position);
            cacheBuffer.clear().limit(requested);
            channel.position(position);
            while (cacheBuffer.hasRemaining()) {
                int read = channel.read(cacheBuffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    throw new IOException("SeekableByteChannel made no progress while caching AVIF input");
                }
            }
            cacheOffset = position;
            cacheLength = cacheBuffer.position();
            if (cacheLength == 0) {
                throw new EOFException("Unexpected end of seekable AVIF input");
            }
        }

        /// Validates an absolute range and the channel's open state.
        ///
        /// @param position the absolute source position
        /// @param length the requested byte count
        /// @throws IOException if the channel is closed or the range is outside the source
        private void ensureRange(long position, long length) throws IOException {
            if (!channel.isOpen()) {
                throw new IOException("AVIF data source is closed");
            }
            if (position < 0 || length < 0 || position > size || length > size - position) {
                throw new EOFException("Source range outside seekable input: " + position + " + " + length);
            }
        }
    }

    /// Implements bounded positional reads over a borrowed forward-only channel.
    
    private static final class ProgressiveSource extends AvifDataSource {
        /// The borrowed readable channel.
        private final ReadableByteChannel channel;
        /// The maximum permitted exclusive source position.
        private final long maximumPosition;
        /// The bounded recent-read window.
        private final byte[] cache = new byte[READ_CACHE_SIZE];
        /// A reusable channel destination over [#cache].
        private final ByteBuffer cacheBuffer = ByteBuffer.wrap(cache);
        /// The absolute source position corresponding to the first cached byte.
        private long cacheOffset;
        /// The number of valid cached bytes.
        private int cacheLength;
        /// The absolute position of the next unread channel byte.
        private long channelPosition;
        /// Whether this source has been closed.
        private boolean closed;

        /// Creates a progressive source over a borrowed channel.
        ///
        /// @param channel the channel to borrow without closing
        /// @param maximumPosition the maximum permitted exclusive source position
        private ProgressiveSource(ReadableByteChannel channel, long maximumPosition) {
            if (maximumPosition <= 0) {
                throw new IllegalArgumentException("maximumPosition <= 0: " + maximumPosition);
            }
            this.channel = Objects.requireNonNull(channel, "channel");
            this.maximumPosition = maximumPosition;
        }

        /// Returns the configured maximum source position.
        ///
        /// @return the maximum exclusive source position
        @Override
        public long limit() {
            return maximumPosition;
        }

        /// Returns that discarded positions cannot be revisited.
        ///
        /// @return `false`
        @Override
        public boolean isSeekable() {
            return false;
        }

        /// Reads one byte through the bounded recent-read window.
        ///
        /// @param position the absolute source position
        /// @return the byte at the supplied position
        /// @throws IOException if the source is closed, truncated, too large, or requires seeking
        @Override
        public byte readByte(long position) throws IOException {
            ensureRange(position, 1L);
            if (position < cacheOffset || position >= cacheOffset + cacheLength) {
                refillCache(position);
            }
            return cache[(int) (position - cacheOffset)];
        }

        /// Reads a complete source range through the bounded recent-read window.
        ///
        /// @param position the absolute source position
        /// @param destination the destination buffer
        /// @throws IOException if the source is closed, truncated, too large, or requires seeking
        @Override
        public void readFully(long position, ByteBuffer destination) throws IOException {
            ByteBuffer checkedDestination = Objects.requireNonNull(destination, "destination");
            int length = checkedDestination.remaining();
            ensureRange(position, length);
            long readPosition = position;
            while (checkedDestination.hasRemaining()) {
                if (readPosition < cacheOffset || readPosition >= cacheOffset + cacheLength) {
                    refillCache(readPosition);
                }
                int cacheIndex = (int) (readPosition - cacheOffset);
                int chunk = Math.min(checkedDestination.remaining(), cacheLength - cacheIndex);
                checkedDestination.put(cache, cacheIndex, chunk);
                readPosition += chunk;
            }
        }

        /// Marks this source closed without closing the borrowed channel.
        @Override
        public void close() {
            closed = true;
        }

        /// Refills the bounded window at a forward source position.
        ///
        /// Any gap before `position` is consumed and discarded.
        ///
        /// @param position the first position to cache
        /// @throws IOException if the channel is truncated, cannot progress, or would require seeking
        private void refillCache(long position) throws IOException {
            if (position < channelPosition) {
                throw seekableSourceRequired(position);
            }
            while (channelPosition < position) {
                int chunk = (int) Math.min(cache.length, position - channelPosition);
                int read = readIntoCache(chunk, "advancing");
                cacheOffset = channelPosition;
                cacheLength = read;
                channelPosition += read;
            }

            int requested = (int) Math.min(cache.length, maximumPosition - channelPosition);
            int read = readIntoCache(requested, "reading");
            cacheOffset = channelPosition;
            cacheLength = read;
            channelPosition += read;
        }

        /// Reads at most one requested chunk into the reusable cache.
        ///
        /// @param requested the positive maximum byte count
        /// @param action the diagnostic action
        /// @return the positive byte count read
        /// @throws IOException if the channel reaches EOF or makes no progress
        private int readIntoCache(int requested, String action) throws IOException {
            cacheBuffer.clear().limit(requested);
            int read = channel.read(cacheBuffer);
            if (read < 0) {
                throw new EOFException("Unexpected end of progressive AVIF input");
            }
            if (read == 0) {
                throw new IOException("ReadableByteChannel made no progress while " + action + " AVIF input");
            }
            return read;
        }

        /// Validates an absolute range and the source's open state.
        ///
        /// @param position the absolute source position
        /// @param length the requested byte count
        /// @throws IOException if the source is closed or the range exceeds its configured limit
        private void ensureRange(long position, long length) throws IOException {
            if (closed) {
                throw new IOException("AVIF data source is closed");
            }
            if (position < 0 || length < 0) {
                throw new EOFException("Invalid progressive source range: " + position + " + " + length);
            }
            if (position > maximumPosition || length > maximumPosition - position) {
                throw new AvifDecodeException(
                        AvifErrorCode.INPUT_TOO_LARGE,
                        "AVIF input exceeds supported size limit: " + maximumPosition + " bytes",
                        position
                );
            }
        }
    }
}
