// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;

import pzopt.vendor.avif.av1.Av1Decoder;
import pzopt.vendor.avif.av1.Av1ColorConfig;
import pzopt.vendor.avif.av1.Av1DecodedOutput;
import pzopt.vendor.avif.av1.Av1DecodeException;
import pzopt.vendor.avif.internal.av1.output.ArgbOutput;
import pzopt.vendor.avif.internal.av1.output.YuvToRgbTransform;
import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;
import pzopt.vendor.avif.internal.bmff.AvifContainer;
import pzopt.vendor.avif.internal.bmff.AvifContainerParser;
import pzopt.vendor.avif.internal.bmff.AvifImageSource;
import pzopt.vendor.avif.internal.bmff.AvifPayload;
import pzopt.vendor.avif.internal.bmff.SampleTransform;
import pzopt.vendor.avif.internal.io.AvifDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// High-level reader for AVIF images.
///
/// Readers opened from an input stream or readable channel support sequential [#readFrame()] and
/// [#readAllFrames()] access. Their indexed, raw-plane, and tone-mapping operations fail with
/// [AvifErrorCode#SEEKABLE_SOURCE_REQUIRED]. Sequential decoding also reports that code when the
/// container layout requires revisiting data outside the bounded progressive read window.
/// After a sequence frame fails, indexed operations remain available for seekable sources, but
/// sequential reading cannot resume because its persistent AV1 decoder may have consumed input.

public final class AvifImageReader implements AutoCloseable {
    /// The immutable factory options used to create this reader.
    private final AvifImageReaderFactory factory;
    /// The retained container source.
    private final AvifDataSource source;
    /// The parsed container data.
    private final AvifContainer container;
    /// The retained color sequence sample descriptors, or `null` for a still image.
    private final AvifPayload [] sequenceSamplePayloads;
    /// The retained alpha sequence sample descriptors, or `null` when absent.
    private final AvifPayload [] sequenceAlphaSamplePayloads;
    /// The retained depth sequence sample descriptors, or `null` when absent.
    private final AvifPayload [] sequenceDepthSamplePayloads;
    /// The next frame index for sequential reads.
    private int nextFrameIndex;
    /// Whether this reader has been closed.
    private boolean closed;
    /// A persistent AV1 decoder for image sequences, or `null`.
    private Av1Decoder sequenceAv1Decoder;
    /// The expected next frame index from the persistent sequence reader.
    private int sequenceAv1FrameIndex;
    /// A persistent AV1 decoder for image-sequence alpha samples, or `null`.
    private Av1Decoder sequenceAlphaAv1Decoder;
    /// The expected next frame index from the persistent sequence alpha reader.
    private int sequenceAlphaAv1FrameIndex;
    /// Whether a sequence frame failed after sequential decoding began.
    private boolean sequentialSequenceReadFailed;
    /// The reusable indexed color-sequence cursor, or `null` before the first indexed read.
    private SequenceDecoderCursor indexedColorSequenceCursor;
    /// The reusable indexed alpha-sequence cursor, or `null` before the first indexed read.
    private SequenceDecoderCursor indexedAlphaSequenceCursor;
    /// The reusable indexed depth-sequence cursor, or `null` before the first indexed read.
    private SequenceDecoderCursor indexedDepthSequenceCursor;

    /// Creates an AVIF image reader.
    ///
    /// @param source the retained AVIF source
    /// @param factory the immutable factory that owns the decoding options
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    AvifImageReader(AvifDataSource source, AvifImageReaderFactory factory) throws AvifDecodeException {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.source = Objects.requireNonNull(source, "source");
        try {
            this.container = AvifContainerParser.parse(source, factory.metadataSizeLimit());
            this.sequenceSamplePayloads = container.samplePayloads();
            this.sequenceAlphaSamplePayloads = container.sequenceAlphaSamplePayloads();
            this.sequenceDepthSamplePayloads = container.sequenceDepthSamplePayloads();
        } catch (AvifDecodeException | RuntimeException | Error exception) {
            try {
                source.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens an AVIF image reader over a byte array.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// The reader borrows the array, which must not be modified until the reader is closed.
    ///
    /// @param source the complete AVIF source bytes
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(byte[] source) throws AvifDecodeException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a byte buffer.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// The reader borrows the buffer's remaining region without changing its position or limit.
    /// The region must not be modified through any alias until the reader is closed.
    ///
    /// @param source the source byte buffer, read from its current position to its limit
    /// @return a new AVIF image reader
    /// @throws AvifDecodeException if the source is not a supported AVIF container
    public static AvifImageReader open(ByteBuffer source) throws AvifDecodeException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over an input stream.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// It borrows the stream and reads container metadata and encoded frames progressively without
    /// closing the stream. The caller must keep the stream open until the reader is closed.
    ///
    /// @param source the source input stream
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(InputStream source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a readable byte channel.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// It borrows the channel and reads container metadata and encoded frames progressively without
    /// closing the channel. The caller must keep the channel open and in blocking mode until the
    /// reader is closed.
    ///
    /// @param source the source byte channel
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    /// @throws IllegalArgumentException if the channel is selectable and configured as
    ///                                  non-blocking
    public static AvifImageReader open(ReadableByteChannel source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Opens an AVIF image reader over a file path.
    ///
    /// This method is equivalent to `AvifImageReaderFactory.DEFAULT.open(source)`.
    /// The returned reader owns an open read-only file handle; the file must not be modified until
    /// the reader is closed.
    ///
    /// @param source the source file path
    /// @return a new AVIF image reader
    /// @throws IOException if the source cannot be read or decoded
    public static AvifImageReader open(Path source) throws IOException {
        return AvifImageReaderFactory.DEFAULT.open(source);
    }

    /// Returns immutable image metadata parsed from the container.
    ///
    /// @return immutable image metadata parsed from the container
    /// @throws AvifDecodeException if this reader is closed
    public AvifImageInfo info() throws AvifDecodeException {
        ensureOpen();
        return container.info();
    }

    /// Reads the next decoded frame.
    ///
    /// If a sequence frame fails, subsequent calls fail without attempting to reuse the partially
    /// advanced persistent sequence decoder. Indexed access remains available when the source is
    /// seekable.
    ///
    /// @return the next decoded frame, or `null` at end-of-stream
    /// @throws IOException if the frame cannot be decoded or sequential sequence decoding has
    ///                     already failed
    public AvifFrame readFrame() throws IOException {
        ensureOpen();
        if (sequentialSequenceReadFailed) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequential sequence decoding cannot resume after a frame failure",
                    null
            );
        }
        if (nextFrameIndex >= container.info().frameCount()) {
            return null;
        }
        try {
            AvifFrame frame = container.isSequence()
                    ? readSequenceFrameSequential(nextFrameIndex)
                    : readFrameAtIndex(nextFrameIndex);
            nextFrameIndex++;
            return frame;
        } catch (IOException | RuntimeException exception) {
            if (container.isSequence()) {
                sequentialSequenceReadFailed = true;
            }
            throw exception;
        }
    }

    /// Reads the decoded frame at the supplied index.
    ///
    /// For image sequences, forward indexed reads reuse decoder state. Reading an index lower than
    /// the most recently decoded indexed frame restarts the indexed decoder from the beginning and
    /// does not affect sequential [#readFrame()] state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if the frame cannot be decoded or the reader was opened from a
    ///                     forward-only stream or channel
    public AvifFrame readFrame(int frameIndex) throws IOException {
        ensureRandomAccess("readFrame(int)");
        return readFrameAtIndex(frameIndex);
    }

    /// Reads a frame without applying the public indexed-access capability check.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if the frame cannot be decoded
    private AvifFrame readFrameAtIndex(int frameIndex) throws IOException {
        ensureOpen();
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (container.isSequence()) {
            return readSequenceFrameRandomAccess(frameIndex);
        }
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return readSampleTransformedFrame(frameIndex, sampleTransform);
        }
        AvifImageSource primarySource = container.primarySource();
        if (primarySource == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        DecodedRawImage decodedColor = decodeImageSource(primarySource, "Primary AV1 image");
        AvifFrame rawFrame = adaptRawPlanes(
                decodedColor.planes(),
                decodedColor.colorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
        );
        AvifImageSource alphaSource = container.alphaSource();
        if (alphaSource != null) {
            Av1DecodedPlanes alphaPlanes = decodeAlphaImageSource(
                    alphaSource,
                    "Alpha auxiliary AV1 image",
                    decodedColor.colorConfig().bitDepth()
            ).planes();
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Alpha dimensions differ from color dimensions",
                        null
                );
            }
            rawFrame = combineFrameWithAlphaPlane(
                    rawFrame,
                    alphaPlanes,
                    alphaPlanes.bitDepth(),
                    frameIndex,
                    container.info().alphaPremultiplied()
            );
        }
        return applyTransforms(rawFrame);
    }

    /// Reads raw decoded color planes for the frame at the supplied index.
    ///
    /// The returned planes expose the decoded AV1 color image before AVIF auxiliary alpha
    /// composition and before AVIF item transforms such as `clap`, `irot`, and `imir`.
    /// Grid-derived still images are returned as composed raw planes.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded color planes
    /// @throws IOException if the frame cannot be decoded or the reader was opened from a
    ///                     forward-only stream or channel
    public Av1DecodedPlanes readRawColorPlanes(int frameIndex) throws IOException {
        ensureRandomAccess("readRawColorPlanes(int)");
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (container.isSequence()) {
            return readSequenceRawColorPlanes(frameIndex);
        }
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, false).planes();
        }
        AvifImageSource primarySource = container.primarySource();
        if (primarySource == null) {
            throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Primary AV1 item payload is missing", null);
        }
        return decodeImageSource(primarySource, "Primary AV1 image").planes();
    }

    /// Reads raw decoded alpha auxiliary planes for the frame at the supplied index.
    ///
    /// The returned planes expose an alpha auxiliary AV1 image before AVIF item transforms are
    /// applied. They are monochrome and full range; color-coded legacy alpha images expose only
    /// their luma plane, and legacy limited-range alpha samples are expanded to full range. A
    /// `null` return value means the frame has no alpha auxiliary image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded alpha auxiliary planes, or `null` when no alpha auxiliary image is present
    /// @throws IOException if the alpha auxiliary image cannot be decoded or the reader was opened
    ///                     from a forward-only stream or channel
    public Av1DecodedPlanes readRawAlphaPlanes(int frameIndex) throws IOException {
        ensureRandomAccess("readRawAlphaPlanes(int)");
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (!container.info().alphaPresent()) {
            return null;
        }
        if (container.isSequence()) {
            AvifPayload [] alphaPayloads = sequenceAlphaSamplePayloads;
            if (alphaPayloads == null) {
                return null;
            }
            DecodedRawImage decodedAlpha = readSequenceRawImage(
                    frameIndex,
                    alphaPayloads,
                    "Alpha sequence frame"
            );
            return normalizeAlphaPlanes(
                    decodedAlpha.planes(),
                    decodedAlpha.colorConfig(),
                    container.info().bitDepth(),
                    "Alpha sequence frame"
            );
        }
        SampleTransform sampleTransform = container.sampleTransform();
        if (sampleTransform != null) {
            return decodeSampleTransform(sampleTransform, true).planes();
        }
        AvifImageSource alphaSource = container.alphaSource();
        return alphaSource != null
                ? decodeAlphaImageSource(
                        alphaSource,
                        "Alpha auxiliary AV1 image",
                        container.info().bitDepth()
                ).planes()
                : null;
    }

    /// Reads raw decoded gain-map planes for the frame at the supplied index.
    ///
    /// The returned planes expose the AV1 image referenced by the AVIF `tmap` gain-map
    /// association. A `null` return value means the frame has no gain-map image. The returned
    /// planes are not tone-mapped or applied to the base image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded gain-map planes, or `null` when no gain-map image is present
    /// @throws IOException if the gain-map image cannot be decoded or the reader was opened from a
    ///                     forward-only stream or channel
    public Av1DecodedPlanes readRawGainMapPlanes(int frameIndex) throws IOException {
        ensureRandomAccess("readRawGainMapPlanes(int)");
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        AvifGainMapInfo gainMapInfo = container.info().gainMapInfo();
        if (gainMapInfo == null) {
            return null;
        }
        AvifImageSource gainMapSource = container.gainMapSource();
        if (gainMapSource != null) {
            return decodeImageSource(gainMapSource, "Gain-map AV1 image").planes();
        }
        throw unsupported("Gain-map item type is not decodable as AV1 planes: " + gainMapInfo.gainMapItemType());
    }

    /// Reads the decoded frame at the supplied index with its AVIF gain map applied.
    ///
    /// The returned frame preserves the regular `readFrame(int)` alpha composition, item
    /// transforms, frame index, and packed ARGB pixel format. A `null` return value means the frame
    /// has no supported gain-map association.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    public AvifFrame readToneMappedFrame(int frameIndex, double hdrHeadroom) throws IOException {
        return readToneMappedFrameInternal(frameIndex, hdrHeadroom, null);
    }

    /// Reads the decoded frame at the supplied index with its AVIF gain map applied.
    ///
    /// The returned frame preserves the regular `readFrame(int)` alpha composition, item
    /// transforms, frame index, and packed ARGB pixel format. RGB channels are converted into the
    /// requested CICP output color space after gain-map application. ICC profile application
    /// remains metadata-only.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @param outputColorInfo the requested output CICP color information
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    public AvifFrame readToneMappedFrame(
            int frameIndex,
            double hdrHeadroom,
            AvifColorInfo outputColorInfo
    ) throws IOException {
        return readToneMappedFrameInternal(
                frameIndex,
                hdrHeadroom,
                Objects.requireNonNull(outputColorInfo, "outputColorInfo")
        );
    }

    /// Reads the decoded frame at the supplied index with optional output color conversion.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param hdrHeadroom the requested display HDR headroom in log2 space
    /// @param outputColorInfo the requested output CICP color information, or `null` for base color space
    /// @return the tone-mapped frame, or `null` when no supported gain map is present
    /// @throws IOException if the base frame or gain-map image cannot be decoded
    private AvifFrame readToneMappedFrameInternal(
            int frameIndex,
            double hdrHeadroom,
            AvifColorInfo outputColorInfo
    ) throws IOException {
        ensureRandomAccess("readToneMappedFrame");
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (!Double.isFinite(hdrHeadroom) || hdrHeadroom < 0.0) {
            throw new IllegalArgumentException("hdrHeadroom must be a finite non-negative value");
        }
        AvifGainMapInfo gainMapInfo = container.info().gainMapInfo();
        if (gainMapInfo == null) {
            return null;
        }
        AvifGainMapMetadata metadata = gainMapInfo.metadata();
        if (metadata == null) {
            return null;
        }
        Av1DecodedPlanes gainMapPlanes = null;
        if (AvifGainMapToneMapper.requiresGainMap(metadata, hdrHeadroom)) {
            gainMapPlanes = readRawGainMapPlanes(frameIndex);
            if (gainMapPlanes == null) {
                return null;
            }
        }
        try {
            return AvifGainMapToneMapper.apply(
                    readFrame(frameIndex),
                    gainMapPlanes,
                    metadata,
                    container.info().colorInfo(),
                    gainMapInfo.toneMappedColorInfo(),
                    gainMapInfo.gainMapColorInfo(),
                    outputColorInfo,
                    hdrHeadroom
            );
        } catch (UnsupportedOperationException exception) {
            throw unsupportedColorConversion(exception);
        }
    }

    /// Reads raw decoded depth auxiliary planes for the frame at the supplied index.
    ///
    /// The returned planes expose a depth auxiliary AV1 image before AVIF item transforms are
    /// applied. A `null` return value means the frame has no depth auxiliary image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded depth auxiliary planes, or `null` when no depth auxiliary image is present
    /// @throws IOException if the depth auxiliary image cannot be decoded or the reader was opened
    ///                     from a forward-only stream or channel
    public Av1DecodedPlanes readRawDepthPlanes(int frameIndex) throws IOException {
        ensureRandomAccess("readRawDepthPlanes(int)");
        if (frameIndex < 0 || frameIndex >= container.info().frameCount()) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (container.isSequence()) {
            AvifPayload [] depthPayloads = sequenceDepthSamplePayloads;
            if (depthPayloads == null) {
                return null;
            }
            return readSequenceRawAuxiliaryPlanes(frameIndex, depthPayloads, "Depth sequence frame");
        }
        AvifImageSource depthSource = container.depthSource();
        if (depthSource != null) {
            return decodeImageSource(depthSource, "Depth auxiliary AV1 image").planes();
        }
        if (!hasAuxiliaryType(container.info(), AvifAuxiliaryImageInfo.DEPTH_TYPE)) {
            return null;
        }
        throw unsupported("Depth auxiliary item type is not decodable as AV1 planes");
    }

    /// Decodes the next frame from an image sequence using the persistent sequential AV1 decoder.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrameSequential(int frameIndex) throws IOException {
        AvifPayload [] payloads = sequenceSamplePayloads;
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        if (frameIndex != sequenceAv1FrameIndex && sequenceAv1Decoder != null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequential AVIF sequence reader is out of sync at frame " + frameIndex,
                    null
            );
        }
        if (sequenceAv1Decoder == null) {
            sequenceAv1Decoder = Av1Decoder.open(
                    AvifPayload.openInput(payloads),
                    factory.av1DecoderConfig()
            );
            sequenceAv1FrameIndex = 0;
        }
        while (sequenceAv1FrameIndex < frameIndex) {
            Av1DecodedOutput skipped = sequenceAv1Decoder.readOutput();
            if (skipped == null)
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Sequence ended before frame " + frameIndex, null);
            sequenceAv1FrameIndex++;
        }
        try {
            Av1DecodedOutput output = sequenceAv1Decoder.readOutput();
            if (output == null)
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, "Sequence produced no frame: " + frameIndex, null);
            sequenceAv1FrameIndex++;
            AvifFrame rawFrame = adaptRawPlanes(
                    output.planes(),
                    output.colorConfig(),
                    container.info().colorInfo(),
                    frameIndex,
                    factory.outputPixelFormat()
            );
            return combineFrameWithSequenceAlphaSequential(rawFrame, frameIndex);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Decodes raw color planes for one image-sequence frame without mutating sequential playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return raw decoded color planes
    /// @throws IOException if decoding fails
    private Av1DecodedPlanes readSequenceRawColorPlanes(int frameIndex) throws IOException {
        AvifPayload [] payloads = sequenceSamplePayloads;
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        return readSequenceRawImage(frameIndex, payloads, "Sequence frame").planes();
    }

    /// Decodes raw planes for one image-sequence auxiliary frame without mutating playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param payloads the auxiliary sample payloads
    /// @param label the diagnostic label for failures
    /// @return raw decoded auxiliary planes
    /// @throws IOException if decoding fails
    private Av1DecodedPlanes readSequenceRawAuxiliaryPlanes(
            int frameIndex,
            AvifPayload [] payloads,
            String label
    ) throws IOException {
        if (frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        return readSequenceRawImage(frameIndex, payloads, label).planes();
    }

    /// Decodes raw planes for one image-sequence frame without mutating playback state.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param payloads the sample payloads
    /// @param label the diagnostic label for failures
    /// @return raw decoded planes
    /// @throws IOException if decoding fails
    private DecodedRawImage readSequenceRawImage(
            int frameIndex,
            AvifPayload [] payloads,
            String label
    ) throws IOException {
        try {
            Av1DecodedOutput requiredOutput = indexedSequenceCursor(payloads, label).read(frameIndex);
            return new DecodedRawImage(requiredOutput.planes(), requiredOutput.colorConfig());
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Returns the reusable indexed decoder cursor for one retained sequence payload array.
    ///
    /// @param payloads the retained color, alpha, or depth payload array
    /// @param label the diagnostic sequence label used when creating a cursor
    /// @return the matching reusable decoder cursor
    private SequenceDecoderCursor indexedSequenceCursor(
            AvifPayload [] payloads,
            String label
    ) {
        if (payloads == sequenceSamplePayloads) {
            if (indexedColorSequenceCursor == null) {
                indexedColorSequenceCursor = new SequenceDecoderCursor(payloads, label);
            }
            return indexedColorSequenceCursor;
        }
        if (payloads == sequenceAlphaSamplePayloads) {
            if (indexedAlphaSequenceCursor == null) {
                indexedAlphaSequenceCursor = new SequenceDecoderCursor(payloads, label);
            }
            return indexedAlphaSequenceCursor;
        }
        if (payloads == sequenceDepthSamplePayloads) {
            if (indexedDepthSequenceCursor == null) {
                indexedDepthSequenceCursor = new SequenceDecoderCursor(payloads, label);
            }
            return indexedDepthSequenceCursor;
        }
        throw new IllegalArgumentException("payloads are not retained by this reader");
    }

    /// Decodes one AV1 item payload and selects its requested output spatial layer.
    ///
    /// @param payload the AV1 payload to decode
    /// @param label the diagnostic label for failures
    /// @param operatingPoint the selected AV1 operating-point index
    /// @param selectedSpatialLayer the selected spatial-layer identifier, or
    ///        [AvifImageSource#HIGHEST_SPATIAL_LAYER]
    /// @return decoded raw planes and their AV1 color configuration
    /// @throws IOException if decoding fails
    private DecodedRawImage decodeRawImage(
            AvifPayload payload,
            String label,
            int operatingPoint,
            int selectedSpatialLayer
    ) throws IOException {
        try (Av1Decoder rawDecoder = Av1Decoder.open(
                payload.openInput(),
                factory.av1DecoderConfig().withOperatingPoint(operatingPoint)
        )) {
            DecodedRawImage selectedImage = null;
            int highestSpatialId = -1;
            while (true) {
                Av1DecodedOutput output = rawDecoder.readOutput();
                if (output == null) {
                    break;
                }
                boolean matchesSelection = selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER
                        ? output.spatialId() >= highestSpatialId
                        : output.spatialId() == selectedSpatialLayer;
                if (!matchesSelection) {
                    continue;
                }
                selectedImage = new DecodedRawImage(
                        output.planes(),
                        output.colorConfig()
                );
                highestSpatialId = output.spatialId();
                if (selectedSpatialLayer != AvifImageSource.HIGHEST_SPATIAL_LAYER) {
                    break;
                }
            }
            if (selectedImage == null) {
                String message = selectedSpatialLayer == AvifImageSource.HIGHEST_SPATIAL_LAYER
                        ? label + " produced no frame"
                        : label + " produced no output for selected spatial layer " + selectedSpatialLayer;
                throw new AvifDecodeException(AvifErrorCode.AV1_DECODE_FAILED, message, null);
            }
            return selectedImage;
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Decodes and applies one parsed Sample Transform.
    ///
    /// @param sampleTransform the parsed Sample Transform
    /// @param alpha whether to reconstruct alpha rather than color planes
    /// @return the reconstructed planes and primary-input AV1 color configuration
    /// @throws IOException if one input image cannot be decoded
    private DecodedSampleTransform decodeSampleTransform(SampleTransform sampleTransform, boolean alpha)
            throws IOException {
        Av1DecodedPlanes[] inputPlanes = new Av1DecodedPlanes[sampleTransform.inputCount()];
        Av1ColorConfig primaryColorConfig = null;
        for (int inputIndex = 0; inputIndex < sampleTransform.inputCount(); inputIndex++) {
            SampleTransform.Input input = sampleTransform.input(inputIndex);
            AvifImageSource source = alpha ? input.alphaSource() : input.colorSource();
            if (source == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.BMFF_PARSE_FAILED,
                        "Sample Transform alpha input is missing: " + inputIndex,
                        null
                );
            }
            String label = "Sample Transform " + (alpha ? "alpha " : "color ") + "input " + inputIndex;
            DecodedRawImage decoded = alpha
                    ? decodeAlphaImageSource(source, label, input.colorBitDepth())
                    : decodeImageSource(source, label);
            inputPlanes[inputIndex] = decoded.planes();
            if (inputIndex == sampleTransform.primaryInputIndex()) {
                primaryColorConfig = decoded.colorConfig();
            }
        }
        if (primaryColorConfig == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    "Sample Transform primary input is missing",
                    null
            );
        }
        try {
            Av1DecodedPlanes reconstructed = alpha
                    ? sampleTransform.applyAlpha(inputPlanes)
                    : sampleTransform.apply(inputPlanes);
            return new DecodedSampleTransform(reconstructed, primaryColorConfig);
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sample Transform input planes cannot be reconstructed: " + exception.getMessage(),
                    null,
                    exception
            );
        }
    }

    /// Decodes one standalone or grid-derived AV1 image source.
    ///
    /// @param source the image source
    /// @param label the diagnostic label for failures
    /// @return decoded raw planes and their AV1 color configuration
    /// @throws IOException if the image source cannot be decoded
    private DecodedRawImage decodeImageSource(AvifImageSource source, String label) throws IOException {
        return decodeImageSource(source, label, null);
    }

    /// Decodes an alpha AV1 image source and validates its required AVIF signaling.
    ///
    /// @param source the alpha image source
    /// @param label the diagnostic label for failures
    /// @param expectedBitDepth the associated master-image bit depth
    /// @return decoded raw alpha planes and their AV1 color configuration
    /// @throws IOException if the source cannot be decoded or violates AVIF alpha requirements
    private DecodedRawImage decodeAlphaImageSource(
            AvifImageSource source,
            String label,
            AvifBitDepth expectedBitDepth
    ) throws IOException {
        return decodeImageSource(source, label, expectedBitDepth);
    }

    /// Decodes one standalone or grid-derived AV1 image source with optional alpha validation.
    ///
    /// @param source the image source
    /// @param label the diagnostic label for failures
    /// @param expectedAlphaBitDepth the required alpha bit depth, or `null` for a color image
    /// @return decoded raw planes and their AV1 color configuration
    /// @throws IOException if the image source cannot be decoded
    private DecodedRawImage decodeImageSource(
            AvifImageSource source,
            String label,
            AvifBitDepth expectedAlphaBitDepth
    ) throws IOException {
        if (!source.isGrid()) {
            DecodedRawImage decoded = decodeRawImage(
                    source.payload(0),
                    label,
                    source.operatingPoint(0),
                    source.selectedSpatialLayer(0)
            );
            validateDecodedItemDimensions(source, 0, decoded.planes(), label);
            if (expectedAlphaBitDepth != null) {
                return new DecodedRawImage(
                        normalizeAlphaPlanes(
                                decoded.planes(),
                                decoded.colorConfig(),
                                expectedAlphaBitDepth,
                                label
                        ),
                        decoded.colorConfig()
                );
            }
            return decoded;
        }
        enforceGridFrameSizeLimit(source, label);
        AvifPayload [] cellPayloads = source.payloads();
        if (cellPayloads.length == 0) {
            throw new AvifDecodeException(
                    AvifErrorCode.BMFF_PARSE_FAILED,
                    label + " grid has no cells",
                    null
            );
        }
        Av1DecodedPlanes[] cellPlanes = new Av1DecodedPlanes[cellPayloads.length];
        Av1ColorConfig colorConfig = null;
        for (int cellIndex = 0; cellIndex < cellPayloads.length; cellIndex++) {
            DecodedRawImage decoded = decodeRawImage(
                    cellPayloads[cellIndex],
                    label + " grid cell " + cellIndex,
                    source.operatingPoint(cellIndex),
                    source.selectedSpatialLayer(cellIndex)
            );
            validateDecodedItemDimensions(
                    source,
                    cellIndex,
                    decoded.planes(),
                    label + " grid cell " + cellIndex
            );
            if (expectedAlphaBitDepth != null) {
                cellPlanes[cellIndex] = normalizeAlphaPlanes(
                        decoded.planes(),
                        decoded.colorConfig(),
                        expectedAlphaBitDepth,
                        label + " grid cell " + cellIndex
                );
            } else {
                cellPlanes[cellIndex] = decoded.planes();
            }
            if (colorConfig == null) {
                colorConfig = decoded.colorConfig();
            }
        }
        validateGridGeometry(
                cellPlanes,
                source.rows(),
                source.columns(),
                source.outputWidth(),
                source.outputHeight(),
                label
        );
        Av1DecodedPlanes composed = composeGridRawColorPlanes(
                cellPlanes,
                source.rows(),
                source.columns(),
                source.outputWidth(),
                source.outputHeight()
        );
        return new DecodedRawImage(composed, Objects.requireNonNull(colorConfig, "colorConfig"));
    }

    /// Enforces configured and implementation frame-size limits against a derived grid canvas.
    ///
    /// Individual AV1 cells are checked by `Av1Decoder`; this additional check prevents a
    /// collection of individually valid cells from producing an oversized composed image.
    ///
    /// @param source the normalized grid image source
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the grid canvas exceeds a frame-size limit
    private void enforceGridFrameSizeLimit(AvifImageSource source, String label) throws AvifDecodeException {
        long frameSizeLimit = factory.av1DecoderConfig().frameSizeLimit();
        long pixelCount = (long) source.outputWidth() * source.outputHeight();
        long effectiveLimit = frameSizeLimit == 0
                ? Integer.MAX_VALUE
                : Math.min(frameSizeLimit, Integer.MAX_VALUE);
        if (pixelCount > effectiveLimit) {
            String limitKind = frameSizeLimit != 0 && frameSizeLimit <= Integer.MAX_VALUE
                    ? "configured"
                    : "implementation";
            throw new AvifDecodeException(
                    AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED,
                    label + " grid size exceeds the " + limitKind + " limit: "
                            + source.outputWidth() + "x" + source.outputHeight(),
                    null
            );
        }
    }

    /// Validates a selected AV1 output frame against its associated `ispe` dimensions.
    ///
    /// @param source the normalized image source
    /// @param itemIndex the zero-based payload or grid-cell index
    /// @param planes the selected final decoded planes
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the decoded dimensions differ from `ispe`
    private static void validateDecodedItemDimensions(
            AvifImageSource source,
            int itemIndex,
            Av1DecodedPlanes planes,
            String label
    ) throws AvifDecodeException {
        int expectedWidth = source.itemWidth(itemIndex);
        int expectedHeight = source.itemHeight(itemIndex);
        if (planes.codedWidth() != expectedWidth || planes.codedHeight() != expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.ISPE_SIZE_MISMATCH,
                    label + " decoded dimensions " + planes.codedWidth() + "x" + planes.codedHeight()
                            + " do not match ispe " + expectedWidth + "x" + expectedHeight,
                    null
            );
        }
    }

    /// Decodes and renders one preferred Sample Transform still image.
    ///
    /// @param frameIndex the zero-based frame index
    /// @param sampleTransform the parsed Sample Transform
    /// @return the reconstructed and transformed AVIF frame
    /// @throws IOException if an input image cannot be decoded
    private AvifFrame readSampleTransformedFrame(int frameIndex, SampleTransform sampleTransform) throws IOException {
        DecodedSampleTransform decodedColor = decodeSampleTransform(sampleTransform, false);
        AvifFrame rawFrame = adaptRawPlanes(
                decodedColor.planes(),
                decodedColor.primaryColorConfig(),
                container.info().colorInfo(),
                frameIndex,
                factory.outputPixelFormat()
        );
        if (container.info().alphaPresent()) {
            Av1DecodedPlanes alphaPlanes = decodeSampleTransform(sampleTransform, true).planes();
            if (alphaPlanes.codedWidth() != rawFrame.width() || alphaPlanes.codedHeight() != rawFrame.height()) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Sample Transform alpha dimensions differ from color dimensions",
                        null
                );
            }
            validateAlphaLumaPlane(
                    alphaPlanes.lumaPlane(),
                    rawFrame.width(),
                    rawFrame.height(),
                    "Sample Transform alpha"
            );
            rawFrame = combineFrameWithAlphaPlane(
                    rawFrame,
                    alphaPlanes,
                    alphaPlanes.bitDepth(),
                    frameIndex,
                    container.info().alphaPremultiplied()
            );
        }
        return applyTransforms(rawFrame);
    }

    /// Renders reconstructed color planes into the requested packed ARGB format.
    ///
    /// Container `nclx` metadata takes precedence over the primary input's AV1 sequence-header
    /// color configuration, matching the normal still-image path.
    ///
    /// @param planes the reconstructed color planes
    /// @param av1ColorConfig the primary input's AV1 color configuration
    /// @param colorInfo the AVIF container color information, or `null`
    /// @param frameIndex the zero-based frame index
    /// @param outputPixelFormat the configured packed pixel format, or `null` to select by source bit depth
    /// @return the rendered AVIF frame
    /// @throws AvifDecodeException if the selected color conversion is unsupported
    private static AvifFrame adaptRawPlanes(
            Av1DecodedPlanes planes,
            Av1ColorConfig av1ColorConfig,
            AvifColorInfo colorInfo,
            int frameIndex,
            AvifPixelFormat outputPixelFormat
    ) throws AvifDecodeException {
        AvifPixelFormat pixelFormat = outputPixelFormat != null
                ? outputPixelFormat
                : planes.bitDepth().defaultPixelFormat();
        try {
            YuvToRgbTransform transform = colorInfo != null
                    ? YuvToRgbTransform.fromColorInfo(colorInfo, planes.chromaFormat() == Av1ChromaFormat.MONOCHROME)
                    : YuvToRgbTransform.fromColorConfig(av1ColorConfig);
            DecodedSurface decodedPlanes = toDecodedPlanes(planes);
            if (pixelFormat == AvifPixelFormat.ARGB_8888) {
                return AvifFrame.fromOwnedPixels(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbPixels(decodedPlanes, transform)
                );
            }
            if (pixelFormat == AvifPixelFormat.ARGB_16161616) {
                return AvifFrame.fromOwnedPixels(
                        planes.codedWidth(),
                        planes.codedHeight(),
                        planes.bitDepth(),
                        planes.chromaFormat(),
                        frameIndex,
                        ArgbOutput.toOpaqueArgbLongPixels(decodedPlanes, transform)
                );
            }
            throw new IllegalArgumentException("Unsupported pixel format: " + pixelFormat);
        } catch (UnsupportedOperationException exception) {
            throw unsupportedColorConversion(exception);
        }
    }

    /// Converts public raw planes back to the internal output-conversion representation.
    ///
    /// @param planes the public raw planes
    /// @return equivalent internal decoded planes
    private static DecodedSurface toDecodedPlanes(Av1DecodedPlanes planes) {
        return new DecodedSurface(
                planes.bitDepth().bits(),
                planes.chromaFormat(),
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                toDecodedPlane(planes.lumaPlane()),
                toNullableDecodedPlane(planes.chromaUPlane()),
                toNullableDecodedPlane(planes.chromaVPlane())
        );
    }

    /// Converts one public plane to the internal output-conversion representation.
    ///
    /// @param plane the public plane
    /// @return the equivalent internal plane
    private static PaddedPlane toDecodedPlane(Av1DecodedPlane plane) {
        return new PaddedPlane(plane.width(), plane.height(), plane.stride(), plane.sampleBuffer());
    }

    /// Converts one optional public plane to the internal output-conversion representation.
    ///
    /// @param plane the public plane, or `null`
    /// @return the equivalent internal plane, or `null`
    private static PaddedPlane toNullableDecodedPlane(Av1DecodedPlane plane) {
        return plane != null ? toDecodedPlane(plane) : null;
    }

    /// Normalizes decoded auxiliary-image planes for use as AVIF alpha.
    ///
    /// Color-coded auxiliary images are accepted for compatibility and only their luma plane is
    /// retained. Limited-range luma is converted to full range as permitted for legacy AVIF 1.0
    /// alpha images.
    ///
    /// @param planes the decoded auxiliary-image planes
    /// @param colorConfig the auxiliary image's AV1 color configuration
    /// @param expectedBitDepth the associated master-image bit depth
    /// @param label the diagnostic alpha source label
    /// @return monochrome, full-range alpha planes
    /// @throws AvifDecodeException if the alpha bit depth differs from the master image
    static Av1DecodedPlanes normalizeAlphaPlanes(
            Av1DecodedPlanes planes,
            Av1ColorConfig colorConfig,
            AvifBitDepth expectedBitDepth,
            String label
    ) throws AvifDecodeException {
        if (colorConfig.bitDepth() != expectedBitDepth) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    label + " bit depth differs from its master image: "
                            + colorConfig.bitDepth().bits() + " != " + expectedBitDepth.bits(),
                    null
            );
        }
        Av1DecodedPlane lumaPlane = colorConfig.colorRange()
                ? planes.lumaPlane()
                : limitedToFullAlphaPlane(planes.lumaPlane(), colorConfig.bitDepth());
        return new Av1DecodedPlanes(
                planes.bitDepth(),
                Av1ChromaFormat.MONOCHROME,
                planes.codedWidth(),
                planes.codedHeight(),
                planes.renderWidth(),
                planes.renderHeight(),
                lumaPlane,
                null,
                null
        );
    }

    /// Converts one limited-range alpha luma plane to full range.
    ///
    /// @param plane the limited-range luma plane
    /// @param bitDepth the decoded AV1 bit depth
    /// @return a compact full-range alpha plane
    private static Av1DecodedPlane limitedToFullAlphaPlane(Av1DecodedPlane plane, AvifBitDepth bitDepth) {
        int width = plane.width();
        int height = plane.height();
        int rangeShift = bitDepth.bits() - 8;
        int limitedMinimum = 16 << rangeShift;
        int limitedMaximum = 235 << rangeShift;
        int limitedRange = limitedMaximum - limitedMinimum;
        int fullMaximum = bitDepth.maxSampleValue();
        ShortBuffer source = plane.sampleBuffer();
        short[] samples = new short[width * height];
        for (int y = 0; y < height; y++) {
            int sourceOffset = y * plane.stride();
            int destinationOffset = y * width;
            for (int x = 0; x < width; x++) {
                int sample = source.get(sourceOffset + x) & 0xFFFF;
                int fullRangeSample = ((sample - limitedMinimum) * fullMaximum + limitedRange / 2)
                        / limitedRange;
                samples[destinationOffset + x] = (short) Math.max(
                        0,
                        Math.min(fullMaximum, fullRangeSample)
                );
            }
        }
        return new Av1DecodedPlane(width, height, width, ShortBuffer.wrap(samples).asReadOnlyBuffer());
    }

    /// Returns whether image metadata contains one auxiliary image type.
    ///
    /// @param info the image metadata
    /// @param auxiliaryType the auxiliary image type
    /// @return whether the auxiliary type is present
    private static boolean hasAuxiliaryType(AvifImageInfo info, String auxiliaryType) {
        for (String type : info.auxiliaryImageTypes()) {
            if (auxiliaryType.equals(type)) {
                return true;
            }
        }
        return false;
    }

    /// Composes decoded grid cell raw planes into one canvas.
    ///
    /// @param cellPlanes the decoded cell planes in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output luma width
    /// @param outputHeight the output luma height
    /// @return composed raw color planes
    private static Av1DecodedPlanes composeGridRawColorPlanes(
            Av1DecodedPlanes[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        if (cellPlanes.length != rows * columns) {
            throw new IllegalArgumentException("grid cell count does not match rows * columns");
        }
        Av1DecodedPlanes firstCell = cellPlanes[0];
        AvifBitDepth bitDepth = firstCell.bitDepth();
        Av1ChromaFormat chromaFormat = gridRawPlaneChromaFormat(cellPlanes);
        validateGridRawPlaneCells(cellPlanes, bitDepth, chromaFormat);

        Av1DecodedPlane lumaPlane = composeGridPlane(lumaPlanes(cellPlanes), rows, columns, outputWidth, outputHeight);
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            return new Av1DecodedPlanes(bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                    lumaPlane, null, null);
        }

        int chromaWidth = expectedChromaWidth(chromaFormat, outputWidth);
        int chromaHeight = expectedChromaHeight(chromaFormat, outputHeight);
        Av1DecodedPlane chromaUPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, true),
                rows,
                columns,
                chromaWidth,
                chromaHeight
        );
        Av1DecodedPlane chromaVPlane = composeGridPlane(
                chromaPlanes(cellPlanes, chromaFormat, bitDepth, false),
                rows,
                columns,
                chromaWidth,
                chromaHeight
        );
        return new Av1DecodedPlanes(bitDepth, chromaFormat, outputWidth, outputHeight, outputWidth, outputHeight,
                lumaPlane, chromaUPlane, chromaVPlane);
    }

    /// Validates decoded grid-cell consistency and canvas coverage.
    ///
    /// @param cellPlanes the decoded cells in row-major order
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output luma width
    /// @param outputHeight the output luma height
    /// @param label the diagnostic image label
    /// @throws AvifDecodeException if the cells or canvas violate grid requirements
    private static void validateGridGeometry(
            Av1DecodedPlanes[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight,
            String label
    ) throws AvifDecodeException {
        if (cellPlanes.length != rows * columns || cellPlanes.length == 0) {
            throw invalidImageGrid(label + " grid cell count does not match its row and column counts");
        }
        Av1DecodedPlanes firstCell = cellPlanes[0];
        int tileWidth = firstCell.codedWidth();
        int tileHeight = firstCell.codedHeight();
        for (int cellIndex = 1; cellIndex < cellPlanes.length; cellIndex++) {
            Av1DecodedPlanes cell = cellPlanes[cellIndex];
            if (cell.codedWidth() != tileWidth || cell.codedHeight() != tileHeight) {
                throw invalidImageGrid(
                        label + " grid cell " + cellIndex + " dimensions "
                                + cell.codedWidth() + "x" + cell.codedHeight()
                                + " differ from the first cell " + tileWidth + "x" + tileHeight
                );
            }
        }
        if (tileWidth < 64 || tileHeight < 64) {
            throw invalidImageGrid(label + " grid cells must be at least 64x64 samples");
        }
        if ((long) tileWidth * columns < outputWidth || (long) tileHeight * rows < outputHeight) {
            throw invalidImageGrid(label + " grid cells do not cover the output canvas");
        }
        if ((long) tileWidth * (columns - 1) >= outputWidth
                || (long) tileHeight * (rows - 1) >= outputHeight) {
            throw invalidImageGrid(label + " rightmost or bottommost grid cells do not overlap the output canvas");
        }

        Av1ChromaFormat chromaFormat = gridRawPlaneChromaFormat(cellPlanes);
        if ((chromaFormat == Av1ChromaFormat.YUV420 || chromaFormat == Av1ChromaFormat.YUV422)
                && ((tileWidth & 1) != 0 || (outputWidth & 1) != 0)) {
            throw invalidImageGrid(label + " horizontally subsampled grid widths must be even");
        }
        if (chromaFormat == Av1ChromaFormat.YUV420
                && ((tileHeight & 1) != 0 || (outputHeight & 1) != 0)) {
            throw invalidImageGrid(label + " vertically subsampled grid heights must be even");
        }
        if ((long) outputWidth * outputHeight > Integer.MAX_VALUE) {
            throw unsupported(label + " grid output contains too many samples for a Java array");
        }
    }

    /// Returns the common raw grid chroma format, allowing monochrome cells in a chroma grid.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return the grid chroma format
    private static Av1ChromaFormat gridRawPlaneChromaFormat(Av1DecodedPlanes[] cellPlanes) {
        Av1ChromaFormat chromaFormat = Av1ChromaFormat.MONOCHROME;
        for (Av1DecodedPlanes cellPlane : cellPlanes) {
            Av1ChromaFormat cellChromaFormat = cellPlane.chromaFormat();
            if (cellChromaFormat == Av1ChromaFormat.MONOCHROME) {
                continue;
            }
            if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
                chromaFormat = cellChromaFormat;
            } else if (cellChromaFormat != chromaFormat) {
                throw new IllegalArgumentException("grid cell chroma format mismatch");
            }
        }
        return chromaFormat;
    }

    /// Validates that all grid cells share the same raw-plane format.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @param bitDepth the expected bit depth
    /// @param chromaFormat the expected output chroma format
    private static void validateGridRawPlaneCells(
            Av1DecodedPlanes[] cellPlanes,
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat
    ) {
        for (Av1DecodedPlanes cellPlane : cellPlanes) {
            if (cellPlane.bitDepth() != bitDepth) {
                throw new IllegalArgumentException("grid cell bit depth mismatch");
            }
            Av1ChromaFormat cellChromaFormat = cellPlane.chromaFormat();
            if (cellChromaFormat != chromaFormat && cellChromaFormat != Av1ChromaFormat.MONOCHROME) {
                throw new IllegalArgumentException("grid cell chroma format mismatch");
            }
        }
    }

    /// Returns luma planes for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @return luma planes in row-major order
    private static Av1DecodedPlane[] lumaPlanes(Av1DecodedPlanes[] cellPlanes) {
        Av1DecodedPlane[] result = new Av1DecodedPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            result[i] = cellPlanes[i].lumaPlane();
        }
        return result;
    }

    /// Returns one chroma plane for all grid cells.
    ///
    /// @param cellPlanes the decoded cell planes
    /// @param chromaFormat the output chroma format
    /// @param bitDepth the output bit depth
    /// @param chromaU whether to return U instead of V
    /// @return chroma planes in row-major order
    private static Av1DecodedPlane[] chromaPlanes(
            Av1DecodedPlanes[] cellPlanes,
            Av1ChromaFormat chromaFormat,
            AvifBitDepth bitDepth,
            boolean chromaU
    ) {
        Av1DecodedPlane[] result = new Av1DecodedPlane[cellPlanes.length];
        for (int i = 0; i < cellPlanes.length; i++) {
            Av1DecodedPlanes cellPlane = cellPlanes[i];
            Av1DecodedPlane plane = chromaU ? cellPlane.chromaUPlane() : cellPlane.chromaVPlane();
            result[i] = plane != null ? plane : neutralChromaPlane(cellPlane, chromaFormat, bitDepth);
        }
        return result;
    }

    /// Creates a neutral chroma plane for a monochrome grid cell.
    ///
    /// @param cellPlane the monochrome cell planes
    /// @param chromaFormat the output chroma format
    /// @param bitDepth the output bit depth
    /// @return a neutral chroma plane matching the cell's target chroma dimensions
    private static Av1DecodedPlane neutralChromaPlane(
            Av1DecodedPlanes cellPlane,
            Av1ChromaFormat chromaFormat,
            AvifBitDepth bitDepth
    ) {
        int width = expectedChromaWidth(chromaFormat, cellPlane.codedWidth());
        int height = expectedChromaHeight(chromaFormat, cellPlane.codedHeight());
        short[] samples = new short[width * height];
        Arrays.fill(samples, (short) (1 << (bitDepth.bits() - 1)));
        return new Av1DecodedPlane(width, height, width, samples);
    }

    /// Composes one plane from row-major grid cell planes.
    ///
    /// @param cellPlanes the cell planes
    /// @param rows the grid row count
    /// @param columns the grid column count
    /// @param outputWidth the output plane width
    /// @param outputHeight the output plane height
    /// @return composed plane
    private static Av1DecodedPlane composeGridPlane(
            Av1DecodedPlane[] cellPlanes,
            int rows,
            int columns,
            int outputWidth,
            int outputHeight
    ) {
        short[] samples = new short[Math.multiplyExact(outputWidth, outputHeight)];
        int cellWidth = cellPlanes[0].width();
        int cellHeight = cellPlanes[0].height();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int cellIndex = row * columns + col;
                Av1DecodedPlane cellPlane = cellPlanes[cellIndex];
                if (cellPlane.width() != cellWidth || cellPlane.height() != cellHeight) {
                    throw new IllegalArgumentException("grid cell plane dimensions mismatch");
                }
                copyGridPlaneCell(
                        samples,
                        outputWidth,
                        outputHeight,
                        cellPlane,
                        col * cellWidth,
                        row * cellHeight
                );
            }
        }
        return new Av1DecodedPlane(outputWidth, outputHeight, outputWidth, samples);
    }

    /// Copies one grid cell plane into the destination plane canvas.
    ///
    /// @param destination the destination samples
    /// @param outputWidth the output plane width
    /// @param outputHeight the output plane height
    /// @param cellPlane the source cell plane
    /// @param xOffset the destination x offset
    /// @param yOffset the destination y offset
    private static void copyGridPlaneCell(
            short[] destination,
            int outputWidth,
            int outputHeight,
            Av1DecodedPlane cellPlane,
            int xOffset,
            int yOffset
    ) {
        if (xOffset >= outputWidth || yOffset >= outputHeight) {
            return;
        }
        int copyWidth = Math.min(cellPlane.width(), outputWidth - xOffset);
        int copyHeight = Math.min(cellPlane.height(), outputHeight - yOffset);
        for (int y = 0; y < copyHeight; y++) {
            int destinationBase = (yOffset + y) * outputWidth + xOffset;
            for (int x = 0; x < copyWidth; x++) {
                destination[destinationBase + x] = (short) cellPlane.sample(x, y);
            }
        }
    }

    /// Returns the expected chroma width for one chroma format.
    ///
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width
    private static int expectedChromaWidth(Av1ChromaFormat chromaFormat, int codedWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (codedWidth + 1) / 2;
            case YUV444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one chroma format.
    ///
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height
    private static int expectedChromaHeight(Av1ChromaFormat chromaFormat, int codedHeight) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420 -> (codedHeight + 1) / 2;
            case YUV422, YUV444 -> codedHeight;
        };
    }

    /// Decodes one image-sequence frame without mutating the persistent sequential AV1 decoder.
    ///
    /// @param frameIndex the zero-based frame index
    /// @return the decoded frame
    /// @throws IOException if decoding fails
    private AvifFrame readSequenceFrameRandomAccess(int frameIndex) throws IOException {
        AvifPayload [] payloads = sequenceSamplePayloads;
        if (payloads == null || frameIndex >= payloads.length) {
            throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
        }
        try {
            Av1DecodedOutput requiredOutput = indexedSequenceCursor(payloads, "Sequence").read(frameIndex);
            AvifFrame rawFrame = adaptRawPlanes(
                    requiredOutput.planes(),
                    requiredOutput.colorConfig(),
                    container.info().colorInfo(),
                    frameIndex,
                    factory.outputPixelFormat()
            );
            return combineFrameWithSequenceAlphaRandomAccess(rawFrame, frameIndex);
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Reads all remaining decoded frames from the current sequential position.
    ///
    /// @return all remaining decoded frames
    /// @throws IOException if a frame cannot be decoded
    public List<AvifFrame> readAllFrames() throws IOException {
        ensureOpen();
        ArrayList<AvifFrame> frames = new ArrayList<>();
        while (true) {
            AvifFrame frame = readFrame();
            if (frame == null) {
                return List.copyOf(frames);
            }
            frames.add(frame);
        }
    }

    /// Closes this reader and its owned container resources.
    ///
    /// Repeated calls have no effect. A borrowed input stream or readable channel remains open.
    /// The reader is closed even if releasing an AV1 decoder or owned file handle fails. When
    /// multiple releases fail, later failures are suppressed on the first.
    ///
    /// @throws IOException if an owned decoder or source cannot be released
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (sequenceAv1Decoder != null) {
            try {
                sequenceAv1Decoder.close();
            } catch (IOException exception) {
                failure = exception;
            }
            sequenceAv1Decoder = null;
        }
        if (sequenceAlphaAv1Decoder != null) {
            try {
                sequenceAlphaAv1Decoder.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            sequenceAlphaAv1Decoder = null;
        }
        failure = closeSequenceCursor(indexedColorSequenceCursor, failure);
        indexedColorSequenceCursor = null;
        failure = closeSequenceCursor(indexedAlphaSequenceCursor, failure);
        indexedAlphaSequenceCursor = null;
        failure = closeSequenceCursor(indexedDepthSequenceCursor, failure);
        indexedDepthSequenceCursor = null;
        try {
            source.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Closes one indexed sequence cursor and combines any failure with an earlier failure.
    ///
    /// @param cursor the cursor to close, or `null`
    /// @param failure the earlier close failure, or `null`
    /// @return the first close failure with later failures suppressed, or `null`
    private static IOException closeSequenceCursor(
            SequenceDecoderCursor cursor,
            IOException failure
    ) {
        if (cursor == null) {
            return failure;
        }
        try {
            cursor.close();
        } catch (IOException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Ensures that this reader is open.
    ///
    /// @throws AvifDecodeException if this reader is closed
    private void ensureOpen() throws AvifDecodeException {
        if (closed) {
            throw new AvifDecodeException(AvifErrorCode.CLOSED, "AvifImageReader is closed", null);
        }
    }

    /// Ensures that this reader is open and backed by a seekable source.
    ///
    /// @param operation the public operation requiring arbitrary source access
    /// @throws AvifDecodeException if this reader is closed or its source is forward-only
    private void ensureRandomAccess(String operation) throws AvifDecodeException {
        ensureOpen();
        if (!source.isSeekable()) {
            throw new AvifDecodeException(
                    AvifErrorCode.SEEKABLE_SOURCE_REQUIRED,
                    operation + " requires Path, byte[], or ByteBuffer input",
                    null
            );
        }
    }

    /// Applies container-level transforms (clap, irot, imir) to a decoded frame.
    ///
    /// @param frame the raw decoded frame
    /// @return the transformed frame, or the same frame when no transforms are present
    private AvifFrame applyTransforms(AvifFrame frame) {
        AvifImageInfo info = container.info();
        AvifImageTransformInfo transformInfo = info.transformInfo();
        if (transformInfo == null) {
            return frame;
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            int[] pixels = intBufferToArray(frame.intPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (transformInfo.hasCleanApertureCrop()) {
                int[] cropped = applyClapCropInt(pixels, width,
                        transformInfo.cleanApertureCropX(), transformInfo.cleanApertureCropY(),
                        transformInfo.cleanApertureCropWidth(), transformInfo.cleanApertureCropHeight());
                pixels = cropped;
                width = transformInfo.cleanApertureCropWidth();
                height = transformInfo.cleanApertureCropHeight();
            }

            int rotation = transformInfo.rotationCode();
            if (rotation > 0) {
                int[] rotated = applyRotationInt(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = transformInfo.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorInt(pixels, width, height, mirror);
            }

            return AvifFrame.fromOwnedPixels(width, height, frame.bitDepth(),
                    frame.chromaFormat(), frame.frameIndex(), pixels);
        }
        if (frame.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            long[] pixels = longBufferToArray(frame.longPixelBuffer());
            int width = frame.width();
            int height = frame.height();

            if (transformInfo.hasCleanApertureCrop()) {
                long[] cropped = applyClapCropLong(pixels, width,
                        transformInfo.cleanApertureCropX(), transformInfo.cleanApertureCropY(),
                        transformInfo.cleanApertureCropWidth(), transformInfo.cleanApertureCropHeight());
                pixels = cropped;
                width = transformInfo.cleanApertureCropWidth();
                height = transformInfo.cleanApertureCropHeight();
            }

            int rotation = transformInfo.rotationCode();
            if (rotation > 0) {
                long[] rotated = applyRotationLong(pixels, width, height, rotation);
                pixels = rotated;
                if (rotation == 1 || rotation == 3) {
                    int tmp = width;
                    width = height;
                    height = tmp;
                }
            }

            int mirror = transformInfo.mirrorAxis();
            if (mirror >= 0) {
                pixels = applyMirrorLong(pixels, width, height, mirror);
            }

            return AvifFrame.fromOwnedPixels(width, height, frame.bitDepth(),
                    frame.chromaFormat(), frame.frameIndex(), pixels);
        }
        return frame;
    }

    /// Applies a clean-aperture crop to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param srcWidth the source width
    /// @param cropX the crop x offset
    /// @param cropY the crop y offset
    /// @param cropWidth the crop width
    /// @param cropHeight the crop height
    /// @return the cropped pixel array
    private static int[] applyClapCropInt(
            int[] pixels, int srcWidth,
            int cropX, int cropY, int cropWidth, int cropHeight
    ) {
        int[] result = new int[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            int srcRow = (cropY + y) * srcWidth + cropX;
            int destRow = y * cropWidth;
            System.arraycopy(pixels, srcRow, result, destRow, cropWidth);
        }
        return result;
    }

    /// Applies a clean-aperture crop to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param srcWidth the source width
    /// @param cropX the crop x offset
    /// @param cropY the crop y offset
    /// @param cropWidth the crop width
    /// @param cropHeight the crop height
    /// @return the cropped pixel array
    private static long[] applyClapCropLong(
            long[] pixels, int srcWidth,
            int cropX, int cropY, int cropWidth, int cropHeight
    ) {
        long[] result = new long[cropWidth * cropHeight];
        for (int y = 0; y < cropHeight; y++) {
            int srcRow = (cropY + y) * srcWidth + cropX;
            int destRow = y * cropWidth;
            System.arraycopy(pixels, srcRow, result, destRow, cropWidth);
        }
        return result;
    }

    /// Applies rotation to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
    /// @return the rotated pixel array
    private static int[] applyRotationInt(int[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
                int newWidth = height;
                int newHeight = width;
                int[] result = new int[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[x * width + (width - 1 - y)];
                    }
                }
                yield result;
            }
            case 2 -> {
                int[] result = new int[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        result[y * width + x] = pixels[(height - 1 - y) * width + (width - 1 - x)];
                    }
                }
                yield result;
            }
            case 3 -> {
                int newWidth = height;
                int newHeight = width;
                int[] result = new int[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
                    }
                }
                yield result;
            }
            default -> pixels;
        };
    }

    /// Applies rotation to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param rotation the AVIF `irot` rotation code (1=90° CCW, 2=180°, 3=90° CW)
    /// @return the rotated pixel array
    private static long[] applyRotationLong(long[] pixels, int width, int height, int rotation) {
        return switch (rotation) {
            case 1 -> {
                int newWidth = height;
                int newHeight = width;
                long[] result = new long[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[x * width + (width - 1 - y)];
                    }
                }
                yield result;
            }
            case 2 -> {
                long[] result = new long[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        result[y * width + x] = pixels[(height - 1 - y) * width + (width - 1 - x)];
                    }
                }
                yield result;
            }
            case 3 -> {
                int newWidth = height;
                int newHeight = width;
                long[] result = new long[newWidth * newHeight];
                for (int y = 0; y < newHeight; y++) {
                    for (int x = 0; x < newWidth; x++) {
                        result[y * newWidth + x] = pixels[(height - 1 - x) * width + y];
                    }
                }
                yield result;
            }
            default -> pixels;
        };
    }

    /// Applies mirroring to 8-bit pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
    /// @return the mirrored pixel array
    private static int[] applyMirrorInt(int[] pixels, int width, int height, int axis) {
        int[] result = new int[width * height];
        if (axis == 0) {
            for (int y = 0; y < height; y++) {
                int srcRow = (height - 1 - y) * width;
                int destRow = y * width;
                System.arraycopy(pixels, srcRow, result, destRow, width);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowBase = y * width;
                for (int x = 0; x < width; x++) {
                    result[rowBase + x] = pixels[rowBase + (width - 1 - x)];
                }
            }
        }
        return result;
    }

    /// Applies mirroring to 16-bit-per-channel pixels.
    ///
    /// @param pixels the source pixel array
    /// @param width the source width
    /// @param height the source height
    /// @param axis the AVIF `imir` mirror axis (0=horizontal axis, 1=vertical axis)
    /// @return the mirrored pixel array
    private static long[] applyMirrorLong(long[] pixels, int width, int height, int axis) {
        long[] result = new long[width * height];
        if (axis == 0) {
            for (int y = 0; y < height; y++) {
                int srcRow = (height - 1 - y) * width;
                int destRow = y * width;
                System.arraycopy(pixels, srcRow, result, destRow, width);
            }
        } else {
            for (int y = 0; y < height; y++) {
                int rowBase = y * width;
                for (int x = 0; x < width; x++) {
                    result[rowBase + x] = pixels[rowBase + (width - 1 - x)];
                }
            }
        }
        return result;
    }

    /// Combines a sequentially read sequence frame with its matching alpha sample when present.
    ///
    /// @param colorFrame the decoded color frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the color frame with sequence alpha applied, or the original frame
    /// @throws IOException if the alpha sample cannot be decoded
    private AvifFrame combineFrameWithSequenceAlphaSequential(AvifFrame colorFrame, int frameIndex) throws IOException {
        AvifPayload [] alphaPayloads = sequenceAlphaSamplePayloads;
        if (alphaPayloads == null) {
            return colorFrame;
        }
        if (frameIndex != sequenceAlphaAv1FrameIndex && sequenceAlphaAv1Decoder != null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequential AVIF alpha reader is out of sync at frame " + frameIndex,
                    null
            );
        }
        if (sequenceAlphaAv1Decoder == null) {
            sequenceAlphaAv1Decoder = Av1Decoder.open(
                    AvifPayload.openInput(alphaPayloads),
                    factory.av1DecoderConfig()
            );
            sequenceAlphaAv1FrameIndex = 0;
        }
        while (sequenceAlphaAv1FrameIndex < frameIndex) {
            Av1DecodedOutput skipped = sequenceAlphaAv1Decoder.readOutput();
            if (skipped == null) {
                throw new AvifDecodeException(
                        AvifErrorCode.AV1_DECODE_FAILED,
                        "Sequence alpha ended before frame " + frameIndex,
                        null
                );
            }
            sequenceAlphaAv1FrameIndex++;
        }
        Av1DecodedOutput alphaOutput = sequenceAlphaAv1Decoder.readOutput();
        if (alphaOutput == null) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Sequence alpha produced no frame: " + frameIndex,
                    null
            );
        }
        sequenceAlphaAv1FrameIndex++;
        Av1DecodedPlanes alphaPlanes = normalizeAlphaPlanes(
                alphaOutput.planes(),
                alphaOutput.colorConfig(),
                container.info().bitDepth(),
                "Sequence alpha frame " + frameIndex
        );
        return combineFrameWithDecodedAlpha(
                colorFrame,
                alphaPlanes,
                frameIndex
        );
    }

    /// Combines a randomly accessed sequence frame with its matching alpha sample when present.
    ///
    /// @param colorFrame the decoded color frame
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the color frame with sequence alpha applied, or the original frame
    /// @throws IOException if the alpha sample cannot be decoded
    private AvifFrame combineFrameWithSequenceAlphaRandomAccess(AvifFrame colorFrame, int frameIndex)
            throws IOException {
        AvifPayload [] alphaPayloads = sequenceAlphaSamplePayloads;
        if (alphaPayloads == null) {
            return colorFrame;
        }
        try {
            Av1DecodedOutput requiredOutput = indexedSequenceCursor(alphaPayloads, "Sequence alpha").read(frameIndex);
            Av1DecodedPlanes alphaPlanes = normalizeAlphaPlanes(
                    requiredOutput.planes(),
                    requiredOutput.colorConfig(),
                    container.info().bitDepth(),
                    "Sequence alpha frame " + frameIndex
            );
            return combineFrameWithDecodedAlpha(
                    colorFrame,
                    alphaPlanes,
                    frameIndex
            );
        } catch (AvifDecodeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw wrapAv1DecodeFailure(exception);
        }
    }

    /// Combines decoded alpha planes with a color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaPlanes the decoded alpha planes
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined AVIF frame
    /// @throws AvifDecodeException if the alpha dimensions differ
    private AvifFrame combineFrameWithDecodedAlpha(
            AvifFrame colorFrame,
            Av1DecodedPlanes alphaPlanes,
            int frameIndex
    ) throws AvifDecodeException {
        return combineFrameWithDecodedAlpha(
                colorFrame,
                alphaPlanes,
                frameIndex,
                container.info().alphaPremultiplied()
        );
    }

    /// Combines decoded alpha planes with a color frame.
    ///
    /// @param colorFrame the decoded color frame
    /// @param alphaPlanes the decoded alpha planes
    /// @param frameIndex the zero-based AVIF frame index
    /// @param alphaPremultiplied whether color samples are premultiplied by alpha
    /// @return the combined AVIF frame
    /// @throws AvifDecodeException if the alpha dimensions differ
    private static AvifFrame combineFrameWithDecodedAlpha(
            AvifFrame colorFrame,
            Av1DecodedPlanes alphaPlanes,
            int frameIndex,
            boolean alphaPremultiplied
    ) throws AvifDecodeException {
        Av1DecodedPlanes checkedAlphaPlanes = validateDecodedAlphaPlanes(
                colorFrame.width(),
                colorFrame.height(),
                alphaPlanes
        );
        return combineFrameWithAlphaPlane(
                colorFrame,
                checkedAlphaPlanes,
                checkedAlphaPlanes.bitDepth(),
                frameIndex,
                alphaPremultiplied
        );
    }

    /// Validates decoded alpha planes before composition.
    ///
    /// @param expectedWidth the expected alpha width
    /// @param expectedHeight the expected alpha height
    /// @param alphaPlanes the decoded alpha planes
    /// @return the validated alpha planes
    /// @throws AvifDecodeException if the alpha planes are incompatible with the color frame
    private static Av1DecodedPlanes validateDecodedAlphaPlanes(
            int expectedWidth,
            int expectedHeight,
            Av1DecodedPlanes alphaPlanes
    ) throws AvifDecodeException {
        if (alphaPlanes.codedWidth() != expectedWidth || alphaPlanes.codedHeight() != expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Alpha with different decoded dimensions than master image",
                    null
            );
        }
        if (alphaPlanes.chromaFormat() != Av1ChromaFormat.MONOCHROME) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    "Decoded alpha planes are not monochrome",
                    null
            );
        }
        validateAlphaLumaPlane(alphaPlanes.lumaPlane(), expectedWidth, expectedHeight, "Alpha");
        return alphaPlanes;
    }

    /// Validates one alpha luma plane against the expected decoded dimensions.
    ///
    /// @param lumaPlane the decoded luma plane used as alpha
    /// @param expectedWidth the expected luma width
    /// @param expectedHeight the expected luma height
    /// @param label the diagnostic alpha source label
    /// @throws AvifDecodeException if the luma plane cannot cover the expected alpha image
    private static void validateAlphaLumaPlane(
            Av1DecodedPlane lumaPlane,
            int expectedWidth,
            int expectedHeight,
            String label
    ) throws AvifDecodeException {
        if (lumaPlane.width() < expectedWidth || lumaPlane.height() < expectedHeight) {
            throw new AvifDecodeException(
                    AvifErrorCode.AV1_DECODE_FAILED,
                    label + " luma plane is smaller than the decoded alpha frame",
                    null
            );
        }
    }

    /// Combines alpha from one raw luma plane into a color frame.
    ///
    /// @param color the decoded color frame
    /// @param alphaPlanes the decoded alpha planes
    /// @param alphaBitDepth the alpha plane bit depth
    /// @param frameIndex the zero-based AVIF frame index
    /// @return the combined frame
    private static AvifFrame combineFrameWithAlphaPlane(
            AvifFrame color,
            Av1DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        if (color.pixelFormat() == AvifPixelFormat.ARGB_8888) {
            return combineIntPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        if (color.pixelFormat() == AvifPixelFormat.ARGB_16161616) {
            return combineLongPlaneAlpha(color, alphaPlanes, alphaBitDepth, frameIndex, alphaPremultiplied);
        }
        throw new IllegalArgumentException("Unsupported alpha color frame pixel format: " + color.pixelFormat());
    }

    /// Combines alpha from raw luma plane into an 8-bit color frame.
    private static AvifFrame combineIntPlaneAlpha(
            AvifFrame color,
            Av1DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        IntBuffer colorPixels = color.intPixelBuffer();
        int width = color.width();
        int height = color.height();
        Av1DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = alphaBitDepth.maxSampleValue();
        int[] combined = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                int alpha8 = scaleSampleToByte(alphaSample, maxSample);
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x00FFFFFF) | (alpha8 << 24);
            }
        }
        if (alphaPremultiplied) {
            unpremultiplyIntPixels(combined);
        }
        return AvifFrame.fromOwnedPixels(
                width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined
        );
    }

    /// Combines alpha from raw luma plane into a 10/12-bit color frame.
    private static AvifFrame combineLongPlaneAlpha(
            AvifFrame color,
            Av1DecodedPlanes alphaPlanes,
            AvifBitDepth alphaBitDepth,
            int frameIndex,
            boolean alphaPremultiplied
    ) {
        LongBuffer colorPixels = color.longPixelBuffer();
        int width = color.width();
        int height = color.height();
        Av1DecodedPlane lumaPlane = alphaPlanes.lumaPlane();
        int maxSample = alphaBitDepth.maxSampleValue();
        long[] combined = new long[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alphaSample = lumaPlane.sample(x, y);
                long alpha16 = scaleSampleToWord(alphaSample, maxSample);
                int i = y * width + x;
                combined[i] = (colorPixels.get(i) & 0x0000FFFF_FFFFFFFFL) | ((alpha16 & 0xFFFFL) << 48);
            }
        }
        if (alphaPremultiplied) {
            unpremultiplyLongPixels(combined);
        }
        return AvifFrame.fromOwnedPixels(
                width, height, color.bitDepth(), color.chromaFormat(), frameIndex, combined
        );
    }

    /// Converts packed 8-bit ARGB pixels from premultiplied to straight alpha in place.
    ///
    /// @param pixels the pixels to convert
    private static void unpremultiplyIntPixels(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int alpha = pixel >>> 24;
            if (alpha == 0) {
                pixels[i] = 0;
                continue;
            }
            if (alpha == 255) {
                continue;
            }
            int red = unpremultiplyChannel((pixel >>> 16) & 0xFF, alpha, 255);
            int green = unpremultiplyChannel((pixel >>> 8) & 0xFF, alpha, 255);
            int blue = unpremultiplyChannel(pixel & 0xFF, alpha, 255);
            pixels[i] = (alpha << 24) | (red << 16) | (green << 8) | blue;
        }
    }

    /// Converts packed 16-bit-per-channel ARGB pixels from premultiplied to straight alpha in place.
    ///
    /// @param pixels the pixels to convert
    private static void unpremultiplyLongPixels(long[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            long pixel = pixels[i];
            int alpha = (int) ((pixel >>> 48) & 0xFFFFL);
            if (alpha == 0) {
                pixels[i] = 0L;
                continue;
            }
            if (alpha == 65_535) {
                continue;
            }
            long red = unpremultiplyChannel((int) ((pixel >>> 32) & 0xFFFFL), alpha, 65_535);
            long green = unpremultiplyChannel((int) ((pixel >>> 16) & 0xFFFFL), alpha, 65_535);
            long blue = unpremultiplyChannel((int) (pixel & 0xFFFFL), alpha, 65_535);
            pixels[i] = ((long) alpha << 48) | (red << 32) | (green << 16) | blue;
        }
    }

    /// Converts one premultiplied channel to straight alpha.
    ///
    /// @param sample the premultiplied color sample
    /// @param alpha the alpha sample
    /// @param maxSample the maximum channel sample value
    /// @return the straight-alpha channel sample
    private static int unpremultiplyChannel(int sample, int alpha, int maxSample) {
        long value = ((long) sample * maxSample + alpha / 2L) / alpha;
        return value > maxSample ? maxSample : (int) value;
    }

    /// Scales a decoded alpha sample to an unsigned 8-bit channel.
    ///
    /// @param sample the decoded alpha sample
    /// @param maxSample the maximum alpha sample value
    /// @return the scaled 8-bit alpha channel
    private static int scaleSampleToByte(int sample, int maxSample) {
        if (maxSample == 255) {
            return sample;
        }
        return (sample * 255 + maxSample / 2) / maxSample;
    }

    /// Scales a decoded alpha sample to an unsigned 16-bit channel.
    ///
    /// @param sample the decoded alpha sample
    /// @param maxSample the maximum alpha sample value
    /// @return the scaled 16-bit alpha channel
    private static long scaleSampleToWord(int sample, int maxSample) {
        if (maxSample == 65_535) {
            return sample;
        }
        return ((long) sample * 65_535 + maxSample / 2) / maxSample;
    }

    /// Maintains reusable AV1 decoder state for indexed reads of one image sequence.
    ///
    /// Forward reads continue from the current decoder position. Reading an earlier frame closes
    /// and reopens the decoder, then advances from the beginning. The most recently returned frame
    /// remains cached so repeated reads of the same index do not decode it again.
    
    private final class SequenceDecoderCursor implements AutoCloseable {
        /// The immutable sequence payload descriptors.
        private final AvifPayload [] payloads;
        /// The diagnostic sequence label.
        private final String label;
        /// The active AV1 decoder, or `null` before the first read or after closure.
        private Av1Decoder decoder;
        /// The index to be read next from the active decoder.
        private int nextFrameIndex;
        /// The most recently decoded frame index, or `-1` before any successful read.
        private int lastFrameIndex = -1;
        /// The most recently decoded output, or `null` before any successful read.
        private Av1DecodedOutput lastOutput;

        /// Creates an indexed decoder cursor.
        ///
        /// @param payloads the immutable sequence payload descriptors
        /// @param label the diagnostic sequence label
        private SequenceDecoderCursor(AvifPayload [] payloads, String label) {
            this.payloads = Objects.requireNonNull(payloads, "payloads");
            this.label = Objects.requireNonNull(label, "label");
        }

        /// Reads one indexed output, reusing forward decoder state when possible.
        ///
        /// @param frameIndex the zero-based frame index
        /// @return the decoded output
        /// @throws IOException if the decoder cannot be opened, advanced, or reset
        private Av1DecodedOutput read(int frameIndex) throws IOException {
            if (frameIndex < 0 || frameIndex >= payloads.length) {
                throw new IndexOutOfBoundsException("frameIndex out of range: " + frameIndex);
            }
            if (frameIndex == lastFrameIndex) {
                return Objects.requireNonNull(lastOutput, "lastOutput");
            }
            if (frameIndex < lastFrameIndex) {
                reset();
            }
            if (decoder == null) {
                decoder = Av1Decoder.open(
                        AvifPayload.openInput(payloads),
                        factory.av1DecoderConfig()
                );
            }
            try {
                while (nextFrameIndex <= frameIndex) {
                    Av1DecodedOutput output = decoder.readOutput();
                    if (output == null) {
                        throw new AvifDecodeException(
                                AvifErrorCode.AV1_DECODE_FAILED,
                                label + " ended before frame " + frameIndex,
                                null
                        );
                    }
                    lastOutput = output;
                    lastFrameIndex = nextFrameIndex;
                    nextFrameIndex++;
                }
                return Objects.requireNonNull(lastOutput, "lastOutput");
            } catch (IOException | RuntimeException exception) {
                try {
                    reset();
                } catch (IOException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }

        /// Resets this cursor to the beginning of its payload sequence.
        ///
        /// @throws IOException if the active decoder cannot be closed
        private void reset() throws IOException {
            Av1Decoder activeDecoder = decoder;
            decoder = null;
            nextFrameIndex = 0;
            lastFrameIndex = -1;
            lastOutput = null;
            if (activeDecoder != null) {
                activeDecoder.close();
            }
        }

        /// Closes the active decoder and clears cached output.
        ///
        /// Repeated calls have no effect.
        ///
        /// @throws IOException if the active decoder cannot be closed
        @Override
        public void close() throws IOException {
            reset();
        }
    }

    /// Raw decoded image planes and the active AV1 color configuration.
    ///
    /// @param planes the decoded raw planes
    /// @param colorConfig the active AV1 sequence-header color configuration
    
    private record DecodedRawImage(Av1DecodedPlanes planes, Av1ColorConfig colorConfig) {
    }

    /// Reconstructed Sample Transform planes and the primary input's AV1 color configuration.
    ///
    /// @param planes the reconstructed planes
    /// @param primaryColorConfig the primary input's AV1 sequence-header color configuration
    
    private record DecodedSampleTransform(
            Av1DecodedPlanes planes,
            Av1ColorConfig primaryColorConfig
    ) {
    }

    /// Creates an unsupported-feature exception.
    ///
    /// @param message the failure message
    /// @return an unsupported-feature exception
    private static AvifDecodeException unsupported(String message) {
        return new AvifDecodeException(AvifErrorCode.UNSUPPORTED_FEATURE, message, null);
    }

    /// Creates an invalid-grid exception.
    ///
    /// @param message the failure message
    /// @return an invalid-grid exception
    private static AvifDecodeException invalidImageGrid(String message) {
        return new AvifDecodeException(AvifErrorCode.INVALID_IMAGE_GRID, message, null);
    }

    /// Creates an unsupported-feature exception for an unavailable CICP color conversion.
    ///
    /// @param exception the unsupported color conversion failure
    /// @return an unsupported-feature exception retaining the underlying cause
    private static AvifDecodeException unsupportedColorConversion(UnsupportedOperationException exception) {
        return new AvifDecodeException(
                AvifErrorCode.UNSUPPORTED_FEATURE,
                exception.getMessage() != null
                        ? exception.getMessage()
                        : "AVIF output uses an unsupported color conversion",
                null,
                exception
        );
    }

    /// Wraps one low-level AV1 decoding failure while preserving caller-relevant classification.
    ///
    /// @param exception the low-level decoding failure
    /// @return the corresponding AVIF decoding failure
    private static AvifDecodeException wrapAv1DecodeFailure(IOException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof AvifDecodeException decodeException) {
                return decodeException;
            }
            cause = cause.getCause();
        }
        AvifErrorCode code = AvifErrorCode.AV1_DECODE_FAILED;
        if (exception instanceof Av1DecodeException decodeException) {
            code = switch (decodeException.code()) {
                case UNSUPPORTED_FEATURE -> AvifErrorCode.UNSUPPORTED_FEATURE;
                case FRAME_SIZE_LIMIT_EXCEEDED -> AvifErrorCode.FRAME_SIZE_LIMIT_EXCEEDED;
                default -> AvifErrorCode.AV1_DECODE_FAILED;
            };
        }
        return new AvifDecodeException(
                code,
                exception.getMessage() != null ? exception.getMessage() : "AV1 decoding failed",
                null,
                exception
        );
    }

    /// Copies remaining integers from a buffer into an array.
    ///
    /// @param buffer the source buffer
    /// @return an array containing the buffer's remaining integers
    private static int[] intBufferToArray(IntBuffer buffer) {
        IntBuffer source = buffer.slice();
        int[] result = new int[source.remaining()];
        source.get(result);
        return result;
    }

    /// Copies remaining longs from a buffer into an array.
    ///
    /// @param buffer the source buffer
    /// @return an array containing the buffer's remaining longs
    private static long[] longBufferToArray(LongBuffer buffer) {
        LongBuffer source = buffer.slice();
        long[] result = new long[source.remaining()];
        source.get(result);
        return result;
    }

}
