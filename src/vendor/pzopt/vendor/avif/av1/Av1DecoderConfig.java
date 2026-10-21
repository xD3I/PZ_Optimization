// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.av1;


import java.util.Objects;

/// Immutable decoder configuration for [Av1Decoder].
///
/// Configuration changes are expressed by `withXxx` methods. Each method returns this instance
/// when the requested value is already selected and otherwise returns a new configuration.

public final class Av1DecoderConfig {
    /// The default maximum decoded luma-plane area, sufficient for an `8192x8192` frame.
    private static final long DEFAULT_FRAME_SIZE_LIMIT = 8192L * 8192L;
    /// The default maximum encoded payload retained for one OBU.
    private static final long DEFAULT_OBU_PAYLOAD_SIZE_LIMIT = 256L * 1024L * 1024L;

    /// The default decoder configuration.
    public static final Av1DecoderConfig DEFAULT = new Av1DecoderConfig(
            true,
            false,
            false,
            false,
            false,
            Av1FrameSelection.ALL,
            0,
            DEFAULT_FRAME_SIZE_LIMIT,
            DEFAULT_OBU_PAYLOAD_SIZE_LIMIT
    );

    /// Whether decoded output includes film grain synthesis.
    private final boolean applyFilmGrain;
    /// Whether standard compliance issues are treated as hard errors.
    private final boolean strictStdCompliance;
    /// Whether invisible frames are exposed through the public API.
    private final boolean outputInvisibleFrames;
    /// Whether every selected spatial layer is exposed as a separate output.
    private final boolean outputAllLayers;
    /// Whether frame payloads use the AV1 Large Scale Tile layout.
    private final boolean largeScaleTileMode;
    /// The selected frame categories.
    private final Av1FrameSelection frameSelection;
    /// The selected AV1 operating point.
    private final int operatingPoint;
    /// The maximum decoded frame size in pixels, or `0` when no additional configured limit applies.
    private final long frameSizeLimit;
    /// The maximum encoded payload size retained for one OBU, or `0` when no configured limit applies.
    private final long obuPayloadSizeLimit;

    /// Creates a validated decoder configuration.
    ///
    /// @param applyFilmGrain whether film grain is applied
    /// @param strictStdCompliance whether standard compliance issues are hard errors
    /// @param outputInvisibleFrames whether invisible frames are exposed
    /// @param outputAllLayers whether every selected spatial layer is exposed
    /// @param largeScaleTileMode whether Large Scale Tile layout is enabled
    /// @param frameSelection the selected frame categories
    /// @param operatingPoint the operating-point index
    /// @param frameSizeLimit the maximum frame size in pixels, or `0` when no additional
    ///                       configured limit applies
    /// @param obuPayloadSizeLimit the maximum encoded payload size retained for one OBU, or `0`
    ///                            when no additional configured limit applies
    private Av1DecoderConfig(
            boolean applyFilmGrain,
            boolean strictStdCompliance,
            boolean outputInvisibleFrames,
            boolean outputAllLayers,
            boolean largeScaleTileMode,
            Av1FrameSelection frameSelection,
            int operatingPoint,
            long frameSizeLimit,
            long obuPayloadSizeLimit
    ) {
        if (operatingPoint < 0 || operatingPoint > 31) {
            throw new IllegalArgumentException("operatingPoint out of range: " + operatingPoint);
        }
        if (frameSizeLimit < 0) {
            throw new IllegalArgumentException("frameSizeLimit < 0: " + frameSizeLimit);
        }
        if (obuPayloadSizeLimit < 0) {
            throw new IllegalArgumentException("obuPayloadSizeLimit < 0: " + obuPayloadSizeLimit);
        }
        this.applyFilmGrain = applyFilmGrain;
        this.strictStdCompliance = strictStdCompliance;
        this.outputInvisibleFrames = outputInvisibleFrames;
        this.outputAllLayers = outputAllLayers;
        this.largeScaleTileMode = largeScaleTileMode;
        this.frameSelection = Objects.requireNonNull(frameSelection, "frameSelection");
        this.operatingPoint = operatingPoint;
        this.frameSizeLimit = frameSizeLimit;
        this.obuPayloadSizeLimit = obuPayloadSizeLimit;
    }

    /// Returns whether decoded output includes film grain synthesis.
    ///
    /// @return whether film grain is enabled
    public boolean applyFilmGrain() {
        return applyFilmGrain;
    }

    /// Returns whether standard compliance issues are treated as hard errors.
    ///
    /// @return whether strict compliance is enabled
    public boolean strictStdCompliance() {
        return strictStdCompliance;
    }

    /// Returns whether invisible frames are exposed through the public API.
    ///
    /// @return whether invisible frames are returned
    public boolean outputInvisibleFrames() {
        return outputInvisibleFrames;
    }

    /// Returns whether every selected spatial layer is exposed as a separate output.
    ///
    /// When disabled, only the last selected spatial-layer output in each temporal unit is
    /// returned, while earlier layers remain available for decoding dependencies.
    ///
    /// @return whether all selected spatial layers are returned
    public boolean outputAllLayers() {
        return outputAllLayers;
    }

    /// Returns whether frame payloads use the AV1 Large Scale Tile layout.
    ///
    /// This setting is external to the bitstream. When enabled, leading decoded frames are
    /// retained as externally indexed anchors, the common camera frame is not presented, and
    /// tile-list OBUs produce the visible outputs.
    ///
    /// @return whether Large Scale Tile mode is enabled
    public boolean largeScaleTileMode() {
        return largeScaleTileMode;
    }

    /// Returns the selected frame categories.
    ///
    /// @return the frame selection
    public Av1FrameSelection frameSelection() {
        return frameSelection;
    }

    /// Returns the selected AV1 operating point.
    ///
    /// @return the operating-point index in `[0, 31]`
    public int operatingPoint() {
        return operatingPoint;
    }

    /// Returns the maximum decoded frame size in pixels.
    ///
    /// A zero value disables the configurable resource limit. Implementation limits required to
    /// represent a decoded plane still apply.
    ///
    /// @return the frame size limit, or `0` when no additional configured limit applies
    public long frameSizeLimit() {
        return frameSizeLimit;
    }

    /// Returns the maximum encoded payload retained for one OBU.
    ///
    /// A zero value disables the configurable resource limit. The implementation limit required
    /// to represent the payload as a byte array still applies.
    ///
    /// @return the OBU payload size limit in bytes, or `0` when no configured limit applies
    public long obuPayloadSizeLimit() {
        return obuPayloadSizeLimit;
    }

    /// Returns a configuration that selects whether film grain is applied.
    ///
    /// @param value whether film grain is applied
    /// @return this configuration or one with the requested value
    public Av1DecoderConfig withApplyFilmGrain(boolean value) {
        return value == applyFilmGrain ? this : copy(value, strictStdCompliance, outputInvisibleFrames,
                outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit,
                obuPayloadSizeLimit);
    }

    /// Returns a configuration that selects strict standard compliance.
    ///
    /// @param value whether compliance issues are hard errors
    /// @return this configuration or one with the requested value
    public Av1DecoderConfig withStrictStdCompliance(boolean value) {
        return value == strictStdCompliance ? this : copy(applyFilmGrain, value, outputInvisibleFrames,
                outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit,
                obuPayloadSizeLimit);
    }

    /// Returns a configuration that selects whether invisible frames are exposed.
    ///
    /// @param value whether invisible frames are exposed
    /// @return this configuration or one with the requested value
    public Av1DecoderConfig withOutputInvisibleFrames(boolean value) {
        return value == outputInvisibleFrames ? this : copy(applyFilmGrain, strictStdCompliance, value,
                outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit,
                obuPayloadSizeLimit);
    }

    /// Returns a configuration that selects whether every selected spatial layer is exposed.
    ///
    /// @param value whether every selected spatial layer is exposed
    /// @return this configuration or one with the requested value
    public Av1DecoderConfig withOutputAllLayers(boolean value) {
        return value == outputAllLayers ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, value, largeScaleTileMode, frameSelection, operatingPoint,
                frameSizeLimit, obuPayloadSizeLimit);
    }

    /// Returns a configuration that selects whether Large Scale Tile layout is enabled.
    ///
    /// The caller must enable this only for streams packaged for Large Scale Tile decoder
    /// operation.
    ///
    /// @param value whether Large Scale Tile layout is enabled
    /// @return this configuration or one with the requested value
    public Av1DecoderConfig withLargeScaleTileMode(boolean value) {
        return value == largeScaleTileMode ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, outputAllLayers, value, frameSelection, operatingPoint,
                frameSizeLimit, obuPayloadSizeLimit);
    }

    /// Returns a configuration that selects the supplied frame categories.
    ///
    /// @param value the frame selection
    /// @return this configuration or one with the requested selection
    public Av1DecoderConfig withFrameSelection(Av1FrameSelection value) {
        Av1FrameSelection checkedValue = Objects.requireNonNull(value, "value");
        return checkedValue == frameSelection ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, outputAllLayers, largeScaleTileMode, checkedValue,
                operatingPoint, frameSizeLimit, obuPayloadSizeLimit);
    }

    /// Returns a configuration selecting the supplied operating point.
    ///
    /// @param value the operating-point index in `[0, 31]`
    /// @return this configuration or one with the requested selection
    public Av1DecoderConfig withOperatingPoint(int value) {
        return value == operatingPoint ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection, value,
                frameSizeLimit, obuPayloadSizeLimit);
    }

    /// Returns a configuration with the supplied maximum decoded frame size.
    ///
    /// A zero value disables the configurable resource limit. It does not disable implementation
    /// limits required to represent decoded planes.
    ///
    /// @param value the frame size limit in pixels, or `0` when no additional configured limit applies
    /// @return this configuration or one with the requested limit
    public Av1DecoderConfig withFrameSizeLimit(long value) {
        return value == frameSizeLimit ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection,
                operatingPoint, value, obuPayloadSizeLimit);
    }

    /// Returns a configuration with the supplied maximum encoded payload size for one OBU.
    ///
    /// A zero value disables the configurable resource limit. It does not disable the
    /// implementation limit required to represent an OBU payload as a byte array.
    ///
    /// @param value the OBU payload size limit in bytes, or `0` when no configured limit applies
    /// @return this configuration or one with the requested limit
    public Av1DecoderConfig withObuPayloadSizeLimit(long value) {
        return value == obuPayloadSizeLimit ? this : copy(applyFilmGrain, strictStdCompliance,
                outputInvisibleFrames, outputAllLayers, largeScaleTileMode, frameSelection,
                operatingPoint, frameSizeLimit, value);
    }

    /// Creates a configuration from the supplied complete state.
    ///
    /// @return the validated configuration
    private static Av1DecoderConfig copy(
            boolean applyFilmGrain,
            boolean strictStdCompliance,
            boolean outputInvisibleFrames,
            boolean outputAllLayers,
            boolean largeScaleTileMode,
            Av1FrameSelection frameSelection,
            int operatingPoint,
            long frameSizeLimit,
            long obuPayloadSizeLimit
    ) {
        return new Av1DecoderConfig(applyFilmGrain, strictStdCompliance, outputInvisibleFrames,
                outputAllLayers, largeScaleTileMode, frameSelection, operatingPoint, frameSizeLimit,
                obuPayloadSizeLimit);
    }
}
