// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif;


import java.util.Objects;

/// Immutable decoded AV1 image planes.
///
/// The render dimensions retain the AV1 display hint; they do not crop or resample the stored
/// planes. AVIF readers expose these planes before auxiliary alpha composition and item transforms
/// such as `clap`, `irot`, and `imir` are applied.

public final class Av1DecodedPlanes {
    /// The decoded sample bit depth.
    private final AvifBitDepth bitDepth;
    /// The decoded AV1 chroma sampling layout.
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
    private final Av1DecodedPlane lumaPlane;
    /// The decoded chroma U plane, or `null` for monochrome images.
    private final Av1DecodedPlane chromaUPlane;
    /// The decoded chroma V plane, or `null` for monochrome images.
    private final Av1DecodedPlane chromaVPlane;

    /// Creates decoded AV1 image planes.
    ///
    /// @param bitDepth the decoded sample bit depth
    /// @param chromaFormat the decoded AV1 chroma sampling layout
    /// @param codedWidth the stored luma width in samples
    /// @param codedHeight the stored luma height in samples
    /// @param renderWidth the presentation render width
    /// @param renderHeight the presentation render height
    /// @param lumaPlane the decoded luma plane
    /// @param chromaUPlane the decoded chroma U plane, or `null` for monochrome images
    /// @param chromaVPlane the decoded chroma V plane, or `null` for monochrome images
    public Av1DecodedPlanes(
            AvifBitDepth bitDepth,
            Av1ChromaFormat chromaFormat,
            int codedWidth,
            int codedHeight,
            int renderWidth,
            int renderHeight,
            Av1DecodedPlane lumaPlane,
            Av1DecodedPlane chromaUPlane,
            Av1DecodedPlane chromaVPlane
    ) {
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

        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.codedWidth = codedWidth;
        this.codedHeight = codedHeight;
        this.renderWidth = renderWidth;
        this.renderHeight = renderHeight;
        this.lumaPlane = Objects.requireNonNull(lumaPlane, "lumaPlane");
        this.chromaUPlane = chromaUPlane;
        this.chromaVPlane = chromaVPlane;
        validatePlanes();
    }

    /// Returns the decoded sample bit depth.
    ///
    /// @return the decoded sample bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns the decoded AV1 chroma sampling layout.
    ///
    /// @return the decoded AV1 chroma sampling layout
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
    public Av1DecodedPlane lumaPlane() {
        return lumaPlane;
    }

    /// Returns the decoded chroma U plane.
    ///
    /// @return the decoded chroma U plane, or `null` for monochrome images
    public Av1DecodedPlane chromaUPlane() {
        return chromaUPlane;
    }

    /// Returns the decoded chroma V plane.
    ///
    /// @return the decoded chroma V plane, or `null` for monochrome images
    public Av1DecodedPlane chromaVPlane() {
        return chromaVPlane;
    }

    /// Returns whether chroma planes are present.
    ///
    /// @return whether chroma planes are present
    public boolean hasChroma() {
        return chromaUPlane != null && chromaVPlane != null;
    }

    /// Validates the plane layout against the chroma format.
    private void validatePlanes() {
        if (lumaPlane.width() != codedWidth || lumaPlane.height() != codedHeight) {
            throw new IllegalArgumentException("lumaPlane dimensions do not match coded luma dimensions");
        }
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
}
