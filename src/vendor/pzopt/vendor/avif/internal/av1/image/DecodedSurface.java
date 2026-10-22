// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.image;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.AvifBitDepth;
import pzopt.vendor.avif.Av1DecodedPlane;
import pzopt.vendor.avif.Av1DecodedPlanes;

import java.util.Objects;

/// Immutable decoded-plane snapshot produced by AV1 decoding.
///
/// Stored planes contain the postprocessed presentation samples. The render dimensions are AV1
/// display hints and do not crop or resample the stored planes.

public final class DecodedSurface {
    /// The decoded bit depth.
    private final int bitDepth;

    /// The chroma layout of the decoded planes.
    private final Av1ChromaFormat chromaFormat;

    /// The stored luma width in samples.
    private final int codedWidth;

    /// The stored luma height in samples.
    private final int codedHeight;

    /// The presentation render width.
    private final int renderWidth;

    /// The presentation render height.
    private final int renderHeight;

    /// The decoded luma plane.
    private final PaddedPlane lumaPlane;

    /// The decoded chroma U plane, or `null` for monochrome output.
    private final PaddedPlane chromaUPlane;

    /// The decoded chroma V plane, or `null` for monochrome output.
    private final PaddedPlane chromaVPlane;

    /// Creates one immutable decoded-plane snapshot.
    ///
    /// @param bitDepth the decoded bit depth
    /// @param chromaFormat the chroma layout of the decoded planes
    /// @param codedWidth the stored luma width in samples
    /// @param codedHeight the stored luma height in samples
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane, or `null` for monochrome output
    /// @param chromaVPlane the decoded chroma V plane, or `null` for monochrome output
    public DecodedSurface(
            int bitDepth,
            Av1ChromaFormat chromaFormat,
            int codedWidth,
            int codedHeight,
            int renderWidth,
            int renderHeight,
            PaddedPlane lumaPlane,
            PaddedPlane chromaUPlane,
            PaddedPlane chromaVPlane
    ) {
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12 && bitDepth != 16) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        if (codedWidth <= 0) {
            throw new IllegalArgumentException("codedWidth <= 0: " + codedWidth);
        }
        if (codedHeight <= 0) {
            throw new IllegalArgumentException("codedHeight <= 0: " + codedHeight);
        }
        if (renderWidth <= 0) {
            throw new IllegalArgumentException("renderWidth <= 0: " + renderWidth);
        }
        if (renderHeight <= 0) {
            throw new IllegalArgumentException("renderHeight <= 0: " + renderHeight);
        }

        this.bitDepth = bitDepth;
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.lumaPlane = Objects.requireNonNull(lumaPlane, "lumaPlane");
        this.chromaUPlane = chromaUPlane;
        this.chromaVPlane = chromaVPlane;

        if (lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight) {
            throw new IllegalArgumentException("lumaPlane dimensions do not match coded luma dimensions");
        }

        validateChromaPlanes();
    }

    /// Returns the decoded bit depth.
    ///
    /// @return the decoded bit depth
    public int bitDepth() {
        return bitDepth;
    }

    /// Returns the chroma layout of the decoded planes.
    ///
    /// @return the chroma layout of the decoded planes
    public Av1ChromaFormat chromaFormat() {
        return chromaFormat;
    }

    /// Returns the stored luma width in samples.
    ///
    /// @return the stored luma width in samples
    public int codedWidth() {
        return codedWidth;
    }

    /// Returns the stored luma height in samples.
    ///
    /// @return the stored luma height in samples
    public int codedHeight() {
        return codedHeight;
    }

    /// Returns the presentation render width.
    ///
    /// @return the presentation render width
    public int renderWidth() {
        return renderWidth;
    }

    /// Returns the presentation render height.
    ///
    /// @return the presentation render height
    public int renderHeight() {
        return renderHeight;
    }

    /// Returns the decoded luma plane.
    ///
    /// @return the decoded luma plane
    public PaddedPlane lumaPlane() {
        return lumaPlane;
    }

    /// Returns the decoded chroma U plane, or `null` for monochrome output.
    ///
    /// @return the decoded chroma U plane, or `null` for monochrome output
    public PaddedPlane chromaUPlane() {
        return chromaUPlane;
    }

    /// Returns the decoded chroma V plane, or `null` for monochrome output.
    ///
    /// @return the decoded chroma V plane, or `null` for monochrome output
    public PaddedPlane chromaVPlane() {
        return chromaVPlane;
    }

    /// Returns whether this decoded snapshot contains chroma planes.
    ///
    /// @return whether this decoded snapshot contains chroma planes
    public boolean hasChroma() {
        return chromaUPlane != null && chromaVPlane != null;
    }

    /// Creates the public visible-plane view of this internal padded surface.
    ///
    /// The returned plane buffers share immutable sample storage with this surface but exclude
    /// internal rows below the visible plane height.
    ///
    /// @return the public decoded planes
    public Av1DecodedPlanes toDecodedPlanes() {
        return new Av1DecodedPlanes(
                AvifBitDepth.fromBits(bitDepth),
                chromaFormat,
                codedWidth,
                codedHeight,
                renderWidth,
                renderHeight,
                toDecodedPlane(lumaPlane),
                toNullableDecodedPlane(chromaUPlane),
                toNullableDecodedPlane(chromaVPlane)
        );
    }

    /// Creates a public visible plane over one internal padded plane.
    ///
    /// @param plane the internal padded plane
    /// @return the public visible plane
    private static Av1DecodedPlane toDecodedPlane(PaddedPlane plane) {
        return new Av1DecodedPlane(plane.width(), plane.height(), plane.stride(), plane.sampleBuffer());
    }

    /// Creates a public visible plane over one optional internal padded plane.
    ///
    /// @param plane the internal padded plane, or `null`
    /// @return the public visible plane, or `null`
    private static Av1DecodedPlane toNullableDecodedPlane(PaddedPlane plane) {
        return plane == null ? null : toDecodedPlane(plane);
    }

    /// Validates the stored chroma-plane arrangement against the chroma format.
    private void validateChromaPlanes() {
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            if (chromaUPlane != null || chromaVPlane != null) {
                throw new IllegalArgumentException("MONOCHROME output must not carry chroma planes");
            }
            return;
        }

        if (chromaUPlane == null || chromaVPlane == null) {
            throw new IllegalArgumentException("Chroma planes are required for " + chromaFormat);
        }

        int expectedWidth = expectedChromaWidth(chromaFormat, codedWidth);
        int expectedHeight = expectedChromaHeight(chromaFormat, codedHeight);
        if (chromaUPlane.width() != expectedWidth || chromaUPlane.height() != expectedHeight) {
            throw new IllegalArgumentException("chromaUPlane dimensions do not match chroma format");
        }
        if (chromaVPlane.width() != expectedWidth || chromaVPlane.height() != expectedHeight) {
            throw new IllegalArgumentException("chromaVPlane dimensions do not match chroma format");
        }
    }

    /// Returns the expected chroma width for one chroma format.
    ///
    /// @param chromaFormat the chroma layout of the decoded planes
    /// @param codedWidth the coded luma width in samples
    /// @return the expected chroma width for one chroma format
    private static int expectedChromaWidth(Av1ChromaFormat chromaFormat, int codedWidth) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> (codedWidth + 1) / 2;
            case YUV444 -> codedWidth;
        };
    }

    /// Returns the expected chroma height for one chroma format.
    ///
    /// @param chromaFormat the chroma layout of the decoded planes
    /// @param codedHeight the coded luma height in samples
    /// @return the expected chroma height for one chroma format
    private static int expectedChromaHeight(Av1ChromaFormat chromaFormat, int codedHeight) {
        return switch (chromaFormat) {
            case MONOCHROME -> 0;
            case YUV420 -> (codedHeight + 1) / 2;
            case YUV422, YUV444 -> codedHeight;
        };
    }
}
