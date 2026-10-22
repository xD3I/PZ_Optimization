// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.recon;

import pzopt.vendor.avif.internal.av1.image.PaddedPlane;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;
import pzopt.vendor.avif.Av1ChromaFormat;

import java.util.Objects;

/// Builds one Large Scale Tile output frame from decoded camera-frame tile regions.

public final class LargeScaleTileOutputBuilder {
    /// The output sample bit depth.
    private final int bitDepth;
    /// The output chroma layout.
    private final Av1ChromaFormat chromaFormat;
    /// The source and destination tile width in luma samples.
    private final int tileWidth;
    /// The source and destination tile height in luma samples.
    private final int tileHeight;
    /// The output tile-column count.
    private final int outputTileColumns;
    /// The output tile-row count.
    private final int outputTileRows;
    /// The output luma width.
    private final int outputWidth;
    /// The output luma height.
    private final int outputHeight;
    /// The mutable output luma samples.
    private final short[] lumaSamples;
    /// The mutable output U samples, or `null` for monochrome output.
    private final short [] chromaUSamples;
    /// The mutable output V samples, or `null` for monochrome output.
    private final short [] chromaVSamples;
    /// Whether ownership of the output arrays has been transferred.
    private boolean built;

    /// Creates an empty zero-initialized output tile grid.
    ///
    /// @param bitDepth the output sample bit depth
    /// @param chromaFormat the output chroma layout
    /// @param tileWidth the tile width in luma samples
    /// @param tileHeight the tile height in luma samples
    /// @param outputTileColumns the output width in tiles
    /// @param outputTileRows the output height in tiles
    public LargeScaleTileOutputBuilder(
            int bitDepth,
            Av1ChromaFormat chromaFormat,
            int tileWidth,
            int tileHeight,
            int outputTileColumns,
            int outputTileRows
    ) {
        if (bitDepth != 8 && bitDepth != 10 && bitDepth != 12) {
            throw new IllegalArgumentException("Unsupported bitDepth: " + bitDepth);
        }
        if (tileWidth <= 0 || tileHeight <= 0) {
            throw new IllegalArgumentException("Tile dimensions must be positive");
        }
        if (outputTileColumns <= 0 || outputTileRows <= 0) {
            throw new IllegalArgumentException("Output tile dimensions must be positive");
        }
        this.bitDepth = bitDepth;
        this.chromaFormat = Objects.requireNonNull(chromaFormat, "chromaFormat");
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.outputTileColumns = outputTileColumns;
        this.outputTileRows = outputTileRows;
        this.outputWidth = Math.multiplyExact(tileWidth, outputTileColumns);
        this.outputHeight = Math.multiplyExact(tileHeight, outputTileRows);
        this.lumaSamples = new short[Math.multiplyExact(outputWidth, outputHeight)];

        int chromaWidth = chromaWidth(chromaFormat, outputWidth);
        int chromaHeight = chromaHeight(chromaFormat, outputHeight);
        if (chromaFormat == Av1ChromaFormat.MONOCHROME) {
            this.chromaUSamples = null;
            this.chromaVSamples = null;
        } else {
            int chromaLength = Math.multiplyExact(chromaWidth, chromaHeight);
            this.chromaUSamples = new short[chromaLength];
            this.chromaVSamples = new short[chromaLength];
        }
    }

    /// Copies one decoded source tile into an output raster position.
    ///
    /// @param source the reconstructed common camera frame containing the decoded tile
    /// @param sourceTileColumn the source tile column
    /// @param sourceTileRow the source tile row
    /// @param outputTileIndex the zero-based destination tile index in raster order
    public void copyTile(
            DecodedSurface source,
            int sourceTileColumn,
            int sourceTileRow,
            int outputTileIndex
    ) {
        ensureMutable();
        DecodedSurface checkedSource = Objects.requireNonNull(source, "source");
        if (checkedSource.bitDepth() != bitDepth || checkedSource.chromaFormat() != chromaFormat) {
            throw new IllegalArgumentException("Source tile color configuration differs from the output");
        }
        if (sourceTileColumn < 0 || sourceTileRow < 0) {
            throw new IllegalArgumentException("Source tile coordinates must be non-negative");
        }
        int outputTileCount = Math.multiplyExact(outputTileColumns, outputTileRows);
        if (outputTileIndex < 0 || outputTileIndex >= outputTileCount) {
            throw new IllegalArgumentException("outputTileIndex out of range: " + outputTileIndex);
        }

        int sourceX = Math.multiplyExact(sourceTileColumn, tileWidth);
        int sourceY = Math.multiplyExact(sourceTileRow, tileHeight);
        if (sourceX + tileWidth > checkedSource.codedWidth()
                || sourceY + tileHeight > checkedSource.codedHeight()) {
            throw new IllegalArgumentException("Source tile exceeds the reconstructed camera frame");
        }
        int destinationX = outputTileIndex % outputTileColumns * tileWidth;
        int destinationY = outputTileIndex / outputTileColumns * tileHeight;
        copyPlane(
                checkedSource.lumaPlane(),
                sourceX,
                sourceY,
                tileWidth,
                tileHeight,
                lumaSamples,
                outputWidth,
                destinationX,
                destinationY
        );

        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            int chromaTileWidth = chromaWidth(chromaFormat, tileWidth);
            int chromaTileHeight = chromaHeight(chromaFormat, tileHeight);
            int sourceChromaX = chromaWidth(chromaFormat, sourceX);
            int sourceChromaY = chromaHeight(chromaFormat, sourceY);
            int destinationChromaX = chromaWidth(chromaFormat, destinationX);
            int destinationChromaY = chromaHeight(chromaFormat, destinationY);
            int outputChromaWidth = chromaWidth(chromaFormat, outputWidth);
            copyPlane(
                    Objects.requireNonNull(checkedSource.chromaUPlane(), "source.chromaUPlane"),
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    Objects.requireNonNull(chromaUSamples, "chromaUSamples"),
                    outputChromaWidth,
                    destinationChromaX,
                    destinationChromaY
            );
            copyPlane(
                    Objects.requireNonNull(checkedSource.chromaVPlane(), "source.chromaVPlane"),
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    Objects.requireNonNull(chromaVSamples, "chromaVSamples"),
                    outputChromaWidth,
                    destinationChromaX,
                    destinationChromaY
            );
        }
    }

    /// Copies one previously written output tile to another output raster position.
    ///
    /// @param sourceOutputTileIndex the zero-based source tile index in raster order
    /// @param outputTileIndex the zero-based destination tile index in raster order
    public void copyOutputTile(int sourceOutputTileIndex, int outputTileIndex) {
        ensureMutable();
        validateOutputTileIndex(sourceOutputTileIndex, "sourceOutputTileIndex");
        validateOutputTileIndex(outputTileIndex, "outputTileIndex");
        if (sourceOutputTileIndex == outputTileIndex) {
            return;
        }

        int sourceX = sourceOutputTileIndex % outputTileColumns * tileWidth;
        int sourceY = sourceOutputTileIndex / outputTileColumns * tileHeight;
        int destinationX = outputTileIndex % outputTileColumns * tileWidth;
        int destinationY = outputTileIndex / outputTileColumns * tileHeight;
        copyStoredPlane(
                lumaSamples,
                outputWidth,
                sourceX,
                sourceY,
                tileWidth,
                tileHeight,
                destinationX,
                destinationY
        );

        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            int chromaTileWidth = chromaWidth(chromaFormat, tileWidth);
            int chromaTileHeight = chromaHeight(chromaFormat, tileHeight);
            int outputChromaWidth = chromaWidth(chromaFormat, outputWidth);
            int sourceChromaX = chromaWidth(chromaFormat, sourceX);
            int sourceChromaY = chromaHeight(chromaFormat, sourceY);
            int destinationChromaX = chromaWidth(chromaFormat, destinationX);
            int destinationChromaY = chromaHeight(chromaFormat, destinationY);
            copyStoredPlane(
                    Objects.requireNonNull(chromaUSamples, "chromaUSamples"),
                    outputChromaWidth,
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    destinationChromaX,
                    destinationChromaY
            );
            copyStoredPlane(
                    Objects.requireNonNull(chromaVSamples, "chromaVSamples"),
                    outputChromaWidth,
                    sourceChromaX,
                    sourceChromaY,
                    chromaTileWidth,
                    chromaTileHeight,
                    destinationChromaX,
                    destinationChromaY
            );
        }
    }

    /// Transfers the accumulated samples into one immutable decoded-plane snapshot.
    ///
    /// This builder must not be used after this method returns.
    ///
    /// @return the assembled output frame
    public DecodedSurface build() {
        ensureMutable();
        built = true;
        PaddedPlane luma = PaddedPlane.fromOwnedSamples(outputWidth, outputHeight, outputWidth, lumaSamples);
        PaddedPlane chromaU = null;
        PaddedPlane chromaV = null;
        if (chromaFormat != Av1ChromaFormat.MONOCHROME) {
            int width = chromaWidth(chromaFormat, outputWidth);
            int height = chromaHeight(chromaFormat, outputHeight);
            chromaU = PaddedPlane.fromOwnedSamples(
                    width,
                    height,
                    width,
                    Objects.requireNonNull(chromaUSamples, "chromaUSamples")
            );
            chromaV = PaddedPlane.fromOwnedSamples(
                    width,
                    height,
                    width,
                    Objects.requireNonNull(chromaVSamples, "chromaVSamples")
            );
        }
        return new DecodedSurface(
                bitDepth,
                chromaFormat,
                outputWidth,
                outputHeight,
                outputWidth,
                outputHeight,
                luma,
                chromaU,
                chromaV
        );
    }

    /// Copies one rectangular plane region into tightly packed destination storage.
    ///
    /// @param source the immutable source plane
    /// @param sourceX the source-region X coordinate
    /// @param sourceY the source-region Y coordinate
    /// @param width the copied width
    /// @param height the copied height
    /// @param destination the destination sample storage
    /// @param destinationStride the destination row stride
    /// @param destinationX the destination-region X coordinate
    /// @param destinationY the destination-region Y coordinate
    private static void copyPlane(
            PaddedPlane source,
            int sourceX,
            int sourceY,
            int width,
            int height,
            short[] destination,
            int destinationStride,
            int destinationX,
            int destinationY
    ) {
        for (int y = 0; y < height; y++) {
            int destinationOffset = (destinationY + y) * destinationStride + destinationX;
            for (int x = 0; x < width; x++) {
                destination[destinationOffset + x] = (short) source.sample(sourceX + x, sourceY + y);
            }
        }
    }

    /// Copies one rectangular region within the builder's tightly packed plane storage.
    ///
    /// @param samples the mutable plane storage
    /// @param stride the plane row stride
    /// @param sourceX the source-region X coordinate
    /// @param sourceY the source-region Y coordinate
    /// @param width the copied width
    /// @param height the copied height
    /// @param destinationX the destination-region X coordinate
    /// @param destinationY the destination-region Y coordinate
    private static void copyStoredPlane(
            short[] samples,
            int stride,
            int sourceX,
            int sourceY,
            int width,
            int height,
            int destinationX,
            int destinationY
    ) {
        for (int y = 0; y < height; y++) {
            int sourceOffset = (sourceY + y) * stride + sourceX;
            int destinationOffset = (destinationY + y) * stride + destinationX;
            System.arraycopy(samples, sourceOffset, samples, destinationOffset, width);
        }
    }

    /// Validates one output tile index.
    ///
    /// @param outputTileIndex the index to validate
    /// @param parameterName the parameter name used in an exception message
    private void validateOutputTileIndex(int outputTileIndex, String parameterName) {
        int outputTileCount = Math.multiplyExact(outputTileColumns, outputTileRows);
        if (outputTileIndex < 0 || outputTileIndex >= outputTileCount) {
            throw new IllegalArgumentException(parameterName + " out of range: " + outputTileIndex);
        }
    }

    /// Returns the chroma-plane width for one luma width.
    ///
    /// @param format the chroma layout
    /// @param lumaWidth the luma width
    /// @return the chroma width
    private static int chromaWidth(Av1ChromaFormat format, int lumaWidth) {
        return switch (format) {
            case MONOCHROME -> 0;
            case YUV420, YUV422 -> lumaWidth >> 1;
            case YUV444 -> lumaWidth;
        };
    }

    /// Returns the chroma-plane height for one luma height.
    ///
    /// @param format the chroma layout
    /// @param lumaHeight the luma height
    /// @return the chroma height
    private static int chromaHeight(Av1ChromaFormat format, int lumaHeight) {
        return switch (format) {
            case MONOCHROME -> 0;
            case YUV420 -> lumaHeight >> 1;
            case YUV422, YUV444 -> lumaHeight;
        };
    }

    /// Ensures that sample ownership has not already been transferred.
    private void ensureMutable() {
        if (built) {
            throw new IllegalStateException("Large Scale Tile output has already been built");
        }
    }
}
