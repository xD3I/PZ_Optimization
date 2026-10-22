// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;

import pzopt.vendor.avif.av1.Av1DecoderConfig;
import pzopt.vendor.avif.internal.io.AvifDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Creates AVIF image readers with an immutable reusable set of decoding options.

public final class AvifImageReaderFactory {
    /// The default maximum accepted encoded AVIF input size.
    private static final long DEFAULT_INPUT_SIZE_LIMIT = 256L * 1024L * 1024L;
    /// The default maximum cumulative estimated size of retained non-image metadata.
    private static final long DEFAULT_METADATA_SIZE_LIMIT = 64L * 1024L * 1024L;

    /// The default reader factory, with a 256 MiB input limit and a 64 MiB metadata limit.
    public static final AvifImageReaderFactory DEFAULT = new AvifImageReaderFactory(
            Av1DecoderConfig.DEFAULT,
            null,
            DEFAULT_INPUT_SIZE_LIMIT,
            DEFAULT_METADATA_SIZE_LIMIT
    );

    /// The maximum encoded input size supported by the integer-offset BMFF parser.
    private static final long MAX_SUPPORTED_INPUT_SIZE = Integer.MAX_VALUE - 8L;

    /// The underlying AV1 decoder configuration.
    private final Av1DecoderConfig av1DecoderConfig;
    /// The configured packed ARGB output format, or `null` to select one from the source bit depth.
    private final AvifPixelFormat outputPixelFormat;
    /// The maximum accepted encoded AVIF input size in bytes, or `0` for no limit.
    private final long inputSizeLimit;
    /// The maximum cumulative estimated size of retained non-image metadata, or `0` for no configured limit.
    private final long metadataSizeLimit;

    /// Creates a reader factory with validated options.
    ///
    /// @param av1DecoderConfig the underlying AV1 decoder configuration
    /// @param outputPixelFormat the packed ARGB output format, or `null` for automatic selection
    /// @param inputSizeLimit the maximum accepted encoded input size in bytes, or `0` for no limit
    /// @param metadataSizeLimit the maximum cumulative estimated size of retained non-image
    ///                          metadata in bytes, or `0` for no configured limit
    private AvifImageReaderFactory(
            Av1DecoderConfig av1DecoderConfig,
            AvifPixelFormat outputPixelFormat,
            long inputSizeLimit,
            long metadataSizeLimit
    ) {
        this.av1DecoderConfig = Objects.requireNonNull(av1DecoderConfig, "av1DecoderConfig");
        this.outputPixelFormat = outputPixelFormat;
        this.inputSizeLimit = inputSizeLimit;
        this.metadataSizeLimit = metadataSizeLimit;
    }

    /// Returns the underlying AV1 decoder configuration.
    ///
    /// @return the underlying AV1 decoder configuration
    public Av1DecoderConfig av1DecoderConfig() {
        return av1DecoderConfig;
    }

    /// Returns the configured packed ARGB output format.
    ///
    /// A `null` value selects [AvifBitDepth#defaultPixelFormat()] after the source bit depth
    /// is known.
    ///
    /// @return the configured output format, or `null` for automatic selection
    public AvifPixelFormat outputPixelFormat() {
        return outputPixelFormat;
    }

    /// Returns the maximum accepted encoded AVIF input size.
    ///
    /// A value of `0` means that readers created by this factory do not apply an additional
    /// configured limit. The parser's implementation limit still applies. For stream and channel
    /// inputs, the value limits the exclusive source position that may be referenced or consumed;
    /// unread trailing bytes are not inspected.
    ///
    /// @return the maximum accepted encoded AVIF input size in bytes, or `0` for no limit
    public long inputSizeLimit() {
        return inputSizeLimit;
    }

    /// Returns the maximum cumulative estimated size of retained non-image metadata.
    ///
    /// The estimate covers payloads such as ICC profiles, Exif, XMP, gain-map metadata, grids,
    /// sample-transform expressions, and opaque item properties, together with parser structures
    /// such as items, properties, extents, references, and expanded sequence sample tables. A value
    /// of `0` disables the configured metadata limit; encoded input and implementation limits still
    /// apply.
    ///
    /// @return the metadata size limit in bytes, or `0` when no configured limit applies
    public long metadataSizeLimit() {
        return metadataSizeLimit;
    }

    /// Returns a factory using the supplied AV1 decoder configuration.
    ///
    /// @param value the underlying AV1 decoder configuration
    /// @return a factory with the supplied AV1 decoder configuration
    public AvifImageReaderFactory withAv1DecoderConfig(Av1DecoderConfig value) {
        Av1DecoderConfig checkedValue = Objects.requireNonNull(value, "value");
        return checkedValue == av1DecoderConfig
                ? this
                : new AvifImageReaderFactory(checkedValue, outputPixelFormat, inputSizeLimit, metadataSizeLimit);
    }

    /// Returns a factory using the supplied packed ARGB output format.
    ///
    /// Passing `null` enables automatic selection: 8-bit sources use `ARGB_8888` and
    /// higher-bit-depth sources use `ARGB_16161616`.
    ///
    /// @param value the requested output format, or `null` for automatic selection
    /// @return a factory with the supplied output format
    public AvifImageReaderFactory withOutputPixelFormat(AvifPixelFormat value) {
        return value == outputPixelFormat
                ? this
                : new AvifImageReaderFactory(av1DecoderConfig, value, inputSizeLimit, metadataSizeLimit);
    }

    /// Returns a factory using the supplied maximum encoded input size.
    ///
    /// A value of `0` disables the configured limit. Positive values are applied before retaining
    /// array, buffer, and path inputs and while progressively reading stream and channel inputs.
    /// Unread trailing stream or channel bytes are not inspected and therefore do not count toward
    /// the limit. The parser's implementation limit still applies when this value is `0`.
    ///
    /// @param value the maximum accepted encoded input size in bytes, or `0` for no limit
    /// @return a factory with the supplied input-size limit
    public AvifImageReaderFactory withInputSizeLimit(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("inputSizeLimit must be non-negative");
        }
        return value == inputSizeLimit
                ? this
                : new AvifImageReaderFactory(av1DecoderConfig, outputPixelFormat, value, metadataSizeLimit);
    }

    /// Returns a factory using the supplied cumulative retained-metadata estimate limit.
    ///
    /// A value of `0` disables the configured metadata limit. Encoded input and implementation
    /// limits still apply.
    ///
    /// @param value the maximum cumulative estimated retained metadata size in bytes, or `0` for
    ///              no configured limit
    /// @return a factory with the supplied metadata-size limit
    public AvifImageReaderFactory withMetadataSizeLimit(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("metadataSizeLimit must be non-negative");
        }
        return value == metadataSizeLimit
                ? this
                : new AvifImageReaderFactory(av1DecoderConfig, outputPixelFormat, inputSizeLimit, value);
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// The reader borrows the array without copying it. The caller must not modify the array until
    /// the reader is closed. If this method fails, the array is no longer retained.
    ///
    /// @param source the complete AVIF source bytes
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the input exceeds the configured limit or is not a supported
    ///                              AVIF container
    public AvifImageReader open(byte[] source) throws AvifDecodeException {
        byte[] checkedSource = Objects.requireNonNull(source, "source");
        validateInputSize(checkedSource.length);
        return new AvifImageReader(AvifDataSource.ofBytes(checkedSource), this);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// The reader borrows the region from the source's current position to its limit without
    /// copying it. This method and subsequent reader operations do not change the source's position
    /// or limit, and later changes to those bounds do not affect the captured region. The caller
    /// must not modify that region through the source, a backing array, or another alias until the
    /// reader is closed. If this method fails, the region is no longer retained.
    ///
    /// @param source the source byte buffer
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the input exceeds the configured limit or is not a supported
    ///                              AVIF container
    public AvifImageReader open(ByteBuffer source) throws AvifDecodeException {
        ByteBuffer checkedSource = Objects.requireNonNull(source, "source");
        validateInputSize(checkedSource.remaining());
        return new AvifImageReader(AvifDataSource.ofByteBuffer(checkedSource), this);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// This method reads only the prefix needed to parse the container metadata. Later reader
    /// operations consume encoded image data progressively. The stream is borrowed, remains open,
    /// and must remain usable until the reader is closed. No temporary file or complete in-memory
    /// copy is created. The reader may consume a bounded amount of input beyond the bytes currently
    /// needed for parsing or decoding. Top-level boxes after the metadata required for decoding may
    /// remain unread and are not validated.
    ///
    /// Because the stream is not seekable, indexed access and a container layout that requires
    /// revisiting discarded bytes fail with [AvifErrorCode#SEEKABLE_SOURCE_REQUIRED]. Use
    /// [#open(Path)], [#open(byte[])], or [#open(ByteBuffer)] when arbitrary access is required.
    /// If reading fails, the consumed prefix remains consumed.
    ///
    /// @param source the source input stream
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    public AvifImageReader open(InputStream source) throws IOException {
        return new AvifImageReader(
                AvifDataSource.progressive(
                        Channels.newChannel(Objects.requireNonNull(source, "source")),
                        maximumInputSize()
                ),
                this
        );
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// This method reads only the prefix needed to parse the container metadata. Later reader
    /// operations consume encoded image data progressively. The channel is borrowed, remains
    /// open, and must remain usable in blocking mode until the reader is closed. No temporary file
    /// or complete in-memory copy is created. The reader may consume a bounded amount of input
    /// beyond the bytes currently needed for parsing or decoding. Top-level boxes after the
    /// metadata required for decoding may remain unread and are not validated.
    ///
    /// Because the channel is treated as forward-only, indexed access and a container layout that
    /// requires revisiting discarded bytes fail with [AvifErrorCode#SEEKABLE_SOURCE_REQUIRED]. Use
    /// [#open(Path)], [#open(byte[])], or [#open(ByteBuffer)] when arbitrary access is required.
    /// If reading fails, the consumed prefix remains consumed.
    ///
    /// @param source the source byte channel
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    /// @throws IllegalArgumentException if the channel is selectable and configured as
    ///                                  non-blocking
    public AvifImageReader open(ReadableByteChannel source) throws IOException {
        return new AvifImageReader(
                AvifDataSource.progressive(
                        Objects.requireNonNull(source, "source"),
                        maximumInputSize()
                ),
                this
        );
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// The returned reader owns an open read-only file handle and releases it from
    /// [AvifImageReader#close()]. The file must not be modified until the reader is closed.
    ///
    /// @param source the source file path
    /// @return a new AVIF image reader
    /// @throws IOException if the file cannot be read, exceeds the configured limit, or does not
    ///                     contain a supported AVIF container
    public AvifImageReader open(Path source) throws IOException {
        Path checkedSource = Objects.requireNonNull(source, "source");
        AvifDataSource retainedSource = AvifDataSource.open(checkedSource);
        try {
            validateInputSize(retainedSource.limit());
            return new AvifImageReader(retainedSource, this);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                retainedSource.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Validates a complete input size against this factory's limit.
    ///
    /// @param inputSize the complete input size in bytes
    /// @throws AvifDecodeException if the size exceeds the configured limit
    private void validateInputSize(long inputSize) throws AvifDecodeException {
        if (inputSize > maximumInputSize()) {
            throw inputTooLarge(maximumInputSize());
        }
    }

    /// Returns the effective configured and implementation input limit.
    ///
    /// @return the maximum supported input size in bytes
    private long maximumInputSize() {
        return inputSizeLimit == 0
                ? MAX_SUPPORTED_INPUT_SIZE
                : Math.min(inputSizeLimit, MAX_SUPPORTED_INPUT_SIZE);
    }

    /// Creates an input-too-large exception for an effective limit.
    ///
    /// @param maximumInputSize the effective maximum input size
    /// @return an input-too-large exception
    private static AvifDecodeException inputTooLarge(long maximumInputSize) {
        return new AvifDecodeException(
                AvifErrorCode.INPUT_TOO_LARGE,
                "AVIF input exceeds supported size limit: " + maximumInputSize + " bytes",
                null
        );
    }
}
