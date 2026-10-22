// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.parse;

import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.SequenceHeader;

import java.io.IOException;
import java.util.Objects;

/// Validates strict AV1 frame-header constraints that depend on previously parsed state.

final class FrameHeaderConformanceValidator {
    /// Number of reference slots signaled by one inter frame.
    private static final int REFERENCES_PER_FRAME = 7;

    /// Prevents instantiation of this utility class.
    private FrameHeaderConformanceValidator() {
    }

    /// Validates level limits and reference-frame dimension ratios for one parsed frame.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @throws IOException if the frame violates a strict conformance constraint
    static void validate(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        Objects.requireNonNull(frameHeader, "frameHeader");
        Objects.requireNonNull(referenceFrameHeaders, "referenceFrameHeaders");

        if (frameHeader.allLossless() && frameHeader.delta().deltaQPresent()) {
            throw new IOException("Coded-lossless frames must not signal delta_q_present");
        }
        validateSequenceFrameDimensions(sequenceHeader, frameHeader);
        validateShortSignaledReferences(sequenceHeader, frameHeader, referenceFrameHeaders);
        validateLevelLimits(sequenceHeader, frameHeader);
        for (int referenceIndex = 0; referenceIndex < REFERENCES_PER_FRAME; referenceIndex++) {
            int slotIndex = frameHeader.referenceFrameIndex(referenceIndex);
            if (slotIndex < 0) {
                continue;
            }
            FrameHeader referenceFrameHeader = referenceFrameHeaders[slotIndex];
            if (referenceFrameHeader != null) {
                validateReferenceDimensions(frameHeader.frameSize(), referenceFrameHeader.frameSize());
            }
        }
    }

    /// Validates sequence and common-camera-frame requirements imposed by Large Scale Tile mode.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if the frame violates a Large Scale Tile requirement
    static void validateLargeScaleTileFrame(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) throws IOException {
        Objects.requireNonNull(sequenceHeader, "sequenceHeader");
        Objects.requireNonNull(frameHeader, "frameHeader");
        SequenceHeader.FeatureConfig features = sequenceHeader.features();
        if (features.superResolution()) {
            throw new IOException("Large Scale Tile mode requires enable_superres to be zero");
        }
        if (features.orderHint()) {
            throw new IOException("Large Scale Tile mode requires enable_order_hint to be zero");
        }
        if (sequenceHeader.stillPicture()) {
            throw new IOException("Large Scale Tile mode requires still_picture to be zero");
        }
        if (features.filmGrainPresent()) {
            throw new IOException("Large Scale Tile mode requires film_grain_params_present to be zero");
        }
        if (sequenceHeader.timingInfo().present()) {
            throw new IOException("Large Scale Tile mode requires timing_info_present_flag to be zero");
        }
        if (sequenceHeader.timingInfo().decoderModelInfoPresent()) {
            throw new IOException("Large Scale Tile mode requires decoder_model_info_present_flag to be zero");
        }
        if (sequenceHeader.timingInfo().displayModelInfoPresent()) {
            throw new IOException("Large Scale Tile mode requires initial_display_delay_present_flag to be zero");
        }
        if (features.restoration()) {
            throw new IOException("Large Scale Tile mode requires enable_restoration to be zero");
        }
        if (features.cdef()) {
            throw new IOException("Large Scale Tile mode requires enable_cdef to be zero");
        }
        if (sequenceHeader.colorConfig().monochrome()) {
            throw new IOException("Large Scale Tile mode requires mono_chrome to be zero");
        }

        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        for (int row = 0; row < tiling.rows(); row++) {
            if (tiling.rowStartSuperblock(row + 1) - tiling.rowStartSuperblock(row) != 1) {
                throw new IOException("Large Scale Tile camera tiles must be exactly one superblock high");
            }
        }
        int tileWidthSuperblocks = tiling.columnStartSuperblock(1) - tiling.columnStartSuperblock(0);
        for (int column = 1; column < tiling.columns(); column++) {
            if (tiling.columnStartSuperblock(column + 1)
                    - tiling.columnStartSuperblock(column) != tileWidthSuperblocks) {
                throw new IOException("Large Scale Tile camera tiles must have identical widths");
            }
        }

        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        int miColumns = ((frameSize.codedWidth() + 7) >> 3) << 1;
        int miRows = ((frameSize.height() + 7) >> 3) << 1;
        if (frameSize.codedWidth() != miColumns * 4 || frameSize.height() != miRows * 4) {
            throw new IOException("Large Scale Tile frame dimensions must be integer MI units");
        }
        if (frameHeader.showExistingFrame()) {
            throw new IOException("Large Scale Tile camera frames must not signal show_existing_frame");
        }
        if (frameHeader.frameType() != Av1FrameType.INTER) {
            throw new IOException("Large Scale Tile camera frames must use INTER_FRAME");
        }
        if (!frameHeader.showFrame()) {
            throw new IOException("Large Scale Tile camera frames must set show_frame");
        }
        if (frameHeader.errorResilientMode()) {
            throw new IOException("Large Scale Tile camera frames must disable error_resilient_mode");
        }
        if (!frameHeader.disableCdfUpdate()) {
            throw new IOException("Large Scale Tile frames must disable CDF updates");
        }
        if (frameHeader.refreshContext()) {
            throw new IOException("Large Scale Tile frames must disable frame-end CDF updates");
        }
        if (frameHeader.delta().deltaQPresent() || frameHeader.delta().deltaLfPresent()) {
            throw new IOException("Large Scale Tile frames must not signal delta quantizer or loop-filter updates");
        }
        if (frameHeader.frameSizeOverride()) {
            throw new IOException("Large Scale Tile camera frames must disable frame-size override");
        }
        if (frameHeader.refreshFrameFlags() != 0) {
            throw new IOException("Large Scale Tile camera frames must not refresh reference slots");
        }
        if (frameHeader.useReferenceFrameMotionVectors()) {
            throw new IOException("Large Scale Tile camera frames must not use reference-frame motion vectors");
        }
        if (frameHeader.segmentation().temporalUpdate()) {
            throw new IOException("Large Scale Tile camera frames must disable temporal segmentation updates");
        }
        if (frameHeader.switchableCompoundReferences()) {
            throw new IOException("Large Scale Tile camera frames must disable compound references");
        }
        if (frameHeader.loopFilter().levelY(0) != 0 || frameHeader.loopFilter().levelY(1) != 0) {
            throw new IOException("Large Scale Tile camera frames must disable loop filtering");
        }
        if (frameHeader.superResolution().enabled()) {
            throw new IOException("Large Scale Tile frames must not use super-resolution");
        }
    }

    /// Validates explicitly signaled frame dimensions against the sequence-header maxima.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if the frame exceeds either declared maximum dimension
    private static void validateSequenceFrameDimensions(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) throws IOException {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        if (frameSize.upscaledWidth() > sequenceHeader.maxWidth()) {
            throw new IOException("Frame width exceeds the sequence-header maximum");
        }
        if (frameSize.height() > sequenceHeader.maxHeight()) {
            throw new IOException("Frame height exceeds the sequence-header maximum");
        }
    }

    /// Validates the LAST and GOLDEN order hints used by short reference signaling.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @throws IOException if either explicitly selected reference is not earlier in output order
    private static void validateShortSignaledReferences(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameHeader[] referenceFrameHeaders
    ) throws IOException {
        if (!frameHeader.frameReferenceShortSignaling()) {
            return;
        }
        validateEarlierReferenceOrderHint(sequenceHeader, frameHeader, referenceFrameHeaders, 0, "LAST");
        validateEarlierReferenceOrderHint(sequenceHeader, frameHeader, referenceFrameHeaders, 3, "GOLDEN");
    }

    /// Validates one short-signaled reference against the current frame order hint.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @param referenceFrameHeaders the refreshed reference-frame headers indexed by slot
    /// @param referenceIndex the LAST..ALTREF reference position
    /// @param referenceName the reference name used in diagnostics
    /// @throws IOException if the reference is unavailable or is not earlier in output order
    private static void validateEarlierReferenceOrderHint(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader,
            FrameHeader[] referenceFrameHeaders,
            int referenceIndex,
            String referenceName
    ) throws IOException {
        int slotIndex = frameHeader.referenceFrameIndex(referenceIndex);
        if (slotIndex < 0 || slotIndex >= referenceFrameHeaders.length) {
            throw new IOException(referenceName + " reference does not select a valid frame slot");
        }
        FrameHeader referenceFrameHeader = referenceFrameHeaders[slotIndex];
        if (referenceFrameHeader == null) {
            throw new IOException(referenceName + " reference selects an unavailable frame slot");
        }
        int relativeDistance = relativeDistance(
                sequenceHeader.features().orderHintBits(),
                referenceFrameHeader.frameOffset(),
                frameHeader.frameOffset()
        );
        if (relativeDistance >= 0) {
            throw new IOException(referenceName + " reference order hint is not earlier than the current frame");
        }
    }

    /// Returns the wrapped AV1 relative distance between two order hints.
    ///
    /// @param orderHintBits the number of order-hint bits declared by the sequence
    /// @param first the first order hint
    /// @param second the second order hint
    /// @return the wrapped signed distance `first - second`
    private static int relativeDistance(int orderHintBits, int first, int second) {
        int mask = 1 << (orderHintBits - 1);
        int difference = first - second;
        return (difference & (mask - 1)) - (difference & mask);
    }

    /// Validates the current frame against each operating point that contains its layer.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if the frame exceeds an applicable level limit
    private static void validateLevelLimits(SequenceHeader sequenceHeader, FrameHeader frameHeader) throws IOException {
        boolean croppedTileDimensionsValidated = false;
        for (int index = 0; index < sequenceHeader.operatingPointCount(); index++) {
            SequenceHeader.OperatingPoint operatingPoint = sequenceHeader.operatingPoint(index);
            if (!containsLayer(operatingPoint.idc(), frameHeader.temporalId(), frameHeader.spatialId())) {
                continue;
            }
            LevelLimits limits = levelLimits(operatingPoint.majorLevel(), operatingPoint.minorLevel());
            if (limits != null) {
                if (!croppedTileDimensionsValidated) {
                    validateCroppedTileDimensions(sequenceHeader, frameHeader);
                    croppedTileDimensionsValidated = true;
                }
                validateLevelLimits(frameHeader, limits, operatingPoint.majorLevel(), operatingPoint.minorLevel());
            }
        }
    }

    /// Returns whether an operating-point mask contains one temporal and spatial layer pair.
    ///
    /// @param operatingPointIdc the operating-point layer mask
    /// @param temporalId the frame temporal layer identifier
    /// @param spatialId the frame spatial layer identifier
    /// @return whether the frame belongs to the operating point
    private static boolean containsLayer(int operatingPointIdc, int temporalId, int spatialId) {
        if (operatingPointIdc == 0) {
            return true;
        }
        return (operatingPointIdc & (1 << temporalId)) != 0
                && (operatingPointIdc & (1 << (spatialId + 8))) != 0;
    }

    /// Validates frame dimensions and tile counts against one level-limit row.
    ///
    /// @param frameHeader the parsed current frame header
    /// @param limits the applicable level limits
    /// @param majorLevel the declared major level
    /// @param minorLevel the declared minor level
    /// @throws IOException if the frame exceeds a level limit
    private static void validateLevelLimits(
            FrameHeader frameHeader,
            LevelLimits limits,
            int majorLevel,
            int minorLevel
    ) throws IOException {
        FrameHeader.FrameSize frameSize = frameHeader.frameSize();
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int upscaledWidth = frameSize.upscaledWidth();
        int frameHeight = frameSize.height();
        long pictureSize = (long) upscaledWidth * frameHeight;
        long tileCount = (long) tiling.columns() * tiling.rows();
        String level = majorLevel + "." + minorLevel;

        if (upscaledWidth < 16) {
            throw new IOException("Frame width is smaller than the AV1 level minimum of 16 pixels");
        }
        if (frameHeight < 16) {
            throw new IOException("Frame height is smaller than the AV1 level minimum of 16 pixels");
        }
        if (pictureSize > limits.maximumPictureSize()) {
            throw new IOException("Frame picture size exceeds AV1 level " + level + " MaxPicSize");
        }
        if (upscaledWidth > limits.maximumHorizontalSize()) {
            throw new IOException("Frame upscaled width exceeds AV1 level " + level + " MaxHSize");
        }
        if (frameHeight > limits.maximumVerticalSize()) {
            throw new IOException("Frame height exceeds AV1 level " + level + " MaxVSize");
        }
        if (tileCount > limits.maximumTileCount()) {
            throw new IOException("Frame tile count exceeds AV1 level " + level + " MaxTiles");
        }
        if (tiling.columns() > limits.maximumTileColumns()) {
            throw new IOException("Frame tile-column count exceeds AV1 level " + level + " MaxTileCols");
        }
    }

    /// Validates that every cropped tile retains at least eight pixels in each dimension.
    ///
    /// @param sequenceHeader the active sequence header
    /// @param frameHeader the parsed current frame header
    /// @throws IOException if a cropped edge tile is narrower or shorter than eight pixels
    private static void validateCroppedTileDimensions(
            SequenceHeader sequenceHeader,
            FrameHeader frameHeader
    ) throws IOException {
        FrameHeader.TilingInfo tiling = frameHeader.tiling();
        int superblockSize = sequenceHeader.features().use128x128Superblocks() ? 128 : 64;
        int frameWidth = frameHeader.frameSize().codedWidth();
        int frameHeight = frameHeader.frameSize().height();

        for (int column = 0; column < tiling.columns(); column++) {
            int start = Math.min(frameWidth, tiling.columnStartSuperblock(column) * superblockSize);
            int end = Math.min(frameWidth, tiling.columnStartSuperblock(column + 1) * superblockSize);
            if (end - start < 8) {
                throw new IOException("Cropped tile width is smaller than eight pixels");
            }
        }
        for (int row = 0; row < tiling.rows(); row++) {
            int start = Math.min(frameHeight, tiling.rowStartSuperblock(row) * superblockSize);
            int end = Math.min(frameHeight, tiling.rowStartSuperblock(row + 1) * superblockSize);
            if (end - start < 8) {
                throw new IOException("Cropped tile height is smaller than eight pixels");
            }
        }
    }

    /// Validates the AV1 dimension-ratio limits between a frame and one referenced frame.
    ///
    /// @param currentSize the current coded frame dimensions
    /// @param referenceSize the referenced frame dimensions
    /// @throws IOException if either dimension ratio is outside the permitted range
    static void validateReferenceDimensions(
            FrameHeader.FrameSize currentSize,
            FrameHeader.FrameSize referenceSize
    ) throws IOException {
        Objects.requireNonNull(currentSize, "currentSize");
        Objects.requireNonNull(referenceSize, "referenceSize");
        int frameWidth = currentSize.codedWidth();
        int frameHeight = currentSize.height();
        int referenceWidth = referenceSize.upscaledWidth();
        int referenceHeight = referenceSize.height();

        if (2L * frameWidth < referenceWidth || 2L * frameHeight < referenceHeight) {
            throw new IOException("Referenced frame dimensions exceed twice the current frame dimensions");
        }
        if (frameWidth > 16L * referenceWidth || frameHeight > 16L * referenceHeight) {
            throw new IOException("Current frame dimensions exceed sixteen times the referenced frame dimensions");
        }
    }

    /// Returns the AV1 level-limit row for a defined level.
    ///
    /// Undefined level indices intentionally return `null`; the specification does not map them to
    /// a parameter-limit row.
    ///
    /// @param majorLevel the declared major level
    /// @param minorLevel the declared minor level
    /// @return the corresponding level limits, or `null` for an undefined level index
    private static LevelLimits levelLimits(int majorLevel, int minorLevel) {
        return switch (majorLevel * 10 + minorLevel) {
            case 20 -> new LevelLimits(147_456, 2_048, 1_152, 8, 4);
            case 21 -> new LevelLimits(278_784, 2_816, 1_584, 8, 4);
            case 30 -> new LevelLimits(665_856, 4_352, 2_448, 16, 6);
            case 31 -> new LevelLimits(1_065_024, 5_504, 3_096, 16, 6);
            case 40, 41 -> new LevelLimits(2_359_296, 6_144, 3_456, 32, 8);
            case 50, 51, 52, 53 -> new LevelLimits(8_912_896, 8_192, 4_352, 64, 8);
            case 60, 61, 62, 63 -> new LevelLimits(35_651_584, 16_384, 8_704, 128, 16);
            default -> null;
        };
    }

    /// Defines the frame-size and tile-count limits for one AV1 level row.
    ///
    /// @param maximumPictureSize the maximum luma picture area
    /// @param maximumHorizontalSize the maximum upscaled frame width
    /// @param maximumVerticalSize the maximum frame height
    /// @param maximumTileCount the maximum number of tiles
    /// @param maximumTileColumns the maximum number of tile columns
    private record LevelLimits(
            int maximumPictureSize,
            int maximumHorizontalSize,
            int maximumVerticalSize,
            int maximumTileCount,
            int maximumTileColumns
    ) {
    }
}
