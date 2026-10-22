// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/// Fully decoded AVIF content.
///
/// This type is the eager counterpart of [AvifImageReader]. It materializes every decoded frame
/// and exposes the immutable container metadata needed to interpret still and animated images.

public final class AvifImage {
    /// The immutable container metadata.
    private final AvifImageInfo info;
    /// The decoded frames in presentation order.
    private final List<AvifFrame> frames;

    /// Reads and fully decodes AVIF bytes using the default reader factory.
    ///
    /// The array is borrowed only for the duration of this call and is not retained after the
    /// method returns. The caller must not modify it while decoding is in progress.
    ///
    /// @param source the complete AVIF source bytes
    /// @return the fully decoded image
    /// @throws IOException if the input exceeds the configured limit, is not a supported AVIF
    ///                     container, or cannot be decoded
    public static AvifImage read(byte[] source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes AVIF bytes using the supplied reader factory.
    ///
    /// The array is borrowed only for the duration of this call and is not retained after the
    /// method returns. The caller must not modify it while decoding is in progress.
    ///
    /// @param source the complete AVIF source bytes
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the input exceeds the configured limit, is not a supported AVIF
    ///                     container, or cannot be decoded
    public static AvifImage read(byte[] source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Reads and fully decodes the remaining bytes of a byte buffer using the default reader
    /// factory.
    ///
    /// The region from the source's current position to its limit is borrowed only for the
    /// duration of this call. The source's position and limit are unchanged, and the caller must
    /// not modify the region while decoding is in progress.
    ///
    /// @param source the source byte buffer
    /// @return the fully decoded image
    /// @throws IOException if the input exceeds the configured limit, is not a supported AVIF
    ///                     container, or cannot be decoded
    public static AvifImage read(ByteBuffer source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes the remaining bytes of a byte buffer using the supplied reader
    /// factory.
    ///
    /// The region from the source's current position to its limit is borrowed only for the
    /// duration of this call. The source's position and limit are unchanged, and the caller must
    /// not modify the region while decoding is in progress.
    ///
    /// @param source the source byte buffer
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the input exceeds the configured limit, is not a supported AVIF
    ///                     container, or cannot be decoded
    public static AvifImage read(ByteBuffer source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Reads and fully decodes an AVIF file using the default reader factory.
    ///
    /// The file handle is closed before this method returns. The returned image does not retain
    /// the path or any open file resource.
    ///
    /// @param source the AVIF file path
    /// @return the fully decoded image
    /// @throws IOException if the file cannot be opened, parsed, or decoded
    public static AvifImage read(Path source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes an AVIF file using the supplied reader factory.
    ///
    /// The file handle is closed before this method returns. The returned image does not retain
    /// the path or any open file resource.
    ///
    /// @param source the AVIF file path
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the file cannot be opened, parsed, or decoded
    public static AvifImage read(Path source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Reads and fully decodes an AVIF stream using the default reader factory.
    ///
    /// The stream is borrowed and remains open. Decoding consumes input progressively through the
    /// final encoded frame and may read ahead by a bounded amount; trailing top-level boxes may
    /// remain unread. If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte stream
    /// @return the fully decoded image
    /// @throws IOException if the stream cannot be read, parsed, or decoded
    public static AvifImage read(InputStream source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes an AVIF stream using the supplied reader factory.
    ///
    /// The stream is borrowed and remains open. Decoding consumes input progressively through the
    /// final encoded frame and may read ahead by a bounded amount; trailing top-level boxes may
    /// remain unread. If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte stream
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the stream cannot be read, parsed, or decoded
    public static AvifImage read(InputStream source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Reads and fully decodes an AVIF byte channel using the default reader factory.
    ///
    /// The channel is borrowed and remains open. It must use blocking mode and remain usable for
    /// the duration of this call. Decoding consumes input progressively through the final encoded
    /// frame and may read ahead by a bounded amount; trailing top-level boxes may remain unread.
    /// If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte channel
    /// @return the fully decoded image
    /// @throws IOException if the channel cannot be read, exceeds the configured limit, or does
    ///                     not contain a supported AVIF container
    /// @throws IllegalArgumentException if the channel is selectable and configured as
    ///                                  non-blocking
    public static AvifImage read(ReadableByteChannel source) throws IOException {
        return read(source, AvifImageReaderFactory.DEFAULT);
    }

    /// Reads and fully decodes an AVIF byte channel using the supplied reader factory.
    ///
    /// The channel is borrowed and remains open. It must use blocking mode and remain usable for
    /// the duration of this call. Decoding consumes input progressively through the final encoded
    /// frame and may read ahead by a bounded amount; trailing top-level boxes may remain unread.
    /// If decoding fails, bytes already consumed remain consumed.
    ///
    /// @param source the AVIF byte channel
    /// @param factory the reader factory that supplies decoding options
    /// @return the fully decoded image
    /// @throws IOException if the channel cannot be read, exceeds the configured limit, or does
    ///                     not contain a supported AVIF container
    /// @throws IllegalArgumentException if the channel is selectable and configured as
    ///                                  non-blocking
    public static AvifImage read(ReadableByteChannel source, AvifImageReaderFactory factory) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(factory, "factory");
        try (AvifImageReader reader = factory.open(source)) {
            return collect(reader);
        }
    }

    /// Creates a fully decoded image from normalized immutable state.
    ///
    /// @param info the immutable container metadata
    /// @param frames the decoded frames in presentation order
    /// @throws IllegalArgumentException if the decoded frame count differs from the metadata
    private AvifImage(AvifImageInfo info, List<AvifFrame> frames) {
        this.info = Objects.requireNonNull(info, "info");
        this.frames = List.copyOf(frames);
        if (this.frames.size() != info.frameCount()) {
            throw new IllegalArgumentException(
                    "Decoded frame count does not match image metadata: "
                            + this.frames.size() + " != " + info.frameCount()
            );
        }
    }

    /// Returns the immutable container metadata.
    ///
    /// @return the immutable container metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns all decoded frames in presentation order.
    ///
    /// @return an immutable list containing every decoded frame
    public List<AvifFrame> frames() {
        return frames;
    }

    /// Returns the first decoded frame.
    ///
    /// @return the first decoded frame
    public AvifFrame firstFrame() {
        return frames.get(0);
    }

    /// Materializes one open reader as an immutable image.
    ///
    /// Metadata is captured before frame decoding so that progressive inputs need not revisit
    /// discarded container bytes.
    ///
    /// @param reader the open reader
    /// @return the fully decoded image
    /// @throws IOException if a frame cannot be decoded
    private static AvifImage collect(AvifImageReader reader) throws IOException {
        AvifImageInfo info = reader.info();
        return new AvifImage(info, reader.readAllFrames());
    }
}
