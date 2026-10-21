// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;

import pzopt.vendor.avif.Av1DecodedPlanes;
import pzopt.vendor.avif.AvifBitDepth;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;
import pzopt.vendor.avif.internal.av1.output.ArgbOutput;
import pzopt.vendor.avif.internal.av1.output.YuvToRgbTransform;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Objects;

/// Immutable decoded AV1 presentation output with YUV planes and frame metadata.
///
/// Plane access does not perform color conversion. [#toFrame()] converts the same output to packed
/// ARGB on first use and caches the resulting [Av1DecodedFrame].

public final class Av1DecodedOutput {
    /// The internal postprocessed presentation surface used for color conversion.
    private final DecodedSurface surface;
    /// The public visible presentation planes.
    private final Av1DecodedPlanes planes;
    /// The color configuration used to interpret the planes.
    private final Av1ColorConfig colorConfig;
    /// The frame category of the presented surface.
    private final Av1FrameType frameType;
    /// Whether the output is visible.
    private final boolean visible;
    /// The zero-based presentation index.
    private final long presentationIndex;
    /// The temporal-layer identifier of the presentation request.
    private final int temporalId;
    /// The spatial-layer identifier of the presentation request.
    private final int spatialId;
    /// The cached packed-pixel representation, or `null` before conversion.
    private Av1DecodedFrame frame;

    /// Creates a decoded output from validated decoder state.
    ///
    /// @param planes the postprocessed presentation planes
    /// @param colorConfig the color configuration used to interpret the planes
    /// @param frameType the frame category of the presented surface
    /// @param visible whether the output is visible
    /// @param presentationIndex the zero-based presentation index
    /// @param temporalId the temporal-layer identifier in `[0, 7]`
    /// @param spatialId the spatial-layer identifier in `[0, 3]`
    Av1DecodedOutput(
            DecodedSurface planes,
            Av1ColorConfig colorConfig,
            Av1FrameType frameType,
            boolean visible,
            long presentationIndex,
            int temporalId,
            int spatialId
    ) {
        if (presentationIndex < 0) {
            throw new IllegalArgumentException("presentationIndex < 0: " + presentationIndex);
        }
        if (temporalId < 0 || temporalId > 7) {
            throw new IllegalArgumentException("temporalId out of range: " + temporalId);
        }
        if (spatialId < 0 || spatialId > 3) {
            throw new IllegalArgumentException("spatialId out of range: " + spatialId);
        }
        this.surface = Objects.requireNonNull(planes, "planes");
        this.planes = planes.toDecodedPlanes();
        this.colorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.visible = visible;
        this.presentationIndex = presentationIndex;
        this.temporalId = temporalId;
        this.spatialId = spatialId;
    }

    /// Returns the postprocessed YUV presentation planes.
    ///
    /// @return the immutable presentation planes
    public Av1DecodedPlanes planes() {
        return planes;
    }

    /// Returns the color configuration used to interpret the planes.
    ///
    /// @return the AV1 color configuration
    public Av1ColorConfig colorConfig() {
        return colorConfig;
    }

    /// Returns the frame category of the presented surface.
    ///
    /// For `show_existing_frame`, this is the category of the referenced surface.
    ///
    /// @return the AV1 frame category
    public Av1FrameType frameType() {
        return frameType;
    }

    /// Returns whether the output is visible.
    ///
    /// @return whether the output is visible
    public boolean visible() {
        return visible;
    }

    /// Returns the zero-based presentation index.
    ///
    /// @return the presentation index
    public long presentationIndex() {
        return presentationIndex;
    }

    /// Returns the temporal-layer identifier of the presentation request.
    ///
    /// @return the temporal-layer identifier in `[0, 7]`
    public int temporalId() {
        return temporalId;
    }

    /// Returns the spatial-layer identifier of the presentation request.
    ///
    /// @return the spatial-layer identifier in `[0, 3]`
    public int spatialId() {
        return spatialId;
    }

    /// Converts this output to packed non-premultiplied ARGB pixels.
    ///
    /// The first call performs color conversion. Later calls return the same immutable frame
    /// instance.
    ///
    /// @return the packed-pixel frame
    /// @throws UnsupportedOperationException if the color configuration cannot be converted
    public Av1DecodedFrame toFrame() {
        Av1DecodedFrame result = frame;
        if (result == null) {
            YuvToRgbTransform transform = YuvToRgbTransform.fromColorConfig(colorConfig);
            result = switch (surface.bitDepth()) {
                case 8 -> new Av1DecodedFrame(
                        surface.codedWidth(),
                        surface.codedHeight(),
                        AvifBitDepth.EIGHT_BITS,
                        surface.chromaFormat(),
                        frameType,
                        visible,
                        presentationIndex,
                        temporalId,
                        spatialId,
                        IntBuffer.wrap(ArgbOutput.toOpaqueArgbPixels(surface, transform)).asReadOnlyBuffer()
                );
                case 10, 12 -> new Av1DecodedFrame(
                        surface.codedWidth(),
                        surface.codedHeight(),
                        AvifBitDepth.fromBits(surface.bitDepth()),
                        surface.chromaFormat(),
                        frameType,
                        visible,
                        presentationIndex,
                        temporalId,
                        spatialId,
                        LongBuffer.wrap(ArgbOutput.toOpaqueArgbLongPixels(surface, transform)).asReadOnlyBuffer()
                );
                default -> throw new IllegalArgumentException(
                        "Unsupported decoded bit depth: " + surface.bitDepth()
                );
            };
            frame = result;
        }
        return result;
    }
}
