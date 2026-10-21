// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.model;

import pzopt.vendor.avif.av1.Av1FrameType;

import java.util.Arrays;
import java.util.Objects;

/// Parsed AV1 frame header data.

public final class FrameHeader {
    /// The temporal layer identifier copied from the OBU header.
    private final int temporalId;
    /// The spatial layer identifier copied from the OBU header.
    private final int spatialId;
    /// Whether this is a show-existing-frame header.
    private final boolean showExistingFrame;
    /// The referenced frame slot when `showExistingFrame` is true.
    private final int existingFrameIndex;
    /// The frame identifier when present.
    private final long frameId;
    /// The frame presentation delay when present.
    private final long framePresentationDelay;
    /// The AV1 frame type.
    private final Av1FrameType frameType;
    /// Whether the frame is shown immediately.
    private final boolean showFrame;
    /// Whether the frame can be shown by a later show-existing-frame header.
    private final boolean showableFrame;
    /// Whether error resilient mode is enabled.
    private final boolean errorResilientMode;
    /// Whether CDF updates are disabled.
    private final boolean disableCdfUpdate;
    /// Whether screen content tools are enabled for this frame.
    private final boolean allowScreenContentTools;
    /// Whether integer motion vectors are forced for this frame.
    private final boolean forceIntegerMotionVectors;
    /// Whether frame size override signaling is enabled.
    private final boolean frameSizeOverride;
    /// The primary reference frame index, or `7` when none is used.
    private final int primaryRefFrame;
    /// The frame order hint when present.
    private final int frameOffset;
    /// The refresh frame flags bitset.
    private final int refreshFrameFlags;
    /// Whether short signaling was used to derive the reference-frame list.
    private final boolean frameReferenceShortSignaling;
    /// The decoded reference-frame slot indices for LAST..ALTREF.
    private final int [] referenceFrameIndices;
    /// The frame dimensions and render size.
    private final FrameSize frameSize;
    /// The super-resolution settings for the frame.
    private final SuperResolutionInfo superResolution;
    /// Whether `allow_intrabc` is enabled.
    private final boolean allowIntrabc;
    /// Whether high-precision motion vectors are allowed.
    private final boolean allowHighPrecisionMotionVectors;
    /// The frame-level interpolation filter mode for inter prediction.
    private final InterpolationFilter subpelFilterMode;
    /// Whether motion mode signaling is switchable.
    private final boolean switchableMotionMode;
    /// Whether reference-frame motion vectors are enabled.
    private final boolean useReferenceFrameMotionVectors;
    /// Whether context refresh is enabled.
    private final boolean refreshContext;
    /// The tile layout information.
    private final TilingInfo tiling;
    /// The quantization parameters.
    private final QuantizationInfo quantization;
    /// The segmentation parameters.
    private final SegmentationInfo segmentation;
    /// The delta-q and delta-lf signaling flags.
    private final DeltaInfo delta;
    /// Whether all segments are lossless.
    private final boolean allLossless;
    /// The loop filter parameters.
    private final LoopFilterInfo loopFilter;
    /// The CDEF parameters.
    private final CdefInfo cdef;
    /// The loop restoration parameters.
    private final RestorationInfo restoration;
    /// The transform mode for this frame.
    private final TransformMode transformMode;
    /// Whether compound reference mode is switchable for inter prediction.
    private final boolean switchableCompoundReferences;
    /// Whether skip mode is permitted for this frame.
    private final boolean skipModeAllowed;
    /// Whether skip mode is enabled for this frame.
    private final boolean skipModeEnabled;
    /// The two reference indices used by skip mode, or `-1` when unavailable.
    private final int [] skipModeReferenceIndices;
    /// Whether warped motion is enabled for this frame.
    private final boolean warpedMotion;
    /// Whether the reduced transform type set is used.
    private final boolean reducedTransformSet;
    /// The decoded global-motion parameters for LAST through ALTREF.
    private final GlobalMotionParams [] globalMotionParameters;
    /// The normalized film grain parameters for this frame.
    private final FilmGrainParams filmGrain;

    /// Creates a parsed frame header with inter-specific fields defaulted to disabled values and normalized film grain parameters.
    ///
    /// @param temporalId the temporal layer identifier
    /// @param spatialId the spatial layer identifier
    /// @param showExistingFrame whether this is a show-existing-frame header
    /// @param existingFrameIndex the referenced frame slot when `showExistingFrame` is true
    /// @param frameId the frame identifier when present
    /// @param framePresentationDelay the frame presentation delay when present
    /// @param frameType the AV1 frame type
    /// @param showFrame whether the frame is shown immediately
    /// @param showableFrame whether the frame can be shown later
    /// @param errorResilientMode whether error resilient mode is enabled
    /// @param disableCdfUpdate whether CDF updates are disabled
    /// @param allowScreenContentTools whether screen content tools are enabled
    /// @param forceIntegerMotionVectors whether integer motion vectors are forced
    /// @param frameSizeOverride whether frame size override signaling is enabled
    /// @param primaryRefFrame the primary reference frame index, or `7` when none is used
    /// @param frameOffset the frame order hint when present
    /// @param refreshFrameFlags the refresh frame flags bitset
    /// @param frameSize the frame dimensions and render size
    /// @param superResolution the super-resolution settings
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param refreshContext whether context refresh is enabled
    /// @param tiling the tile layout information
    /// @param quantization the quantization parameters
    /// @param segmentation the segmentation parameters
    /// @param delta the delta-q and delta-lf signaling flags
    /// @param allLossless whether all segments are lossless
    /// @param loopFilter the loop filter parameters
    /// @param cdef the CDEF parameters
    /// @param restoration the loop restoration parameters
    /// @param transformMode the transform mode for this frame
    /// @param reducedTransformSet whether the reduced transform type set is used
    /// @param filmGrain the normalized film grain parameters
    public FrameHeader(
            int temporalId,
            int spatialId,
            boolean showExistingFrame,
            int existingFrameIndex,
            long frameId,
            long framePresentationDelay,
            Av1FrameType frameType,
            boolean showFrame,
            boolean showableFrame,
            boolean errorResilientMode,
            boolean disableCdfUpdate,
            boolean allowScreenContentTools,
            boolean forceIntegerMotionVectors,
            boolean frameSizeOverride,
            int primaryRefFrame,
            int frameOffset,
            int refreshFrameFlags,
            FrameSize frameSize,
            SuperResolutionInfo superResolution,
            boolean allowIntrabc,
            boolean refreshContext,
            TilingInfo tiling,
            QuantizationInfo quantization,
            SegmentationInfo segmentation,
            DeltaInfo delta,
            boolean allLossless,
            LoopFilterInfo loopFilter,
            CdefInfo cdef,
            RestorationInfo restoration,
            TransformMode transformMode,
            boolean reducedTransformSet,
            FilmGrainParams filmGrain
    ) {
        this(
                temporalId,
                spatialId,
                showExistingFrame,
                existingFrameIndex,
                frameId,
                framePresentationDelay,
                frameType,
                showFrame,
                showableFrame,
                errorResilientMode,
                disableCdfUpdate,
                allowScreenContentTools,
                forceIntegerMotionVectors,
                frameSizeOverride,
                primaryRefFrame,
                frameOffset,
                refreshFrameFlags,
                false,
                new int[]{-1, -1, -1, -1, -1, -1, -1},
                frameSize,
                superResolution,
                allowIntrabc,
                false,
                InterpolationFilter.EIGHT_TAP_REGULAR,
                false,
                false,
                refreshContext,
                tiling,
                quantization,
                segmentation,
                delta,
                allLossless,
                loopFilter,
                cdef,
                restoration,
                transformMode,
                false,
                false,
                false,
                new int[]{-1, -1},
                false,
                reducedTransformSet,
                filmGrain
        );
    }

    /// Creates a parsed frame header with normalized film grain parameters.
    ///
    /// @param temporalId the temporal layer identifier
    /// @param spatialId the spatial layer identifier
    /// @param showExistingFrame whether this is a show-existing-frame header
    /// @param existingFrameIndex the referenced frame slot when `showExistingFrame` is true
    /// @param frameId the frame identifier when present
    /// @param framePresentationDelay the frame presentation delay when present
    /// @param frameType the AV1 frame type
    /// @param showFrame whether the frame is shown immediately
    /// @param showableFrame whether the frame can be shown later
    /// @param errorResilientMode whether error resilient mode is enabled
    /// @param disableCdfUpdate whether CDF updates are disabled
    /// @param allowScreenContentTools whether screen content tools are enabled
    /// @param forceIntegerMotionVectors whether integer motion vectors are forced
    /// @param frameSizeOverride whether frame size override signaling is enabled
    /// @param primaryRefFrame the primary reference frame index, or `7` when none is used
    /// @param frameOffset the frame order hint when present
    /// @param refreshFrameFlags the refresh frame flags bitset
    /// @param frameReferenceShortSignaling whether short signaling was used for references
    /// @param referenceFrameIndices the decoded reference-frame slot indices for LAST..ALTREF
    /// @param frameSize the frame dimensions and render size
    /// @param superResolution the super-resolution settings
    /// @param allowIntrabc whether `allow_intrabc` is enabled
    /// @param allowHighPrecisionMotionVectors whether high-precision motion vectors are allowed
    /// @param subpelFilterMode the interpolation filter mode used for inter prediction
    /// @param switchableMotionMode whether motion mode signaling is switchable
    /// @param useReferenceFrameMotionVectors whether reference-frame motion vectors are enabled
    /// @param refreshContext whether context refresh is enabled
    /// @param tiling the tile layout information
    /// @param quantization the quantization parameters
    /// @param segmentation the segmentation parameters
    /// @param delta the delta-q and delta-lf signaling flags
    /// @param allLossless whether all segments are lossless
    /// @param loopFilter the loop filter parameters
    /// @param cdef the CDEF parameters
    /// @param restoration the loop restoration parameters
    /// @param transformMode the transform mode for this frame
    /// @param switchableCompoundReferences whether compound reference mode is switchable
    /// @param skipModeAllowed whether skip mode is permitted
    /// @param skipModeEnabled whether skip mode is enabled
    /// @param skipModeReferenceIndices the two skip-mode reference indices, or `-1`
    /// @param warpedMotion whether warped motion is enabled
    /// @param reducedTransformSet whether the reduced transform type set is used
    /// @param filmGrain the normalized film grain parameters
    public FrameHeader(
            int temporalId,
            int spatialId,
            boolean showExistingFrame,
            int existingFrameIndex,
            long frameId,
            long framePresentationDelay,
            Av1FrameType frameType,
            boolean showFrame,
            boolean showableFrame,
            boolean errorResilientMode,
            boolean disableCdfUpdate,
            boolean allowScreenContentTools,
            boolean forceIntegerMotionVectors,
            boolean frameSizeOverride,
            int primaryRefFrame,
            int frameOffset,
            int refreshFrameFlags,
            boolean frameReferenceShortSignaling,
            int[] referenceFrameIndices,
            FrameSize frameSize,
            SuperResolutionInfo superResolution,
            boolean allowIntrabc,
            boolean allowHighPrecisionMotionVectors,
            InterpolationFilter subpelFilterMode,
            boolean switchableMotionMode,
            boolean useReferenceFrameMotionVectors,
            boolean refreshContext,
            TilingInfo tiling,
            QuantizationInfo quantization,
            SegmentationInfo segmentation,
            DeltaInfo delta,
            boolean allLossless,
            LoopFilterInfo loopFilter,
            CdefInfo cdef,
            RestorationInfo restoration,
            TransformMode transformMode,
            boolean switchableCompoundReferences,
            boolean skipModeAllowed,
            boolean skipModeEnabled,
            int[] skipModeReferenceIndices,
            boolean warpedMotion,
            boolean reducedTransformSet,
            FilmGrainParams filmGrain
    ) {
        this.temporalId = temporalId;
        this.spatialId = spatialId;
        this.showExistingFrame = showExistingFrame;
        this.existingFrameIndex = existingFrameIndex;
        this.frameId = frameId;
        this.framePresentationDelay = framePresentationDelay;
        this.frameType = Objects.requireNonNull(frameType, "frameType");
        this.showFrame = showFrame;
        this.showableFrame = showableFrame;
        this.errorResilientMode = errorResilientMode;
        this.disableCdfUpdate = disableCdfUpdate;
        this.allowScreenContentTools = allowScreenContentTools;
        this.forceIntegerMotionVectors = forceIntegerMotionVectors;
        this.frameSizeOverride = frameSizeOverride;
        this.primaryRefFrame = primaryRefFrame;
        this.frameOffset = frameOffset;
        this.refreshFrameFlags = refreshFrameFlags;
        this.frameReferenceShortSignaling = frameReferenceShortSignaling;
        if (referenceFrameIndices.length != 7) {
            throw new IllegalArgumentException("referenceFrameIndices.length != 7: " + referenceFrameIndices.length);
        }
        this.referenceFrameIndices = Arrays.copyOf(referenceFrameIndices, referenceFrameIndices.length);
        this.frameSize = Objects.requireNonNull(frameSize, "frameSize");
        this.superResolution = Objects.requireNonNull(superResolution, "superResolution");
        this.allowIntrabc = allowIntrabc;
        this.allowHighPrecisionMotionVectors = allowHighPrecisionMotionVectors;
        this.subpelFilterMode = Objects.requireNonNull(subpelFilterMode, "subpelFilterMode");
        this.switchableMotionMode = switchableMotionMode;
        this.useReferenceFrameMotionVectors = useReferenceFrameMotionVectors;
        this.refreshContext = refreshContext;
        this.tiling = Objects.requireNonNull(tiling, "tiling");
        this.quantization = Objects.requireNonNull(quantization, "quantization");
        this.segmentation = Objects.requireNonNull(segmentation, "segmentation");
        this.delta = Objects.requireNonNull(delta, "delta");
        this.allLossless = allLossless;
        this.loopFilter = Objects.requireNonNull(loopFilter, "loopFilter");
        this.cdef = Objects.requireNonNull(cdef, "cdef");
        this.restoration = Objects.requireNonNull(restoration, "restoration");
        this.transformMode = Objects.requireNonNull(transformMode, "transformMode");
        this.switchableCompoundReferences = switchableCompoundReferences;
        this.skipModeAllowed = skipModeAllowed;
        this.skipModeEnabled = skipModeEnabled;
        if (skipModeReferenceIndices.length != 2) {
            throw new IllegalArgumentException("skipModeReferenceIndices.length != 2: " + skipModeReferenceIndices.length);
        }
        this.skipModeReferenceIndices = Arrays.copyOf(skipModeReferenceIndices, skipModeReferenceIndices.length);
        this.warpedMotion = warpedMotion;
        this.reducedTransformSet = reducedTransformSet;
        this.globalMotionParameters = defaultGlobalMotionParameters();
        this.filmGrain = Objects.requireNonNull(filmGrain, "filmGrain");
    }

    /// Creates a frame header by replacing the global-motion state of an already parsed header.
    ///
    /// All other immutable state is copied from `source`. The supplied array is defensively copied.
    ///
    /// @param source the parsed frame header whose remaining state is copied
    /// @param globalMotionParameters the seven global-motion parameter sets for LAST through ALTREF
    private FrameHeader(FrameHeader source, GlobalMotionParams[] globalMotionParameters) {
        FrameHeader nonNullSource = Objects.requireNonNull(source, "source");
        this.temporalId = nonNullSource.temporalId;
        this.spatialId = nonNullSource.spatialId;
        this.showExistingFrame = nonNullSource.showExistingFrame;
        this.existingFrameIndex = nonNullSource.existingFrameIndex;
        this.frameId = nonNullSource.frameId;
        this.framePresentationDelay = nonNullSource.framePresentationDelay;
        this.frameType = nonNullSource.frameType;
        this.showFrame = nonNullSource.showFrame;
        this.showableFrame = nonNullSource.showableFrame;
        this.errorResilientMode = nonNullSource.errorResilientMode;
        this.disableCdfUpdate = nonNullSource.disableCdfUpdate;
        this.allowScreenContentTools = nonNullSource.allowScreenContentTools;
        this.forceIntegerMotionVectors = nonNullSource.forceIntegerMotionVectors;
        this.frameSizeOverride = nonNullSource.frameSizeOverride;
        this.primaryRefFrame = nonNullSource.primaryRefFrame;
        this.frameOffset = nonNullSource.frameOffset;
        this.refreshFrameFlags = nonNullSource.refreshFrameFlags;
        this.frameReferenceShortSignaling = nonNullSource.frameReferenceShortSignaling;
        this.referenceFrameIndices = Arrays.copyOf(
                nonNullSource.referenceFrameIndices,
                nonNullSource.referenceFrameIndices.length
        );
        this.frameSize = nonNullSource.frameSize;
        this.superResolution = nonNullSource.superResolution;
        this.allowIntrabc = nonNullSource.allowIntrabc;
        this.allowHighPrecisionMotionVectors = nonNullSource.allowHighPrecisionMotionVectors;
        this.subpelFilterMode = nonNullSource.subpelFilterMode;
        this.switchableMotionMode = nonNullSource.switchableMotionMode;
        this.useReferenceFrameMotionVectors = nonNullSource.useReferenceFrameMotionVectors;
        this.refreshContext = nonNullSource.refreshContext;
        this.tiling = nonNullSource.tiling;
        this.quantization = nonNullSource.quantization;
        this.segmentation = nonNullSource.segmentation;
        this.delta = nonNullSource.delta;
        this.allLossless = nonNullSource.allLossless;
        this.loopFilter = nonNullSource.loopFilter;
        this.cdef = nonNullSource.cdef;
        this.restoration = nonNullSource.restoration;
        this.transformMode = nonNullSource.transformMode;
        this.switchableCompoundReferences = nonNullSource.switchableCompoundReferences;
        this.skipModeAllowed = nonNullSource.skipModeAllowed;
        this.skipModeEnabled = nonNullSource.skipModeEnabled;
        this.skipModeReferenceIndices = Arrays.copyOf(
                nonNullSource.skipModeReferenceIndices,
                nonNullSource.skipModeReferenceIndices.length
        );
        this.warpedMotion = nonNullSource.warpedMotion;
        this.reducedTransformSet = nonNullSource.reducedTransformSet;
        Objects.requireNonNull(globalMotionParameters, "globalMotionParameters");
        if (globalMotionParameters.length != 7) {
            throw new IllegalArgumentException(
                    "globalMotionParameters.length != 7: " + globalMotionParameters.length
            );
        }
        this.globalMotionParameters = new GlobalMotionParams[globalMotionParameters.length];
        for (int i = 0; i < globalMotionParameters.length; i++) {
            this.globalMotionParameters[i] = Objects.requireNonNull(
                    globalMotionParameters[i],
                    "globalMotionParameters[" + i + "]"
            );
        }
        this.filmGrain = nonNullSource.filmGrain;
    }

    /// Returns the temporal layer identifier copied from the OBU header.
    ///
    /// @return the temporal layer identifier
    public int temporalId() {
        return temporalId;
    }

    /// Returns the spatial layer identifier copied from the OBU header.
    ///
    /// @return the spatial layer identifier
    public int spatialId() {
        return spatialId;
    }

    /// Returns whether this is a show-existing-frame header.
    ///
    /// @return whether this is a show-existing-frame header
    public boolean showExistingFrame() {
        return showExistingFrame;
    }

    /// Returns the referenced frame slot when `showExistingFrame` is true.
    ///
    /// @return the referenced frame slot
    public int existingFrameIndex() {
        return existingFrameIndex;
    }

    /// Returns the frame identifier when present.
    ///
    /// @return the frame identifier
    public long frameId() {
        return frameId;
    }

    /// Returns the frame presentation delay when present.
    ///
    /// @return the frame presentation delay
    public long framePresentationDelay() {
        return framePresentationDelay;
    }

    /// Returns the AV1 frame type.
    ///
    /// @return the AV1 frame type
    public Av1FrameType frameType() {
        return frameType;
    }

    /// Returns whether the frame is shown immediately.
    ///
    /// @return whether the frame is shown immediately
    public boolean showFrame() {
        return showFrame;
    }

    /// Returns whether the frame can be shown later.
    ///
    /// @return whether the frame can be shown later
    public boolean showableFrame() {
        return showableFrame;
    }

    /// Returns whether error resilient mode is enabled.
    ///
    /// @return whether error resilient mode is enabled
    public boolean errorResilientMode() {
        return errorResilientMode;
    }

    /// Returns whether CDF updates are disabled.
    ///
    /// @return whether CDF updates are disabled
    public boolean disableCdfUpdate() {
        return disableCdfUpdate;
    }

    /// Returns whether screen content tools are enabled for this frame.
    ///
    /// @return whether screen content tools are enabled
    public boolean allowScreenContentTools() {
        return allowScreenContentTools;
    }

    /// Returns whether integer motion vectors are forced for this frame.
    ///
    /// @return whether integer motion vectors are forced
    public boolean forceIntegerMotionVectors() {
        return forceIntegerMotionVectors;
    }

    /// Returns whether frame size override signaling is enabled.
    ///
    /// @return whether frame size override signaling is enabled
    public boolean frameSizeOverride() {
        return frameSizeOverride;
    }

    /// Returns the primary reference frame index, or `7` when none is used.
    ///
    /// @return the primary reference frame index
    public int primaryRefFrame() {
        return primaryRefFrame;
    }

    /// Returns the frame order hint when present.
    ///
    /// @return the frame order hint
    public int frameOffset() {
        return frameOffset;
    }

    /// Returns the refresh frame flags bitset.
    ///
    /// @return the refresh frame flags bitset
    public int refreshFrameFlags() {
        return refreshFrameFlags;
    }

    /// Returns whether short signaling was used to derive the reference-frame list.
    ///
    /// @return whether short signaling was used to derive the reference-frame list
    public boolean frameReferenceShortSignaling() {
        return frameReferenceShortSignaling;
    }

    /// Returns a decoded reference-frame slot index by position.
    ///
    /// @param index the zero-based LAST..ALTREF position
    /// @return the decoded reference-frame slot index
    public int referenceFrameIndex(int index) {
        return referenceFrameIndices[index];
    }

    /// Returns the frame dimensions and render size.
    ///
    /// @return the frame dimensions and render size
    public FrameSize frameSize() {
        return frameSize;
    }

    /// Returns the super-resolution settings for the frame.
    ///
    /// @return the super-resolution settings for the frame
    public SuperResolutionInfo superResolution() {
        return superResolution;
    }

    /// Returns whether `allow_intrabc` is enabled.
    ///
    /// @return whether `allow_intrabc` is enabled
    public boolean allowIntrabc() {
        return allowIntrabc;
    }

    /// Returns whether high-precision motion vectors are allowed.
    ///
    /// @return whether high-precision motion vectors are allowed
    public boolean allowHighPrecisionMotionVectors() {
        return allowHighPrecisionMotionVectors;
    }

    /// Returns the interpolation filter mode used for inter prediction.
    ///
    /// @return the interpolation filter mode used for inter prediction
    public InterpolationFilter subpelFilterMode() {
        return subpelFilterMode;
    }

    /// Returns whether motion mode signaling is switchable.
    ///
    /// @return whether motion mode signaling is switchable
    public boolean switchableMotionMode() {
        return switchableMotionMode;
    }

    /// Returns whether reference-frame motion vectors are enabled.
    ///
    /// @return whether reference-frame motion vectors are enabled
    public boolean useReferenceFrameMotionVectors() {
        return useReferenceFrameMotionVectors;
    }

    /// Returns whether context refresh is enabled.
    ///
    /// @return whether context refresh is enabled
    public boolean refreshContext() {
        return refreshContext;
    }

    /// Returns the tile layout information.
    ///
    /// @return the tile layout information
    public TilingInfo tiling() {
        return tiling;
    }

    /// Returns the quantization parameters.
    ///
    /// @return the quantization parameters
    public QuantizationInfo quantization() {
        return quantization;
    }

    /// Returns the segmentation parameters.
    ///
    /// @return the segmentation parameters
    public SegmentationInfo segmentation() {
        return segmentation;
    }

    /// Returns the delta-q and delta-lf signaling flags.
    ///
    /// @return the delta-q and delta-lf signaling flags
    public DeltaInfo delta() {
        return delta;
    }

    /// Returns whether all segments are lossless.
    ///
    /// @return whether all segments are lossless
    public boolean allLossless() {
        return allLossless;
    }

    /// Returns the loop filter parameters.
    ///
    /// @return the loop filter parameters
    public LoopFilterInfo loopFilter() {
        return loopFilter;
    }

    /// Returns the CDEF parameters.
    ///
    /// @return the CDEF parameters
    public CdefInfo cdef() {
        return cdef;
    }

    /// Returns the loop restoration parameters.
    ///
    /// @return the loop restoration parameters
    public RestorationInfo restoration() {
        return restoration;
    }

    /// Returns the transform mode for this frame.
    ///
    /// @return the transform mode for this frame
    public TransformMode transformMode() {
        return transformMode;
    }

    /// Returns whether compound reference mode is switchable for inter prediction.
    ///
    /// @return whether compound reference mode is switchable for inter prediction
    public boolean switchableCompoundReferences() {
        return switchableCompoundReferences;
    }

    /// Returns whether skip mode is permitted for this frame.
    ///
    /// @return whether skip mode is permitted for this frame
    public boolean skipModeAllowed() {
        return skipModeAllowed;
    }

    /// Returns whether skip mode is enabled for this frame.
    ///
    /// @return whether skip mode is enabled for this frame
    public boolean skipModeEnabled() {
        return skipModeEnabled;
    }

    /// Returns one skip-mode reference index by position.
    ///
    /// @param index the zero-based skip-mode reference position
    /// @return the skip-mode reference index, or `-1`
    public int skipModeReferenceIndex(int index) {
        return skipModeReferenceIndices[index];
    }

    /// Returns whether warped motion is enabled for this frame.
    ///
    /// @return whether warped motion is enabled for this frame
    public boolean warpedMotion() {
        return warpedMotion;
    }

    /// Returns whether the reduced transform type set is used.
    ///
    /// @return whether the reduced transform type set is used
    public boolean reducedTransformSet() {
        return reducedTransformSet;
    }

    /// Returns the global-motion parameters for one LAST-through-ALTREF reference position.
    ///
    /// @param referenceFrame the zero-based reference position in `[0, 7)`
    /// @return the decoded global-motion parameters for the supplied reference
    public GlobalMotionParams globalMotion(int referenceFrame) {
        return globalMotionParameters[Objects.checkIndex(referenceFrame, globalMotionParameters.length)];
    }

    /// Returns this header with the supplied global-motion parameters.
    ///
    /// The receiver is not modified. The supplied array is defensively copied into the returned
    /// header.
    ///
    /// @param parameters the seven global-motion parameter sets for LAST through ALTREF
    /// @return a copied header carrying the supplied global-motion parameters
    public FrameHeader withGlobalMotionParameters(GlobalMotionParams[] parameters) {
        return new FrameHeader(this, parameters);
    }

    /// Returns the normalized film grain parameters for this frame.
    ///
    /// @return the normalized film grain parameters for this frame
    public FilmGrainParams filmGrain() {
        return filmGrain;
    }

    /// Creates identity global-motion state for all seven inter references.
    ///
    /// @return seven identity global-motion parameter sets
    private static GlobalMotionParams[] defaultGlobalMotionParameters() {
        GlobalMotionParams[] parameters = new GlobalMotionParams[7];
        Arrays.fill(parameters, GlobalMotionParams.identity());
        return parameters;
    }

    /// Transform mode values used by AV1 frame headers.
    
    public enum TransformMode {
        /// Lossless transform mode that restricts transforms to 4x4.
        FOUR_BY_FOUR_ONLY,
        /// Largest-allowed transform mode.
        LARGEST,
        /// Switchable transform mode.
        SWITCHABLE
    }

    /// Interpolation filter modes used by AV1 inter prediction.
    
    public enum InterpolationFilter {
        /// Regular 8-tap interpolation.
        EIGHT_TAP_REGULAR,
        /// Smooth 8-tap interpolation.
        EIGHT_TAP_SMOOTH,
        /// Sharp 8-tap interpolation.
        EIGHT_TAP_SHARP,
        /// Bilinear interpolation.
        BILINEAR,
        /// Switchable interpolation selected per block.
        SWITCHABLE
    }

    /// Global warped-motion model types signaled by AV1 frame headers.
    
    public enum GlobalMotionType {
        /// No global displacement is applied.
        IDENTITY,
        /// A translation-only model is applied.
        TRANSLATION,
        /// A constrained rotation-and-zoom model is applied.
        ROTATION_ZOOM,
        /// A general affine model is applied.
        AFFINE
    }

    /// Immutable AV1 global-motion parameters for one inter reference.
    
    public static final class GlobalMotionParams {
        /// The identity affine matrix in AV1 fixed-point representation.
        private static final int [] IDENTITY_MATRIX = {0, 0, 1 << 16, 0, 0, 1 << 16};

        /// The shared immutable identity parameter set.
        private static final GlobalMotionParams IDENTITY =
                new GlobalMotionParams(GlobalMotionType.IDENTITY, IDENTITY_MATRIX);

        /// The decoded global-motion model type.
        private final GlobalMotionType type;

        /// The six AV1 affine matrix entries in fixed-point representation.
        private final int [] matrix;

        /// Creates global-motion parameters.
        ///
        /// The supplied matrix is defensively copied.
        ///
        /// @param type the decoded global-motion model type
        /// @param matrix the six AV1 affine matrix entries
        public GlobalMotionParams(GlobalMotionType type, int[] matrix) {
            this.type = Objects.requireNonNull(type, "type");
            Objects.requireNonNull(matrix, "matrix");
            if (matrix.length != 6) {
                throw new IllegalArgumentException("matrix.length != 6: " + matrix.length);
            }
            this.matrix = Arrays.copyOf(matrix, matrix.length);
        }

        /// Returns the shared identity global-motion parameter set.
        ///
        /// @return the identity global-motion parameters
        public static GlobalMotionParams identity() {
            return IDENTITY;
        }

        /// Returns the decoded global-motion model type.
        ///
        /// @return the decoded global-motion model type
        public GlobalMotionType type() {
            return type;
        }

        /// Returns a copy of the six AV1 affine matrix entries.
        ///
        /// @return a copy of the six fixed-point matrix entries
        public int [] matrix() {
            return Arrays.copyOf(matrix, matrix.length);
        }

        /// Returns one AV1 affine matrix entry.
        ///
        /// @param index the zero-based matrix index in `[0, 6)`
        /// @return the requested fixed-point matrix entry
        public int matrix(int index) {
            return matrix[Objects.checkIndex(index, matrix.length)];
        }
    }

    /// Loop restoration filter types used by AV1 frame headers.
    
    public enum RestorationType {
        /// Loop restoration is disabled.
        NONE,
        /// Loop restoration chooses the filter type adaptively.
        SWITCHABLE,
        /// The Wiener restoration filter is used.
        WIENER,
        /// The self-guided restoration filter is used.
        SELF_GUIDED
    }

    /// Parsed frame dimensions and render size.
    
    public static final class FrameSize {
        /// The coded frame width after super-resolution, or the same as `upscaledWidth` when disabled.
        private final int codedWidth;
        /// The frame width before super-resolution downscaling.
        private final int upscaledWidth;
        /// The coded frame height.
        private final int height;
        /// The render width.
        private final int renderWidth;
        /// The render height.
        private final int renderHeight;

        /// Creates frame dimensions and render size information.
        ///
        /// @param codedWidth the coded frame width
        /// @param upscaledWidth the width before super-resolution downscaling
        /// @param height the coded frame height
        /// @param renderWidth the render width
        /// @param renderHeight the render height
        public FrameSize(int codedWidth, int upscaledWidth, int height, int renderWidth, int renderHeight) {
            this.codedWidth = codedWidth;
            this.upscaledWidth = upscaledWidth;
            this.height = height;
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
        }

        /// Returns the coded frame width after super-resolution.
        ///
        /// @return the coded frame width after super-resolution
        public int codedWidth() {
            return codedWidth;
        }

        /// Returns the frame width before super-resolution downscaling.
        ///
        /// @return the frame width before super-resolution downscaling
        public int upscaledWidth() {
            return upscaledWidth;
        }

        /// Returns the coded frame height.
        ///
        /// @return the coded frame height
        public int height() {
            return height;
        }

        /// Returns the render width.
        ///
        /// @return the render width
        public int renderWidth() {
            return renderWidth;
        }

        /// Returns the render height.
        ///
        /// @return the render height
        public int renderHeight() {
            return renderHeight;
        }
    }

    /// Parsed super-resolution settings.
    
    public static final class SuperResolutionInfo {
        /// Whether super-resolution is enabled for the frame.
        private final boolean enabled;
        /// The width scale denominator.
        private final int widthScaleDenominator;

        /// Creates parsed super-resolution settings.
        ///
        /// @param enabled whether super-resolution is enabled
        /// @param widthScaleDenominator the width scale denominator
        public SuperResolutionInfo(boolean enabled, int widthScaleDenominator) {
            this.enabled = enabled;
            this.widthScaleDenominator = widthScaleDenominator;
        }

        /// Returns whether super-resolution is enabled for the frame.
        ///
        /// @return whether super-resolution is enabled
        public boolean enabled() {
            return enabled;
        }

        /// Returns the width scale denominator.
        ///
        /// @return the width scale denominator
        public int widthScaleDenominator() {
            return widthScaleDenominator;
        }
    }

    /// Parsed tile layout information.
    
    public static final class TilingInfo {
        /// Whether uniform tiling is enabled.
        private final boolean uniform;
        /// The number of bytes used for tile sizes when signaled.
        private final int sizeBytes;
        /// The minimum tile column log2.
        private final int minLog2Cols;
        /// The maximum tile column log2.
        private final int maxLog2Cols;
        /// The actual tile column log2.
        private final int log2Cols;
        /// The tile column count.
        private final int columns;
        /// The minimum tile row log2.
        private final int minLog2Rows;
        /// The maximum tile row log2.
        private final int maxLog2Rows;
        /// The actual tile row log2.
        private final int log2Rows;
        /// The tile row count.
        private final int rows;
        /// The tile column start superblock coordinates.
        private final int [] columnStartSuperblocks;
        /// The tile row start superblock coordinates.
        private final int [] rowStartSuperblocks;
        /// The tile group update index.
        private final int updateTileIndex;

        /// Creates parsed tile layout information.
        ///
        /// @param uniform whether uniform tiling is enabled
        /// @param sizeBytes the number of bytes used for tile sizes when signaled
        /// @param minLog2Cols the minimum tile column log2
        /// @param maxLog2Cols the maximum tile column log2
        /// @param log2Cols the actual tile column log2
        /// @param columns the tile column count
        /// @param minLog2Rows the minimum tile row log2
        /// @param maxLog2Rows the maximum tile row log2
        /// @param log2Rows the actual tile row log2
        /// @param rows the tile row count
        /// @param columnStartSuperblocks the tile column start superblock coordinates
        /// @param rowStartSuperblocks the tile row start superblock coordinates
        /// @param updateTileIndex the tile group update index
        public TilingInfo(
                boolean uniform,
                int sizeBytes,
                int minLog2Cols,
                int maxLog2Cols,
                int log2Cols,
                int columns,
                int minLog2Rows,
                int maxLog2Rows,
                int log2Rows,
                int rows,
                int[] columnStartSuperblocks,
                int[] rowStartSuperblocks,
                int updateTileIndex
        ) {
            if (sizeBytes < 0 || sizeBytes > 4) {
                throw new IllegalArgumentException("sizeBytes out of range: " + sizeBytes);
            }
            this.uniform = uniform;
            this.sizeBytes = sizeBytes;
            this.minLog2Cols = minLog2Cols;
            this.maxLog2Cols = maxLog2Cols;
            this.log2Cols = log2Cols;
            this.columns = columns;
            this.minLog2Rows = minLog2Rows;
            this.maxLog2Rows = maxLog2Rows;
            this.log2Rows = log2Rows;
            this.rows = rows;
            this.columnStartSuperblocks = Arrays.copyOf(columnStartSuperblocks, columnStartSuperblocks.length);
            this.rowStartSuperblocks = Arrays.copyOf(rowStartSuperblocks, rowStartSuperblocks.length);
            this.updateTileIndex = updateTileIndex;
        }

        /// Returns whether uniform tiling is enabled.
        ///
        /// @return whether uniform tiling is enabled
        public boolean uniform() {
            return uniform;
        }

        /// Returns the number of bytes used for tile sizes when signaled.
        ///
        /// @return the number of bytes used for tile sizes when signaled
        public int sizeBytes() {
            return sizeBytes;
        }

        /// Returns the minimum tile column log2.
        ///
        /// @return the minimum tile column log2
        public int minLog2Cols() {
            return minLog2Cols;
        }

        /// Returns the maximum tile column log2.
        ///
        /// @return the maximum tile column log2
        public int maxLog2Cols() {
            return maxLog2Cols;
        }

        /// Returns the actual tile column log2.
        ///
        /// @return the actual tile column log2
        public int log2Cols() {
            return log2Cols;
        }

        /// Returns the tile column count.
        ///
        /// @return the tile column count
        public int columns() {
            return columns;
        }

        /// Returns the minimum tile row log2.
        ///
        /// @return the minimum tile row log2
        public int minLog2Rows() {
            return minLog2Rows;
        }

        /// Returns the maximum tile row log2.
        ///
        /// @return the maximum tile row log2
        public int maxLog2Rows() {
            return maxLog2Rows;
        }

        /// Returns the actual tile row log2.
        ///
        /// @return the actual tile row log2
        public int log2Rows() {
            return log2Rows;
        }

        /// Returns the tile row count.
        ///
        /// @return the tile row count
        public int rows() {
            return rows;
        }

        /// Returns one tile-column boundary in superblock coordinates.
        ///
        /// @param boundaryIndex the boundary index in `[0, columns()]`
        /// @return the selected tile-column boundary
        public int columnStartSuperblock(int boundaryIndex) {
            return columnStartSuperblocks[Objects.checkIndex(boundaryIndex, columnStartSuperblocks.length)];
        }

        /// Returns one tile-row boundary in superblock coordinates.
        ///
        /// @param boundaryIndex the boundary index in `[0, rows()]`
        /// @return the selected tile-row boundary
        public int rowStartSuperblock(int boundaryIndex) {
            return rowStartSuperblocks[Objects.checkIndex(boundaryIndex, rowStartSuperblocks.length)];
        }

        /// Returns the tile group update index.
        ///
        /// @return the tile group update index
        public int updateTileIndex() {
            return updateTileIndex;
        }
    }

    /// Parsed quantization parameters.
    
    public static final class QuantizationInfo {
        /// The base luma AC quantizer index.
        private final int baseQIndex;
        /// The luma DC delta quantizer.
        private final int yDcDelta;
        /// The chroma U DC delta quantizer.
        private final int uDcDelta;
        /// The chroma U AC delta quantizer.
        private final int uAcDelta;
        /// The chroma V DC delta quantizer.
        private final int vDcDelta;
        /// The chroma V AC delta quantizer.
        private final int vAcDelta;
        /// Whether quantization matrices are enabled.
        private final boolean useQuantizationMatrices;
        /// The luma quantization matrix index.
        private final int quantizationMatrixY;
        /// The chroma U quantization matrix index.
        private final int quantizationMatrixU;
        /// The chroma V quantization matrix index.
        private final int quantizationMatrixV;

        /// Creates parsed quantization parameters.
        ///
        /// @param baseQIndex the base luma AC quantizer index
        /// @param yDcDelta the luma DC delta quantizer
        /// @param uDcDelta the chroma U DC delta quantizer
        /// @param uAcDelta the chroma U AC delta quantizer
        /// @param vDcDelta the chroma V DC delta quantizer
        /// @param vAcDelta the chroma V AC delta quantizer
        /// @param useQuantizationMatrices whether quantization matrices are enabled
        /// @param quantizationMatrixY the luma quantization matrix index
        /// @param quantizationMatrixU the chroma U quantization matrix index
        /// @param quantizationMatrixV the chroma V quantization matrix index
        public QuantizationInfo(
                int baseQIndex,
                int yDcDelta,
                int uDcDelta,
                int uAcDelta,
                int vDcDelta,
                int vAcDelta,
                boolean useQuantizationMatrices,
                int quantizationMatrixY,
                int quantizationMatrixU,
                int quantizationMatrixV
        ) {
            this.baseQIndex = baseQIndex;
            this.yDcDelta = yDcDelta;
            this.uDcDelta = uDcDelta;
            this.uAcDelta = uAcDelta;
            this.vDcDelta = vDcDelta;
            this.vAcDelta = vAcDelta;
            this.useQuantizationMatrices = useQuantizationMatrices;
            this.quantizationMatrixY = quantizationMatrixY;
            this.quantizationMatrixU = quantizationMatrixU;
            this.quantizationMatrixV = quantizationMatrixV;
        }

        /// Returns the base luma AC quantizer index.
        ///
        /// @return the base luma AC quantizer index
        public int baseQIndex() {
            return baseQIndex;
        }

        /// Returns the luma DC delta quantizer.
        ///
        /// @return the luma DC delta quantizer
        public int yDcDelta() {
            return yDcDelta;
        }

        /// Returns the chroma U DC delta quantizer.
        ///
        /// @return the chroma U DC delta quantizer
        public int uDcDelta() {
            return uDcDelta;
        }

        /// Returns the chroma U AC delta quantizer.
        ///
        /// @return the chroma U AC delta quantizer
        public int uAcDelta() {
            return uAcDelta;
        }

        /// Returns the chroma V DC delta quantizer.
        ///
        /// @return the chroma V DC delta quantizer
        public int vDcDelta() {
            return vDcDelta;
        }

        /// Returns the chroma V AC delta quantizer.
        ///
        /// @return the chroma V AC delta quantizer
        public int vAcDelta() {
            return vAcDelta;
        }

        /// Returns whether quantization matrices are enabled.
        ///
        /// @return whether quantization matrices are enabled
        public boolean useQuantizationMatrices() {
            return useQuantizationMatrices;
        }

        /// Returns the luma quantization matrix index.
        ///
        /// @return the luma quantization matrix index
        public int quantizationMatrixY() {
            return quantizationMatrixY;
        }

        /// Returns the chroma U quantization matrix index.
        ///
        /// @return the chroma U quantization matrix index
        public int quantizationMatrixU() {
            return quantizationMatrixU;
        }

        /// Returns the chroma V quantization matrix index.
        ///
        /// @return the chroma V quantization matrix index
        public int quantizationMatrixV() {
            return quantizationMatrixV;
        }
    }

    /// Parsed segmentation parameters.
    
    public static final class SegmentationInfo {
        /// Whether segmentation is enabled.
        private final boolean enabled;
        /// Whether the segmentation map is updated.
        private final boolean updateMap;
        /// Whether temporal prediction is used for the segmentation map.
        private final boolean temporalUpdate;
        /// Whether segmentation data is updated in this frame.
        private final boolean updateData;
        /// Whether any active segment feature must be decoded before the skip flag.
        private final boolean preskip;
        /// The highest active segment index, or `-1` when all segment features are defaulted.
        private final int lastActiveSegmentId;
        /// The per-segment data array.
        private final SegmentData [] segments;
        /// The derived lossless flag for each segment.
        private final boolean [] losslessBySegment;
        /// The derived qindex for each segment.
        private final int [] qIndexBySegment;

        /// Creates parsed segmentation parameters.
        ///
        /// @param enabled whether segmentation is enabled
        /// @param updateMap whether the segmentation map is updated
        /// @param temporalUpdate whether temporal prediction is used
        /// @param updateData whether segmentation data is updated in this frame
        /// @param preskip whether any active segment feature must be decoded before the skip flag
        /// @param lastActiveSegmentId the highest active segment index, or `-1`
        /// @param segments the per-segment data array
        /// @param losslessBySegment the derived lossless flags
        /// @param qIndexBySegment the derived qindex values
        public SegmentationInfo(
                boolean enabled,
                boolean updateMap,
                boolean temporalUpdate,
                boolean updateData,
                boolean preskip,
                int lastActiveSegmentId,
                SegmentData[] segments,
                boolean[] losslessBySegment,
                int[] qIndexBySegment
        ) {
            this.enabled = enabled;
            this.updateMap = updateMap;
            this.temporalUpdate = temporalUpdate;
            this.updateData = updateData;
            this.preskip = preskip;
            this.lastActiveSegmentId = lastActiveSegmentId;
            this.segments = Arrays.copyOf(segments, segments.length);
            this.losslessBySegment = Arrays.copyOf(losslessBySegment, losslessBySegment.length);
            this.qIndexBySegment = Arrays.copyOf(qIndexBySegment, qIndexBySegment.length);
        }

        /// Creates parsed segmentation parameters with derived fields defaulted to disabled values.
        ///
        /// @param enabled whether segmentation is enabled
        /// @param updateMap whether the segmentation map is updated
        /// @param temporalUpdate whether temporal prediction is used
        /// @param updateData whether segmentation data is updated in this frame
        /// @param segments the per-segment data array
        /// @param losslessBySegment the derived lossless flags
        /// @param qIndexBySegment the derived qindex values
        public SegmentationInfo(
                boolean enabled,
                boolean updateMap,
                boolean temporalUpdate,
                boolean updateData,
                SegmentData[] segments,
                boolean[] losslessBySegment,
                int[] qIndexBySegment
        ) {
            this(enabled, updateMap, temporalUpdate, updateData, false, -1, segments, losslessBySegment, qIndexBySegment);
        }

        /// Returns whether segmentation is enabled.
        ///
        /// @return whether segmentation is enabled
        public boolean enabled() {
            return enabled;
        }

        /// Returns whether the segmentation map is updated.
        ///
        /// @return whether the segmentation map is updated
        public boolean updateMap() {
            return updateMap;
        }

        /// Returns whether temporal prediction is used for the segmentation map.
        ///
        /// @return whether temporal prediction is used
        public boolean temporalUpdate() {
            return temporalUpdate;
        }

        /// Returns whether segmentation data is updated in this frame.
        ///
        /// @return whether segmentation data is updated
        public boolean updateData() {
            return updateData;
        }

        /// Returns whether any active segment feature must be decoded before the skip flag.
        ///
        /// @return whether any active segment feature must be decoded before the skip flag
        public boolean preskip() {
            return preskip;
        }

        /// Returns the highest active segment index, or `-1` when all features are defaulted.
        ///
        /// @return the highest active segment index, or `-1`
        public int lastActiveSegmentId() {
            return lastActiveSegmentId;
        }

        /// Returns a segment description by index.
        ///
        /// @param index the zero-based segment index
        /// @return the segment description
        public SegmentData segment(int index) {
            return segments[index];
        }

        /// Returns the per-segment data array.
        ///
        /// @return the per-segment data array
        public SegmentData[] segments() {
            return Arrays.copyOf(segments, segments.length);
        }

        /// Returns the derived lossless flag for a segment.
        ///
        /// @param index the zero-based segment index
        /// @return the derived lossless flag
        public boolean lossless(int index) {
            return losslessBySegment[index];
        }

        /// Returns the derived qindex for a segment.
        ///
        /// @param index the zero-based segment index
        /// @return the derived qindex
        public int qIndex(int index) {
            return qIndexBySegment[index];
        }
    }

    /// Per-segment feature data.
    
    public static final class SegmentData {
        /// The segment delta-q value.
        private final int deltaQ;
        /// The segment luma vertical loop-filter delta.
        private final int deltaLfYVertical;
        /// The segment luma horizontal loop-filter delta.
        private final int deltaLfYHorizontal;
        /// The segment chroma U loop-filter delta.
        private final int deltaLfU;
        /// The segment chroma V loop-filter delta.
        private final int deltaLfV;
        /// The reference frame index, or `-1` when not present.
        private final int referenceFrame;
        /// Whether the skip feature is enabled.
        private final boolean skip;
        /// Whether the global motion feature is enabled.
        private final boolean globalMotion;

        /// Creates per-segment feature data.
        ///
        /// @param deltaQ the segment delta-q value
        /// @param deltaLfYVertical the segment luma vertical loop-filter delta
        /// @param deltaLfYHorizontal the segment luma horizontal loop-filter delta
        /// @param deltaLfU the segment chroma U loop-filter delta
        /// @param deltaLfV the segment chroma V loop-filter delta
        /// @param referenceFrame the reference frame index, or `-1` when not present
        /// @param skip whether the skip feature is enabled
        /// @param globalMotion whether the global motion feature is enabled
        public SegmentData(
                int deltaQ,
                int deltaLfYVertical,
                int deltaLfYHorizontal,
                int deltaLfU,
                int deltaLfV,
                int referenceFrame,
                boolean skip,
                boolean globalMotion
        ) {
            this.deltaQ = deltaQ;
            this.deltaLfYVertical = deltaLfYVertical;
            this.deltaLfYHorizontal = deltaLfYHorizontal;
            this.deltaLfU = deltaLfU;
            this.deltaLfV = deltaLfV;
            this.referenceFrame = referenceFrame;
            this.skip = skip;
            this.globalMotion = globalMotion;
        }

        /// Returns the segment delta-q value.
        ///
        /// @return the segment delta-q value
        public int deltaQ() {
            return deltaQ;
        }

        /// Returns the segment luma vertical loop-filter delta.
        ///
        /// @return the segment luma vertical loop-filter delta
        public int deltaLfYVertical() {
            return deltaLfYVertical;
        }

        /// Returns the segment luma horizontal loop-filter delta.
        ///
        /// @return the segment luma horizontal loop-filter delta
        public int deltaLfYHorizontal() {
            return deltaLfYHorizontal;
        }

        /// Returns the segment chroma U loop-filter delta.
        ///
        /// @return the segment chroma U loop-filter delta
        public int deltaLfU() {
            return deltaLfU;
        }

        /// Returns the segment chroma V loop-filter delta.
        ///
        /// @return the segment chroma V loop-filter delta
        public int deltaLfV() {
            return deltaLfV;
        }

        /// Returns the reference frame index, or `-1` when not present.
        ///
        /// @return the reference frame index, or `-1`
        public int referenceFrame() {
            return referenceFrame;
        }

        /// Returns whether the skip feature is enabled.
        ///
        /// @return whether the skip feature is enabled
        public boolean skip() {
            return skip;
        }

        /// Returns whether the global motion feature is enabled.
        ///
        /// @return whether the global motion feature is enabled
        public boolean globalMotion() {
            return globalMotion;
        }
    }

    /// Parsed delta-q and delta-lf signaling flags.
    
    public static final class DeltaInfo {
        /// Whether delta-q signaling is present.
        private final boolean deltaQPresent;
        /// The delta-q resolution log2.
        private final int deltaQResLog2;
        /// Whether delta-lf signaling is present.
        private final boolean deltaLfPresent;
        /// The delta-lf resolution log2.
        private final int deltaLfResLog2;
        /// Whether multi-component delta-lf signaling is enabled.
        private final boolean deltaLfMulti;

        /// Creates parsed delta-q and delta-lf signaling flags.
        ///
        /// @param deltaQPresent whether delta-q signaling is present
        /// @param deltaQResLog2 the delta-q resolution log2
        /// @param deltaLfPresent whether delta-lf signaling is present
        /// @param deltaLfResLog2 the delta-lf resolution log2
        /// @param deltaLfMulti whether multi-component delta-lf signaling is enabled
        public DeltaInfo(
                boolean deltaQPresent,
                int deltaQResLog2,
                boolean deltaLfPresent,
                int deltaLfResLog2,
                boolean deltaLfMulti
        ) {
            this.deltaQPresent = deltaQPresent;
            this.deltaQResLog2 = deltaQResLog2;
            this.deltaLfPresent = deltaLfPresent;
            this.deltaLfResLog2 = deltaLfResLog2;
            this.deltaLfMulti = deltaLfMulti;
        }

        /// Returns whether delta-q signaling is present.
        ///
        /// @return whether delta-q signaling is present
        public boolean deltaQPresent() {
            return deltaQPresent;
        }

        /// Returns the delta-q resolution log2.
        ///
        /// @return the delta-q resolution log2
        public int deltaQResLog2() {
            return deltaQResLog2;
        }

        /// Returns whether delta-lf signaling is present.
        ///
        /// @return whether delta-lf signaling is present
        public boolean deltaLfPresent() {
            return deltaLfPresent;
        }

        /// Returns the delta-lf resolution log2.
        ///
        /// @return the delta-lf resolution log2
        public int deltaLfResLog2() {
            return deltaLfResLog2;
        }

        /// Returns whether multi-component delta-lf signaling is enabled.
        ///
        /// @return whether multi-component delta-lf signaling is enabled
        public boolean deltaLfMulti() {
            return deltaLfMulti;
        }
    }

    /// Parsed loop filter parameters.
    
    public static final class LoopFilterInfo {
        /// The luma loop filter levels for vertical and horizontal edges.
        private final int [] levelY;
        /// The chroma U loop filter level.
        private final int levelU;
        /// The chroma V loop filter level.
        private final int levelV;
        /// The loop filter sharpness level.
        private final int sharpness;
        /// Whether mode/reference deltas are enabled.
        private final boolean modeRefDeltaEnabled;
        /// Whether mode/reference deltas are updated in this frame.
        private final boolean modeRefDeltaUpdate;
        /// The reference-frame loop filter deltas.
        private final int [] referenceDeltas;
        /// The mode loop filter deltas.
        private final int [] modeDeltas;

        /// Creates parsed loop filter parameters.
        ///
        /// @param levelY the luma loop filter levels
        /// @param levelU the chroma U loop filter level
        /// @param levelV the chroma V loop filter level
        /// @param sharpness the loop filter sharpness level
        /// @param modeRefDeltaEnabled whether mode/reference deltas are enabled
        /// @param modeRefDeltaUpdate whether mode/reference deltas are updated
        /// @param referenceDeltas the reference-frame loop filter deltas
        /// @param modeDeltas the mode loop filter deltas
        public LoopFilterInfo(
                int[] levelY,
                int levelU,
                int levelV,
                int sharpness,
                boolean modeRefDeltaEnabled,
                boolean modeRefDeltaUpdate,
                int[] referenceDeltas,
                int[] modeDeltas
        ) {
            this.levelY = Arrays.copyOf(levelY, levelY.length);
            this.levelU = levelU;
            this.levelV = levelV;
            this.sharpness = sharpness;
            this.modeRefDeltaEnabled = modeRefDeltaEnabled;
            this.modeRefDeltaUpdate = modeRefDeltaUpdate;
            this.referenceDeltas = Arrays.copyOf(referenceDeltas, referenceDeltas.length);
            this.modeDeltas = Arrays.copyOf(modeDeltas, modeDeltas.length);
        }

        /// Returns the luma loop filter levels.
        ///
        /// @return the luma loop filter levels
        public int[] levelY() {
            return Arrays.copyOf(levelY, levelY.length);
        }

        /// Returns one luma loop-filter level without copying the complete level array.
        ///
        /// @param pass the edge pass, `0` for vertical and `1` for horizontal edges
        /// @return the selected level, or zero when `pass` is outside the stored array
        public int levelY(int pass) {
            return pass >= 0 && pass < levelY.length ? levelY[pass] : 0;
        }

        /// Returns the chroma U loop filter level.
        ///
        /// @return the chroma U loop filter level
        public int levelU() {
            return levelU;
        }

        /// Returns the chroma V loop filter level.
        ///
        /// @return the chroma V loop filter level
        public int levelV() {
            return levelV;
        }

        /// Returns the loop filter sharpness level.
        ///
        /// @return the loop filter sharpness level
        public int sharpness() {
            return sharpness;
        }

        /// Returns whether mode/reference deltas are enabled.
        ///
        /// @return whether mode/reference deltas are enabled
        public boolean modeRefDeltaEnabled() {
            return modeRefDeltaEnabled;
        }

        /// Returns whether mode/reference deltas are updated in this frame.
        ///
        /// @return whether mode/reference deltas are updated in this frame
        public boolean modeRefDeltaUpdate() {
            return modeRefDeltaUpdate;
        }

        /// Returns the reference-frame loop filter deltas.
        ///
        /// @return the reference-frame loop filter deltas
        public int[] referenceDeltas() {
            return Arrays.copyOf(referenceDeltas, referenceDeltas.length);
        }

        /// Returns the number of reference-frame loop-filter deltas.
        ///
        /// @return the reference-frame delta count
        public int referenceDeltaCount() {
            return referenceDeltas.length;
        }

        /// Returns one reference-frame loop-filter delta.
        ///
        /// @param index the zero-based reference-frame index
        /// @return the selected reference-frame delta
        public int referenceDelta(int index) {
            return referenceDeltas[index];
        }

        /// Returns the mode loop filter deltas.
        ///
        /// @return the mode loop filter deltas
        public int[] modeDeltas() {
            return Arrays.copyOf(modeDeltas, modeDeltas.length);
        }

        /// Returns the number of mode loop-filter deltas.
        ///
        /// @return the mode delta count
        public int modeDeltaCount() {
            return modeDeltas.length;
        }

        /// Returns one mode loop-filter delta.
        ///
        /// @param index the zero-based mode index
        /// @return the selected mode delta
        public int modeDelta(int index) {
            return modeDeltas[index];
        }
    }

    /// Parsed CDEF parameters.
    
    public static final class CdefInfo {
        /// The CDEF damping value.
        private final int damping;
        /// The number of CDEF strength bits.
        private final int bits;
        /// The luma CDEF strengths.
        private final int [] yStrengths;
        /// The chroma CDEF strengths.
        private final int [] uvStrengths;

        /// Creates parsed CDEF parameters.
        ///
        /// @param damping the CDEF damping value
        /// @param bits the number of CDEF strength bits
        /// @param yStrengths the luma CDEF strengths
        /// @param uvStrengths the chroma CDEF strengths
        public CdefInfo(int damping, int bits, int[] yStrengths, int[] uvStrengths) {
            this.damping = damping;
            this.bits = bits;
            this.yStrengths = Arrays.copyOf(yStrengths, yStrengths.length);
            this.uvStrengths = Arrays.copyOf(uvStrengths, uvStrengths.length);
        }

        /// Returns the CDEF damping value.
        ///
        /// @return the CDEF damping value
        public int damping() {
            return damping;
        }

        /// Returns the number of CDEF strength bits.
        ///
        /// @return the number of CDEF strength bits
        public int bits() {
            return bits;
        }

        /// Returns the luma CDEF strengths.
        ///
        /// @return the luma CDEF strengths
        public int[] yStrengths() {
            return Arrays.copyOf(yStrengths, yStrengths.length);
        }

        /// Returns the chroma CDEF strengths.
        ///
        /// @return the chroma CDEF strengths
        public int[] uvStrengths() {
            return Arrays.copyOf(uvStrengths, uvStrengths.length);
        }
    }

    /// Parsed loop restoration parameters.
    
    public static final class RestorationInfo {
        /// The restoration types for Y, U, and V.
        private final RestorationType [] types;
        /// The restoration unit size log2 for luma.
        private final int unitSizeLog2Y;
        /// The restoration unit size log2 for chroma.
        private final int unitSizeLog2Uv;

        /// Creates parsed loop restoration parameters.
        ///
        /// @param types the restoration types for Y, U, and V
        /// @param unitSizeLog2Y the restoration unit size log2 for luma
        /// @param unitSizeLog2Uv the restoration unit size log2 for chroma
        public RestorationInfo(RestorationType[] types, int unitSizeLog2Y, int unitSizeLog2Uv) {
            this.types = Arrays.copyOf(types, types.length);
            this.unitSizeLog2Y = unitSizeLog2Y;
            this.unitSizeLog2Uv = unitSizeLog2Uv;
        }

        /// Returns the restoration types for Y, U, and V.
        ///
        /// @return the restoration types for Y, U, and V
        public RestorationType[] types() {
            return Arrays.copyOf(types, types.length);
        }

        /// Returns the restoration unit size log2 for luma.
        ///
        /// @return the restoration unit size log2 for luma
        public int unitSizeLog2Y() {
            return unitSizeLog2Y;
        }

        /// Returns the restoration unit size log2 for chroma.
        ///
        /// @return the restoration unit size log2 for chroma
        public int unitSizeLog2Uv() {
            return unitSizeLog2Uv;
        }
    }

    /// One control point in a film grain scaling function.
    
    public static final class FilmGrainPoint {
        /// The sample-domain x coordinate in the range `0..255`.
        private final int value;
        /// The scaling-function y coordinate in the range `0..255`.
        private final int scaling;

        /// Creates a film grain scaling point.
        ///
        /// @param value the sample-domain x coordinate
        /// @param scaling the scaling-function y coordinate
        public FilmGrainPoint(int value, int scaling) {
            this.value = value;
            this.scaling = scaling;
        }

        /// Returns the sample-domain x coordinate in the range `0..255`.
        ///
        /// @return the sample-domain x coordinate
        public int value() {
            return value;
        }

        /// Returns the scaling-function y coordinate in the range `0..255`.
        ///
        /// @return the scaling-function y coordinate
        public int scaling() {
            return scaling;
        }
    }

    /// Normalized film grain parameters for a frame.
    
    public static final class FilmGrainParams {
        /// The AV1 maximum luma scaling-point count.
        private static final int MAX_Y_POINTS = 14;
        /// The AV1 maximum chroma scaling-point count.
        private static final int MAX_UV_POINTS = 10;
        /// The total AV1 reference-slot count.
        private static final int TOTAL_REFERENCE_FRAMES = 8;
        /// Whether film grain should be synthesized for the frame.
        private final boolean applyGrain;
        /// The pseudo-random seed used during film grain synthesis.
        private final int grainSeed;
        /// Whether this frame signaled a fresh parameter set instead of referencing an earlier frame.
        private final boolean updated;
        /// The referenced frame slot when parameters were inherited, or `-1` when self-contained.
        private final int referenceFrameIndex;
        /// The luma scaling points.
        private final FilmGrainPoint [] yPoints;
        /// Whether chroma scaling is derived from the luma scaling function.
        private final boolean chromaScalingFromLuma;
        /// The Cb scaling points.
        private final FilmGrainPoint [] cbPoints;
        /// The Cr scaling points.
        private final FilmGrainPoint [] crPoints;
        /// The normalized grain scaling shift, equal to `grain_scaling_minus_8 + 8`.
        private final int scalingShift;
        /// The auto-regressive coefficient neighborhood lag.
        private final int arCoeffLag;
        /// The normalized luma auto-regressive coefficients with the bitstream `+128` offset removed.
        private final int [] arCoefficientsY;
        /// The normalized Cb auto-regressive coefficients with the bitstream `+128` offset removed.
        private final int [] arCoefficientsCb;
        /// The normalized Cr auto-regressive coefficients with the bitstream `+128` offset removed.
        private final int [] arCoefficientsCr;
        /// The normalized auto-regressive coefficient shift, equal to `ar_coeff_shift_minus_6 + 6`.
        private final int arCoeffShift;
        /// The grain scale shift used during synthesis.
        private final int grainScaleShift;
        /// The normalized Cb component multiplier for chroma scaling with the bitstream `-128` bias applied.
        private final int cbMult;
        /// The normalized luma multiplier contributing to the Cb scaling index with the bitstream `-128` bias applied.
        private final int cbLumaMult;
        /// The normalized Cb scaling-function input offset with the bitstream `-256` bias applied.
        private final int cbOffset;
        /// The normalized Cr component multiplier for chroma scaling with the bitstream `-128` bias applied.
        private final int crMult;
        /// The normalized luma multiplier contributing to the Cr scaling index with the bitstream `-128` bias applied.
        private final int crLumaMult;
        /// The normalized Cr scaling-function input offset with the bitstream `-256` bias applied.
        private final int crOffset;
        /// Whether overlap between neighboring grain blocks is enabled.
        private final boolean overlapEnabled;
        /// Whether synthesized samples should be clipped to the restricted range.
        private final boolean clipToRestrictedRange;

        /// Creates normalized film grain parameters.
        ///
        /// @param applyGrain whether film grain should be synthesized for the frame
        /// @param grainSeed the pseudo-random seed used during synthesis
        /// @param updated whether this frame signaled a fresh parameter set
        /// @param referenceFrameIndex the referenced frame slot when parameters were inherited, or `-1`
        /// @param yPoints the luma scaling points
        /// @param chromaScalingFromLuma whether chroma scaling is derived from luma
        /// @param cbPoints the Cb scaling points
        /// @param crPoints the Cr scaling points
        /// @param scalingShift the normalized grain scaling shift
        /// @param arCoeffLag the auto-regressive coefficient neighborhood lag
        /// @param arCoefficientsY the normalized luma auto-regressive coefficients
        /// @param arCoefficientsCb the normalized Cb auto-regressive coefficients
        /// @param arCoefficientsCr the normalized Cr auto-regressive coefficients
        /// @param arCoeffShift the normalized auto-regressive coefficient shift
        /// @param grainScaleShift the grain scale shift used during synthesis
        /// @param cbMult the normalized Cb component multiplier for chroma scaling
        /// @param cbLumaMult the normalized luma multiplier contributing to the Cb scaling index
        /// @param cbOffset the normalized Cb scaling-function input offset
        /// @param crMult the normalized Cr component multiplier for chroma scaling
        /// @param crLumaMult the normalized luma multiplier contributing to the Cr scaling index
        /// @param crOffset the normalized Cr scaling-function input offset
        /// @param overlapEnabled whether overlap between neighboring grain blocks is enabled
        /// @param clipToRestrictedRange whether synthesized samples should be clipped to the restricted range
        public FilmGrainParams(
                boolean applyGrain,
                int grainSeed,
                boolean updated,
                int referenceFrameIndex,
                FilmGrainPoint[] yPoints,
                boolean chromaScalingFromLuma,
                FilmGrainPoint[] cbPoints,
                FilmGrainPoint[] crPoints,
                int scalingShift,
                int arCoeffLag,
                int[] arCoefficientsY,
                int[] arCoefficientsCb,
                int[] arCoefficientsCr,
                int arCoeffShift,
                int grainScaleShift,
                int cbMult,
                int cbLumaMult,
                int cbOffset,
                int crMult,
                int crLumaMult,
                int crOffset,
                boolean overlapEnabled,
                boolean clipToRestrictedRange
        ) {
            validateReferenceState(applyGrain, updated, referenceFrameIndex);
            this.applyGrain = applyGrain;
            requireRange(grainSeed, 0, 0xFFFF, "grainSeed");
            this.grainSeed = grainSeed;
            this.updated = updated;
            this.referenceFrameIndex = referenceFrameIndex;
            this.yPoints = Arrays.copyOf(Objects.requireNonNull(yPoints, "yPoints"), yPoints.length);
            validateScalingPoints(this.yPoints, MAX_Y_POINTS, "yPoints");
            this.chromaScalingFromLuma = chromaScalingFromLuma;
            this.cbPoints = Arrays.copyOf(Objects.requireNonNull(cbPoints, "cbPoints"), cbPoints.length);
            validateScalingPoints(this.cbPoints, MAX_UV_POINTS, "cbPoints");
            this.crPoints = Arrays.copyOf(Objects.requireNonNull(crPoints, "crPoints"), crPoints.length);
            validateScalingPoints(this.crPoints, MAX_UV_POINTS, "crPoints");
            if (chromaScalingFromLuma && (this.cbPoints.length != 0 || this.crPoints.length != 0)) {
                throw new IllegalArgumentException(
                        "Film grain chroma scaling derived from luma must not carry explicit chroma scaling points"
                );
            }
            requireRange(scalingShift, 8, 11, "scalingShift");
            this.scalingShift = scalingShift;
            requireRange(arCoeffLag, 0, 3, "arCoeffLag");
            this.arCoeffLag = arCoeffLag;
            this.arCoefficientsY = Arrays.copyOf(Objects.requireNonNull(arCoefficientsY, "arCoefficientsY"), arCoefficientsY.length);
            int autoregressiveCoefficientCount = 2 * arCoeffLag * (arCoeffLag + 1);
            int expectedLumaCoefficientCount = this.yPoints.length > 0 ? autoregressiveCoefficientCount : 0;
            validateCoefficientArray(this.arCoefficientsY, expectedLumaCoefficientCount, "arCoefficientsY");
            this.arCoefficientsCb = Arrays.copyOf(Objects.requireNonNull(arCoefficientsCb, "arCoefficientsCb"), arCoefficientsCb.length);
            int expectedChromaCoefficientCount = autoregressiveCoefficientCount + (this.yPoints.length > 0 ? 1 : 0);
            validateCoefficientArray(
                    this.arCoefficientsCb,
                    chromaScalingFromLuma || this.cbPoints.length > 0 ? expectedChromaCoefficientCount : 0,
                    "arCoefficientsCb"
            );
            this.arCoefficientsCr = Arrays.copyOf(Objects.requireNonNull(arCoefficientsCr, "arCoefficientsCr"), arCoefficientsCr.length);
            validateCoefficientArray(
                    this.arCoefficientsCr,
                    chromaScalingFromLuma || this.crPoints.length > 0 ? expectedChromaCoefficientCount : 0,
                    "arCoefficientsCr"
            );
            requireRange(arCoeffShift, 6, 9, "arCoeffShift");
            this.arCoeffShift = arCoeffShift;
            requireRange(grainScaleShift, 0, 3, "grainScaleShift");
            this.grainScaleShift = grainScaleShift;
            requireRange(cbMult, -128, 127, "cbMult");
            this.cbMult = cbMult;
            requireRange(cbLumaMult, -128, 127, "cbLumaMult");
            this.cbLumaMult = cbLumaMult;
            requireRange(cbOffset, -256, 255, "cbOffset");
            this.cbOffset = cbOffset;
            requireRange(crMult, -128, 127, "crMult");
            this.crMult = crMult;
            requireRange(crLumaMult, -128, 127, "crLumaMult");
            this.crLumaMult = crLumaMult;
            requireRange(crOffset, -256, 255, "crOffset");
            this.crOffset = crOffset;
            this.overlapEnabled = overlapEnabled;
            this.clipToRestrictedRange = clipToRestrictedRange;
        }

        /// Returns disabled film grain parameters.
        ///
        /// @return disabled film grain parameters
        public static FilmGrainParams disabled() {
            return new FilmGrainParams(
                    false,
                    0,
                    false,
                    -1,
                    new FilmGrainPoint[0],
                    false,
                    new FilmGrainPoint[0],
                    new FilmGrainPoint[0],
                    8,
                    0,
                    new int[0],
                    new int[0],
                    new int[0],
                    6,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    false,
                    false
            );
        }

        /// Returns whether film grain should be synthesized for the frame.
        ///
        /// @return whether film grain should be synthesized for the frame
        public boolean applyGrain() {
            return applyGrain;
        }

        /// Returns the pseudo-random seed used during synthesis.
        ///
        /// @return the pseudo-random seed used during synthesis
        public int grainSeed() {
            return grainSeed;
        }

        /// Returns whether this frame signaled a fresh parameter set.
        ///
        /// @return whether this frame signaled a fresh parameter set
        public boolean updated() {
            return updated;
        }

        /// Returns the referenced frame slot when parameters were inherited, or `-1`.
        ///
        /// @return the referenced frame slot when parameters were inherited, or `-1`
        public int referenceFrameIndex() {
            return referenceFrameIndex;
        }

        /// Returns the luma scaling points.
        ///
        /// @return the luma scaling points
        public FilmGrainPoint[] yPoints() {
            return Arrays.copyOf(yPoints, yPoints.length);
        }

        /// Returns whether chroma scaling is derived from the luma scaling function.
        ///
        /// @return whether chroma scaling is derived from the luma scaling function
        public boolean chromaScalingFromLuma() {
            return chromaScalingFromLuma;
        }

        /// Returns the Cb scaling points.
        ///
        /// @return the Cb scaling points
        public FilmGrainPoint[] cbPoints() {
            return Arrays.copyOf(cbPoints, cbPoints.length);
        }

        /// Returns the Cr scaling points.
        ///
        /// @return the Cr scaling points
        public FilmGrainPoint[] crPoints() {
            return Arrays.copyOf(crPoints, crPoints.length);
        }

        /// Returns the normalized grain scaling shift.
        ///
        /// @return the normalized grain scaling shift
        public int scalingShift() {
            return scalingShift;
        }

        /// Returns the auto-regressive coefficient neighborhood lag.
        ///
        /// @return the auto-regressive coefficient neighborhood lag
        public int arCoeffLag() {
            return arCoeffLag;
        }

        /// Returns the normalized luma auto-regressive coefficients.
        ///
        /// @return the normalized luma auto-regressive coefficients
        public int[] arCoefficientsY() {
            return Arrays.copyOf(arCoefficientsY, arCoefficientsY.length);
        }

        /// Returns the normalized Cb auto-regressive coefficients.
        ///
        /// @return the normalized Cb auto-regressive coefficients
        public int[] arCoefficientsCb() {
            return Arrays.copyOf(arCoefficientsCb, arCoefficientsCb.length);
        }

        /// Returns the normalized Cr auto-regressive coefficients.
        ///
        /// @return the normalized Cr auto-regressive coefficients
        public int[] arCoefficientsCr() {
            return Arrays.copyOf(arCoefficientsCr, arCoefficientsCr.length);
        }

        /// Returns the normalized auto-regressive coefficient shift.
        ///
        /// @return the normalized auto-regressive coefficient shift
        public int arCoeffShift() {
            return arCoeffShift;
        }

        /// Returns the grain scale shift used during synthesis.
        ///
        /// @return the grain scale shift used during synthesis
        public int grainScaleShift() {
            return grainScaleShift;
        }

        /// Returns the normalized Cb component multiplier for chroma scaling.
        ///
        /// @return the normalized Cb component multiplier for chroma scaling
        public int cbMult() {
            return cbMult;
        }

        /// Returns the normalized luma multiplier contributing to the Cb scaling index.
        ///
        /// @return the normalized luma multiplier contributing to the Cb scaling index
        public int cbLumaMult() {
            return cbLumaMult;
        }

        /// Returns the normalized Cb scaling-function input offset.
        ///
        /// @return the normalized Cb scaling-function input offset
        public int cbOffset() {
            return cbOffset;
        }

        /// Returns the normalized Cr component multiplier for chroma scaling.
        ///
        /// @return the normalized Cr component multiplier for chroma scaling
        public int crMult() {
            return crMult;
        }

        /// Returns the normalized luma multiplier contributing to the Cr scaling index.
        ///
        /// @return the normalized luma multiplier contributing to the Cr scaling index
        public int crLumaMult() {
            return crLumaMult;
        }

        /// Returns the normalized Cr scaling-function input offset.
        ///
        /// @return the normalized Cr scaling-function input offset
        public int crOffset() {
            return crOffset;
        }

        /// Returns whether overlap between neighboring grain blocks is enabled.
        ///
        /// @return whether overlap between neighboring grain blocks is enabled
        public boolean overlapEnabled() {
            return overlapEnabled;
        }

        /// Returns whether synthesized samples should be clipped to the restricted range.
        ///
        /// @return whether synthesized samples should be clipped to the restricted range
        public boolean clipToRestrictedRange() {
            return clipToRestrictedRange;
        }

        /// Validates the normalized grain-reference relationship.
        ///
        /// @param applyGrain whether film grain is enabled
        /// @param updated whether the frame signaled fresh parameters
        /// @param referenceFrameIndex the referenced frame slot, or `-1`
        private static void validateReferenceState(boolean applyGrain, boolean updated, int referenceFrameIndex) {
            if (!applyGrain) {
                if (updated) {
                    throw new IllegalArgumentException("Disabled film grain must not be marked as updated");
                }
                if (referenceFrameIndex != -1) {
                    throw new IllegalArgumentException("Disabled film grain must not reference another frame");
                }
                return;
            }

            if (updated) {
                if (referenceFrameIndex != -1) {
                    throw new IllegalArgumentException("Updated film grain must not reference another frame");
                }
            } else if (referenceFrameIndex < 0 || referenceFrameIndex >= TOTAL_REFERENCE_FRAMES) {
                throw new IllegalArgumentException(
                        "Referenced film grain must use one AV1 reference slot in the range 0..7"
                );
            }
        }

        /// Validates one normalized scaling-point array.
        ///
        /// @param points the scaling-point array to validate
        /// @param maximumPointCount the AV1 maximum point count for the component
        /// @param fieldName the field name used in error messages
        private static void validateScalingPoints(
                FilmGrainPoint[] points,
                int maximumPointCount,
                String fieldName
        ) {
            if (points.length > maximumPointCount) {
                throw new IllegalArgumentException(
                        fieldName + " exceeds the AV1 maximum point count of " + maximumPointCount
                );
            }

            int previousValue = -1;
            for (FilmGrainPoint point : points) {
                int value = point.value();
                requireRange(value, 0, 255, fieldName + ".value");
                requireRange(point.scaling(), 0, 255, fieldName + ".scaling");
                if (value <= previousValue) {
                    throw new IllegalArgumentException(fieldName + " values must increase strictly");
                }
                previousValue = value;
            }
        }

        /// Validates one normalized auto-regressive coefficient array.
        ///
        /// @param coefficients the coefficient array to validate
        /// @param expectedLength the exact AV1 coefficient count expected for the current state
        /// @param fieldName the field name used in error messages
        private static void validateCoefficientArray(int[] coefficients, int expectedLength, String fieldName) {
            if (coefficients.length != expectedLength) {
                throw new IllegalArgumentException(
                        fieldName + " must contain exactly " + expectedLength + " coefficients"
                );
            }
            for (int coefficient : coefficients) {
                requireRange(coefficient, -128, 127, fieldName + " element");
            }
        }

        /// Requires one integer value to stay within one inclusive range.
        ///
        /// @param value the integer value to validate
        /// @param minimum the inclusive minimum
        /// @param maximum the inclusive maximum
        /// @param fieldName the field name used in error messages
        private static void requireRange(int value, int minimum, int maximum, String fieldName) {
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException(
                        fieldName + " must stay in the inclusive range " + minimum + ".." + maximum
                );
            }
        }
    }
}
