// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;

import pzopt.vendor.avif.internal.io.BufferedInput;
import pzopt.vendor.avif.internal.io.AvifDataSource;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Describes one logical AV1 payload as one or more ranges of retained source data.
///
/// A payload does not own or close its source. Its views remain readable only while the owning
/// [pzopt.vendor.avif.AvifImageReader] remains open.

public final class AvifPayload {
    /// The largest payload that the byte-array based AV1 packet layer can consume.
    private static final int MAX_PAYLOAD_SIZE = Integer.MAX_VALUE - 8;

    /// The source containing every extent.
    private final AvifDataSource source;
    /// The absolute source offset of each extent.
    private final long [] offsets;
    /// The byte length of each extent.
    private final int [] lengths;
    /// The total logical payload length.
    private final int length;

    /// Creates one payload from validated source ranges.
    ///
    /// @param source the source containing every range
    /// @param offsets the absolute source offsets
    /// @param lengths the source-range lengths
    private AvifPayload(
            AvifDataSource source,
            long [] offsets,
            int [] lengths
    ) {
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(offsets, "offsets");
        Objects.requireNonNull(lengths, "lengths");
        if (offsets.length == 0 || offsets.length != lengths.length) {
            throw new IllegalArgumentException("Payload ranges must be non-empty and have matching arrays");
        }

        long totalLength = 0L;
        for (int i = 0; i < offsets.length; i++) {
            long offset = offsets[i];
            int rangeLength = lengths[i];
            if (offset < 0 || rangeLength < 0 || offset > source.limit() || rangeLength > source.limit() - offset) {
                throw new IllegalArgumentException(
                        "Payload range outside source at index " + i + ": " + offset + " + " + rangeLength
                );
            }
            totalLength += rangeLength;
            if (totalLength > MAX_PAYLOAD_SIZE) {
                throw new IllegalArgumentException("Payload exceeds supported byte-array size: " + totalLength);
            }
        }
        this.offsets = offsets.clone();
        this.lengths = lengths.clone();
        this.length = (int) totalLength;
    }

    /// Creates a payload over source ranges without copying their contents.
    ///
    /// @param source the source containing every range
    /// @param offsets the absolute source offsets
    /// @param lengths the source-range lengths
    /// @return the immutable payload descriptor
    public static AvifPayload ofRanges(
            AvifDataSource source,
            long [] offsets,
            int [] lengths
    ) {
        return new AvifPayload(source, offsets, lengths);
    }

    /// Returns the logical payload length.
    ///
    /// @return the sum of all extent lengths in bytes
    public int length() {
        return length;
    }

    /// Opens a buffered input over this payload.
    ///
    /// Closing the returned input does not close the payload's source.
    ///
    /// @return a new input positioned at the first payload byte
    public BufferedInput openInput() {
        return openInput(new AvifPayload[]{this});
    }

    /// Opens a buffered input over payloads in sequence.
    ///
    /// Each payload remains a separate externally framed input unit as reported by
    /// [BufferedInput#currentUnitRemaining()]. Closing the returned input does not close any
    /// payload source.
    ///
    /// @param payloads the payloads to read in order
    /// @return a new input positioned at the first byte of the first payload
    public static BufferedInput openInput(AvifPayload [] payloads) {
        return new PayloadInput(payloads);
    }

    /// Copies this payload into a newly allocated byte array.
    ///
    /// @return the complete logical payload bytes
    /// @throws IOException if the backing source cannot be read
    public byte[] readBytes() throws IOException {
        byte[] result = new byte[length];
        int destinationOffset = 0;
        for (int i = 0; i < offsets.length; i++) {
            int rangeLength = lengths[i];
            source.readFully(offsets[i], ByteBuffer.wrap(result, destinationOffset, rangeLength));
            destinationOffset += rangeLength;
        }
        return result;
    }

    /// Buffered logical concatenation of payload ranges.
    private static final class PayloadInput extends BufferedInput {
        /// The source corresponding to each flattened extent.
        private final AvifDataSource [] sources;
        /// The absolute source offset of each flattened extent.
        private final long [] sourceOffsets;
        /// The logical exclusive end offset of each flattened extent.
        private final long [] extentEndOffsets;
        /// The logical exclusive end offset of each payload unit.
        private final long [] payloadEndOffsets;
        /// The total logical input size.
        private final long totalSize;

        /// The flattened extent currently being loaded.
        private int extentIndex;
        /// The byte count already loaded from the current extent.
        private int extentPosition;
        /// The logical position immediately after all bytes loaded into the staging buffer.
        private long loadedPosition;

        /// Creates an input over the supplied payload units.
        ///
        /// @param payloads the payloads to concatenate logically
        private PayloadInput(AvifPayload [] payloads) {
            Objects.requireNonNull(payloads, "payloads");
            int extentCount = 0;
            for (int i = 0; i < payloads.length; i++) {
                extentCount = Math.addExact(
                        extentCount,
                        Objects.requireNonNull(payloads[i], "payloads[" + i + "]").offsets.length
                );
            }
            this.sources = new AvifDataSource[extentCount];
            this.sourceOffsets = new long[extentCount];
            this.extentEndOffsets = new long[extentCount];
            this.payloadEndOffsets = new long[payloads.length];

            int flattenedIndex = 0;
            long logicalOffset = 0L;
            for (int payloadIndex = 0; payloadIndex < payloads.length; payloadIndex++) {
                AvifPayload payload = payloads[payloadIndex];
                for (int rangeIndex = 0; rangeIndex < payload.offsets.length; rangeIndex++) {
                    sources[flattenedIndex] = payload.source;
                    sourceOffsets[flattenedIndex] = payload.offsets[rangeIndex];
                    logicalOffset += payload.lengths[rangeIndex];
                    extentEndOffsets[flattenedIndex] = logicalOffset;
                    flattenedIndex++;
                }
                payloadEndOffsets[payloadIndex] = logicalOffset;
            }
            this.totalSize = logicalOffset;
        }

        /// Fills the staging buffer from the current logical extent position.
        ///
        /// @param required the minimum unread byte count needed by the caller
        /// @throws IOException if a payload source cannot be read
        @Override
        protected void fillBuffer(int required) throws IOException {
            ByteBuffer target = prepareForFill(required);
            try {
                while (target.hasRemaining() && extentIndex < sources.length) {
                    long extentStart = extentIndex == 0 ? 0L : extentEndOffsets[extentIndex - 1];
                    int extentLength = Math.toIntExact(extentEndOffsets[extentIndex] - extentStart);
                    int available = extentLength - extentPosition;
                    if (available == 0) {
                        extentIndex++;
                        extentPosition = 0;
                        continue;
                    }

                    int chunk = Math.min(target.remaining(), available);
                    int originalLimit = target.limit();
                    target.limit(target.position() + chunk);
                    try {
                        sources[extentIndex].readFully(
                                sourceOffsets[extentIndex] + extentPosition,
                                target
                        );
                    } finally {
                        target.limit(originalLimit);
                    }
                    extentPosition += chunk;
                    loadedPosition += chunk;
                }
            } finally {
                target.flip();
            }
        }

        /// Returns the unread byte count in the current payload unit.
        ///
        /// @return the exact unread byte count in the current payload, or zero at end-of-input
        /// @throws IOException if this input is closed
        @Override
        public long currentUnitRemaining() throws IOException {
            ensureOpen();
            long consumedPosition = loadedPosition - buffer.remaining();
            int payloadIndex = Arrays.binarySearch(payloadEndOffsets, consumedPosition + 1L);
            if (payloadIndex < 0) {
                payloadIndex = -payloadIndex - 1;
            }
            return payloadIndex < payloadEndOffsets.length
                    ? payloadEndOffsets[payloadIndex] - consumedPosition
                    : 0L;
        }

        /// Skips bytes by repositioning the logical extent cursor.
        ///
        /// @param len the number of bytes to skip
        /// @throws IOException if this input is closed or fewer than `len` bytes remain
        @Override
        public void skip(long len) throws IOException {
            if (len < 0) {
                throw new IllegalArgumentException("len < 0: " + len);
            }
            ensureOpen();
            if (len == 0) {
                return;
            }
            long consumedPosition = loadedPosition - buffer.remaining();
            if (len > totalSize - consumedPosition) {
                throw new EOFException("Unexpected end of input");
            }
            setLogicalPosition(consumedPosition + len);
        }

        /// Repositions this input to one logical payload offset and clears buffered data.
        ///
        /// @param position the logical offset in the concatenated payloads
        private void setLogicalPosition(long position) {
            clearBuffer();
            loadedPosition = position;
            if (position == totalSize) {
                extentIndex = sources.length;
                extentPosition = 0;
                return;
            }
            int index = Arrays.binarySearch(extentEndOffsets, position + 1L);
            if (index < 0) {
                index = -index - 1;
            }
            extentIndex = index;
            long extentStart = index == 0 ? 0L : extentEndOffsets[index - 1];
            extentPosition = Math.toIntExact(position - extentStart);
        }

        /// Closes this logical view without closing shared payload sources.
        @Override
        public void close() {
            closed = true;
        }
    }
}
