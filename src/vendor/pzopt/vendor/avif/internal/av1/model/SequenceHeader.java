// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;

import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.av1.Av1ColorConfig;

import java.util.Arrays;
import java.util.Objects;

/// Parsed AV1 sequence header data.

public final class SequenceHeader {
    /// The AV1 profile.
    private final int profile;
    /// The maximum coded frame width for the sequence.
    private final int maxWidth;
    /// The maximum coded frame height for the sequence.
    private final int maxHeight;
    /// The timing and decoder model information for the sequence.
    private final TimingInfo timingInfo;
    /// The operating point definitions declared by the sequence.
    private final OperatingPoint [] operatingPoints;
    /// Whether the sequence is marked as still-picture only.
    private final boolean stillPicture;
    /// Whether the sequence uses the reduced still-picture header form.
    private final boolean reducedStillPictureHeader;
    /// The encoded bit width used for frame widths.
    private final int widthBits;
    /// The encoded bit width used for frame heights.
    private final int heightBits;
    /// Whether frame identifiers are present in frame headers.
    private final boolean frameIdNumbersPresent;
    /// The number of bits used for delta frame identifiers.
    private final int deltaFrameIdBits;
    /// The number of bits used for frame identifiers.
    private final int frameIdBits;
    /// The feature flags enabled by the sequence.
    private final FeatureConfig features;
    /// The color and chroma configuration for the sequence.
    private final Av1ColorConfig colorConfig;

    /// Creates a parsed sequence header.
    ///
    /// @param profile the AV1 profile
    /// @param maxWidth the maximum coded frame width
    /// @param maxHeight the maximum coded frame height
    /// @param timingInfo the timing and decoder model information
    /// @param operatingPoints the declared operating points
    /// @param stillPicture whether the sequence is still-picture only
    /// @param reducedStillPictureHeader whether the reduced header form is used
    /// @param widthBits the encoded bit width used for frame widths
    /// @param heightBits the encoded bit width used for frame heights
    /// @param frameIdNumbersPresent whether frame identifiers are present
    /// @param deltaFrameIdBits the number of bits used for delta frame identifiers
    /// @param frameIdBits the number of bits used for frame identifiers
    /// @param features the enabled sequence features
    /// @param colorConfig the color and chroma configuration
    public SequenceHeader(
            int profile,
            int maxWidth,
            int maxHeight,
            TimingInfo timingInfo,
            OperatingPoint[] operatingPoints,
            boolean stillPicture,
            boolean reducedStillPictureHeader,
            int widthBits,
            int heightBits,
            boolean frameIdNumbersPresent,
            int deltaFrameIdBits,
            int frameIdBits,
            FeatureConfig features,
            Av1ColorConfig colorConfig
    ) {
        this.profile = profile;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.timingInfo = Objects.requireNonNull(timingInfo, "timingInfo");
        this.operatingPoints = Arrays.copyOf(Objects.requireNonNull(operatingPoints, "operatingPoints"), operatingPoints.length);
        this.stillPicture = stillPicture;
        this.reducedStillPictureHeader = reducedStillPictureHeader;
        this.widthBits = widthBits;
        this.heightBits = heightBits;
        this.frameIdNumbersPresent = frameIdNumbersPresent;
        this.deltaFrameIdBits = deltaFrameIdBits;
        this.frameIdBits = frameIdBits;
        this.features = Objects.requireNonNull(features, "features");
        this.colorConfig = Objects.requireNonNull(colorConfig, "colorConfig");
    }

    /// Returns the AV1 profile.
    ///
    /// @return the AV1 profile
    public int profile() {
        return profile;
    }

    /// Returns the maximum coded frame width for the sequence.
    ///
    /// @return the maximum coded frame width
    public int maxWidth() {
        return maxWidth;
    }

    /// Returns the maximum coded frame height for the sequence.
    ///
    /// @return the maximum coded frame height
    public int maxHeight() {
        return maxHeight;
    }

    /// Returns the timing and decoder model information for the sequence.
    ///
    /// @return the timing and decoder model information
    public TimingInfo timingInfo() {
        return timingInfo;
    }

    /// Returns the declared operating points.
    ///
    /// @return the declared operating points
    public OperatingPoint[] operatingPoints() {
        return Arrays.copyOf(operatingPoints, operatingPoints.length);
    }

    /// Returns the operating point count.
    ///
    /// @return the operating point count
    public int operatingPointCount() {
        return operatingPoints.length;
    }

    /// Returns a declared operating point by index.
    ///
    /// @param index the zero-based operating point index
    /// @return the declared operating point
    public OperatingPoint operatingPoint(int index) {
        return operatingPoints[index];
    }

    /// Returns whether the sequence is marked as still-picture only.
    ///
    /// @return whether the sequence is still-picture only
    public boolean stillPicture() {
        return stillPicture;
    }

    /// Returns whether the sequence uses the reduced still-picture header form.
    ///
    /// @return whether the reduced header form is used
    public boolean reducedStillPictureHeader() {
        return reducedStillPictureHeader;
    }

    /// Returns the encoded bit width used for frame widths.
    ///
    /// @return the encoded bit width used for frame widths
    public int widthBits() {
        return widthBits;
    }

    /// Returns the encoded bit width used for frame heights.
    ///
    /// @return the encoded bit width used for frame heights
    public int heightBits() {
        return heightBits;
    }

    /// Returns whether frame identifiers are present in frame headers.
    ///
    /// @return whether frame identifiers are present
    public boolean frameIdNumbersPresent() {
        return frameIdNumbersPresent;
    }

    /// Returns the number of bits used for delta frame identifiers.
    ///
    /// @return the number of bits used for delta frame identifiers
    public int deltaFrameIdBits() {
        return deltaFrameIdBits;
    }

    /// Returns the number of bits used for frame identifiers.
    ///
    /// @return the number of bits used for frame identifiers
    public int frameIdBits() {
        return frameIdBits;
    }

    /// Returns the feature flags enabled by the sequence.
    ///
    /// @return the enabled sequence features
    public FeatureConfig features() {
        return features;
    }

    /// Returns the color and chroma configuration for the sequence.
    ///
    /// @return the color and chroma configuration
    public Av1ColorConfig colorConfig() {
        return colorConfig;
    }

    /// Tri-state AV1 boolean syntax elements.
    
    public enum AdaptiveBoolean {
        /// The feature is disabled.
        OFF,
        /// The feature is enabled.
        ON,
        /// The feature is signaled adaptively at frame level.
        ADAPTIVE
    }

    /// Timing and decoder model information declared by the sequence.
    
    public static final class TimingInfo {
        /// Whether timing information is present.
        private final boolean present;
        /// The AV1 `num_units_in_tick` value.
        private final long numUnitsInTick;
        /// The AV1 `time_scale` value.
        private final long timeScale;
        /// Whether equal picture interval signaling is present.
        private final boolean equalPictureInterval;
        /// The number of ticks per picture when signaled.
        private final long numTicksPerPicture;
        /// Whether decoder model info is present.
        private final boolean decoderModelInfoPresent;
        /// The number of bits used for decoder/encoder buffer delay fields.
        private final int encoderDecoderBufferDelayLength;
        /// The AV1 `num_units_in_decoding_tick` value.
        private final long numUnitsInDecodingTick;
        /// The number of bits used for buffer removal delay fields.
        private final int bufferRemovalDelayLength;
        /// The number of bits used for frame presentation delay fields.
        private final int framePresentationDelayLength;
        /// Whether display model info is present.
        private final boolean displayModelInfoPresent;

        /// Creates timing and decoder model information.
        ///
        /// @param present whether timing information is present
        /// @param numUnitsInTick the AV1 `num_units_in_tick` value
        /// @param timeScale the AV1 `time_scale` value
        /// @param equalPictureInterval whether equal picture interval signaling is present
        /// @param numTicksPerPicture the number of ticks per picture when signaled
        /// @param decoderModelInfoPresent whether decoder model info is present
        /// @param encoderDecoderBufferDelayLength the number of bits used for decoder/encoder buffer delay fields
        /// @param numUnitsInDecodingTick the AV1 `num_units_in_decoding_tick` value
        /// @param bufferRemovalDelayLength the number of bits used for buffer removal delay fields
        /// @param framePresentationDelayLength the number of bits used for frame presentation delay fields
        /// @param displayModelInfoPresent whether display model info is present
        public TimingInfo(
                boolean present,
                long numUnitsInTick,
                long timeScale,
                boolean equalPictureInterval,
                long numTicksPerPicture,
                boolean decoderModelInfoPresent,
                int encoderDecoderBufferDelayLength,
                long numUnitsInDecodingTick,
                int bufferRemovalDelayLength,
                int framePresentationDelayLength,
                boolean displayModelInfoPresent
        ) {
            this.present = present;
            this.numUnitsInTick = numUnitsInTick;
            this.timeScale = timeScale;
            this.equalPictureInterval = equalPictureInterval;
            this.numTicksPerPicture = numTicksPerPicture;
            this.decoderModelInfoPresent = decoderModelInfoPresent;
            this.encoderDecoderBufferDelayLength = encoderDecoderBufferDelayLength;
            this.numUnitsInDecodingTick = numUnitsInDecodingTick;
            this.bufferRemovalDelayLength = bufferRemovalDelayLength;
            this.framePresentationDelayLength = framePresentationDelayLength;
            this.displayModelInfoPresent = displayModelInfoPresent;
        }

        /// Returns whether timing information is present.
        ///
        /// @return whether timing information is present
        public boolean present() {
            return present;
        }

        /// Returns the AV1 `num_units_in_tick` value.
        ///
        /// @return the AV1 `num_units_in_tick` value
        public long numUnitsInTick() {
            return numUnitsInTick;
        }

        /// Returns the AV1 `time_scale` value.
        ///
        /// @return the AV1 `time_scale` value
        public long timeScale() {
            return timeScale;
        }

        /// Returns whether equal picture interval signaling is present.
        ///
        /// @return whether equal picture interval signaling is present
        public boolean equalPictureInterval() {
            return equalPictureInterval;
        }

        /// Returns the number of ticks per picture when signaled.
        ///
        /// @return the number of ticks per picture
        public long numTicksPerPicture() {
            return numTicksPerPicture;
        }

        /// Returns whether decoder model info is present.
        ///
        /// @return whether decoder model info is present
        public boolean decoderModelInfoPresent() {
            return decoderModelInfoPresent;
        }

        /// Returns the number of bits used for decoder/encoder buffer delay fields.
        ///
        /// @return the decoder/encoder buffer delay field width
        public int encoderDecoderBufferDelayLength() {
            return encoderDecoderBufferDelayLength;
        }

        /// Returns the AV1 `num_units_in_decoding_tick` value.
        ///
        /// @return the AV1 `num_units_in_decoding_tick` value
        public long numUnitsInDecodingTick() {
            return numUnitsInDecodingTick;
        }

        /// Returns the number of bits used for buffer removal delay fields.
        ///
        /// @return the buffer removal delay field width
        public int bufferRemovalDelayLength() {
            return bufferRemovalDelayLength;
        }

        /// Returns the number of bits used for frame presentation delay fields.
        ///
        /// @return the frame presentation delay field width
        public int framePresentationDelayLength() {
            return framePresentationDelayLength;
        }

        /// Returns whether display model info is present.
        ///
        /// @return whether display model info is present
        public boolean displayModelInfoPresent() {
            return displayModelInfoPresent;
        }
    }

    /// Operating point data declared by the sequence.
    
    public static final class OperatingPoint {
        /// The AV1 major level.
        private final int majorLevel;
        /// The AV1 minor level.
        private final int minorLevel;
        /// The initial display delay, or `10` when not signaled.
        private final int initialDisplayDelay;
        /// The AV1 operating point IDC mask.
        private final int idc;
        /// Whether the operating point uses high tier.
        private final boolean tier;
        /// Whether decoder model parameters are present for this operating point.
        private final boolean decoderModelParamPresent;
        /// Whether display model parameters are present for this operating point.
        private final boolean displayModelParamPresent;
        /// The optional operating parameter info for this operating point.
        private final OperatingParameterInfo operatingParameterInfo;

        /// Creates an operating point description.
        ///
        /// @param majorLevel the AV1 major level
        /// @param minorLevel the AV1 minor level
        /// @param initialDisplayDelay the initial display delay, or `10` when not signaled
        /// @param idc the AV1 operating point IDC mask
        /// @param tier whether the operating point uses high tier
        /// @param decoderModelParamPresent whether decoder model parameters are present
        /// @param displayModelParamPresent whether display model parameters are present
        /// @param operatingParameterInfo the optional operating parameter info
        public OperatingPoint(
                int majorLevel,
                int minorLevel,
                int initialDisplayDelay,
                int idc,
                boolean tier,
                boolean decoderModelParamPresent,
                boolean displayModelParamPresent,
                OperatingParameterInfo operatingParameterInfo
        ) {
            this.majorLevel = majorLevel;
            this.minorLevel = minorLevel;
            this.initialDisplayDelay = initialDisplayDelay;
            this.idc = idc;
            this.tier = tier;
            this.decoderModelParamPresent = decoderModelParamPresent;
            this.displayModelParamPresent = displayModelParamPresent;
            this.operatingParameterInfo = operatingParameterInfo;
        }

        /// Returns the AV1 major level.
        ///
        /// @return the AV1 major level
        public int majorLevel() {
            return majorLevel;
        }

        /// Returns the AV1 minor level.
        ///
        /// @return the AV1 minor level
        public int minorLevel() {
            return minorLevel;
        }

        /// Returns the initial display delay, or `10` when not signaled.
        ///
        /// @return the initial display delay
        public int initialDisplayDelay() {
            return initialDisplayDelay;
        }

        /// Returns the AV1 operating point IDC mask.
        ///
        /// @return the AV1 operating point IDC mask
        public int idc() {
            return idc;
        }

        /// Returns whether the operating point uses high tier.
        ///
        /// @return whether the operating point uses high tier
        public boolean tier() {
            return tier;
        }

        /// Returns whether decoder model parameters are present.
        ///
        /// @return whether decoder model parameters are present
        public boolean decoderModelParamPresent() {
            return decoderModelParamPresent;
        }

        /// Returns whether display model parameters are present.
        ///
        /// @return whether display model parameters are present
        public boolean displayModelParamPresent() {
            return displayModelParamPresent;
        }

        /// Returns the optional operating parameter info.
        ///
        /// @return the optional operating parameter info, or `null`
        public OperatingParameterInfo operatingParameterInfo() {
            return operatingParameterInfo;
        }
    }

    /// Operating parameter info declared for an operating point.
    
    public static final class OperatingParameterInfo {
        /// The decoder buffer delay.
        private final long decoderBufferDelay;
        /// The encoder buffer delay.
        private final long encoderBufferDelay;
        /// Whether low-delay mode is enabled.
        private final boolean lowDelayMode;

        /// Creates operating parameter info.
        ///
        /// @param decoderBufferDelay the decoder buffer delay
        /// @param encoderBufferDelay the encoder buffer delay
        /// @param lowDelayMode whether low-delay mode is enabled
        public OperatingParameterInfo(long decoderBufferDelay, long encoderBufferDelay, boolean lowDelayMode) {
            this.decoderBufferDelay = decoderBufferDelay;
            this.encoderBufferDelay = encoderBufferDelay;
            this.lowDelayMode = lowDelayMode;
        }

        /// Returns the decoder buffer delay.
        ///
        /// @return the decoder buffer delay
        public long decoderBufferDelay() {
            return decoderBufferDelay;
        }

        /// Returns the encoder buffer delay.
        ///
        /// @return the encoder buffer delay
        public long encoderBufferDelay() {
            return encoderBufferDelay;
        }

        /// Returns whether low-delay mode is enabled.
        ///
        /// @return whether low-delay mode is enabled
        public boolean lowDelayMode() {
            return lowDelayMode;
        }
    }

    /// Feature flags declared by the sequence.
    
    public static final class FeatureConfig {
        /// Whether 128x128 superblocks are used.
        private final boolean use128x128Superblocks;
        /// Whether filter intra prediction is enabled.
        private final boolean filterIntra;
        /// Whether intra edge filtering is enabled.
        private final boolean intraEdgeFilter;
        /// Whether inter-intra prediction is enabled.
        private final boolean interIntra;
        /// Whether masked compound prediction is enabled.
        private final boolean maskedCompound;
        /// Whether warped motion is enabled.
        private final boolean warpedMotion;
        /// Whether dual filtering is enabled.
        private final boolean dualFilter;
        /// Whether order hint signaling is enabled.
        private final boolean orderHint;
        /// Whether joint compound prediction is enabled.
        private final boolean jointCompound;
        /// Whether reference frame motion vectors are enabled.
        private final boolean refFrameMotionVectors;
        /// The screen content tools mode.
        private final AdaptiveBoolean screenContentTools;
        /// The force integer motion vector mode.
        private final AdaptiveBoolean forceIntegerMotionVectors;
        /// The number of bits used for order hints.
        private final int orderHintBits;
        /// Whether super-resolution is enabled.
        private final boolean superResolution;
        /// Whether CDEF is enabled.
        private final boolean cdef;
        /// Whether loop restoration is enabled.
        private final boolean restoration;
        /// Whether film grain signaling is enabled.
        private final boolean filmGrainPresent;

        /// Creates a feature configuration.
        ///
        /// @param use128x128Superblocks whether 128x128 superblocks are used
        /// @param filterIntra whether filter intra prediction is enabled
        /// @param intraEdgeFilter whether intra edge filtering is enabled
        /// @param interIntra whether inter-intra prediction is enabled
        /// @param maskedCompound whether masked compound prediction is enabled
        /// @param warpedMotion whether warped motion is enabled
        /// @param dualFilter whether dual filtering is enabled
        /// @param orderHint whether order hint signaling is enabled
        /// @param jointCompound whether joint compound prediction is enabled
        /// @param refFrameMotionVectors whether reference frame motion vectors are enabled
        /// @param screenContentTools the screen content tools mode
        /// @param forceIntegerMotionVectors the force integer motion vector mode
        /// @param orderHintBits the number of bits used for order hints
        /// @param superResolution whether super-resolution is enabled
        /// @param cdef whether CDEF is enabled
        /// @param restoration whether loop restoration is enabled
        /// @param filmGrainPresent whether film grain signaling is enabled
        public FeatureConfig(
                boolean use128x128Superblocks,
                boolean filterIntra,
                boolean intraEdgeFilter,
                boolean interIntra,
                boolean maskedCompound,
                boolean warpedMotion,
                boolean dualFilter,
                boolean orderHint,
                boolean jointCompound,
                boolean refFrameMotionVectors,
                AdaptiveBoolean screenContentTools,
                AdaptiveBoolean forceIntegerMotionVectors,
                int orderHintBits,
                boolean superResolution,
                boolean cdef,
                boolean restoration,
                boolean filmGrainPresent
        ) {
            this.use128x128Superblocks = use128x128Superblocks;
            this.filterIntra = filterIntra;
            this.intraEdgeFilter = intraEdgeFilter;
            this.interIntra = interIntra;
            this.maskedCompound = maskedCompound;
            this.warpedMotion = warpedMotion;
            this.dualFilter = dualFilter;
            this.orderHint = orderHint;
            this.jointCompound = jointCompound;
            this.refFrameMotionVectors = refFrameMotionVectors;
            this.screenContentTools = Objects.requireNonNull(screenContentTools, "screenContentTools");
            this.forceIntegerMotionVectors = Objects.requireNonNull(forceIntegerMotionVectors, "forceIntegerMotionVectors");
            this.orderHintBits = orderHintBits;
            this.superResolution = superResolution;
            this.cdef = cdef;
            this.restoration = restoration;
            this.filmGrainPresent = filmGrainPresent;
        }

        /// Returns whether 128x128 superblocks are used.
        ///
        /// @return whether 128x128 superblocks are used
        public boolean use128x128Superblocks() {
            return use128x128Superblocks;
        }

        /// Returns whether filter intra prediction is enabled.
        ///
        /// @return whether filter intra prediction is enabled
        public boolean filterIntra() {
            return filterIntra;
        }

        /// Returns whether intra edge filtering is enabled.
        ///
        /// @return whether intra edge filtering is enabled
        public boolean intraEdgeFilter() {
            return intraEdgeFilter;
        }

        /// Returns whether inter-intra prediction is enabled.
        ///
        /// @return whether inter-intra prediction is enabled
        public boolean interIntra() {
            return interIntra;
        }

        /// Returns whether masked compound prediction is enabled.
        ///
        /// @return whether masked compound prediction is enabled
        public boolean maskedCompound() {
            return maskedCompound;
        }

        /// Returns whether warped motion is enabled.
        ///
        /// @return whether warped motion is enabled
        public boolean warpedMotion() {
            return warpedMotion;
        }

        /// Returns whether dual filtering is enabled.
        ///
        /// @return whether dual filtering is enabled
        public boolean dualFilter() {
            return dualFilter;
        }

        /// Returns whether order hint signaling is enabled.
        ///
        /// @return whether order hint signaling is enabled
        public boolean orderHint() {
            return orderHint;
        }

        /// Returns whether joint compound prediction is enabled.
        ///
        /// @return whether joint compound prediction is enabled
        public boolean jointCompound() {
            return jointCompound;
        }

        /// Returns whether reference frame motion vectors are enabled.
        ///
        /// @return whether reference frame motion vectors are enabled
        public boolean refFrameMotionVectors() {
            return refFrameMotionVectors;
        }

        /// Returns the screen content tools mode.
        ///
        /// @return the screen content tools mode
        public AdaptiveBoolean screenContentTools() {
            return screenContentTools;
        }

        /// Returns the force integer motion vector mode.
        ///
        /// @return the force integer motion vector mode
        public AdaptiveBoolean forceIntegerMotionVectors() {
            return forceIntegerMotionVectors;
        }

        /// Returns the number of bits used for order hints.
        ///
        /// @return the number of bits used for order hints
        public int orderHintBits() {
            return orderHintBits;
        }

        /// Returns whether super-resolution is enabled.
        ///
        /// @return whether super-resolution is enabled
        public boolean superResolution() {
            return superResolution;
        }

        /// Returns whether CDEF is enabled.
        ///
        /// @return whether CDEF is enabled
        public boolean cdef() {
            return cdef;
        }

        /// Returns whether loop restoration is enabled.
        ///
        /// @return whether loop restoration is enabled
        public boolean restoration() {
            return restoration;
        }

        /// Returns whether film grain signaling is enabled.
        ///
        /// @return whether film grain signaling is enabled
        public boolean filmGrainPresent() {
            return filmGrainPresent;
        }
    }
}
