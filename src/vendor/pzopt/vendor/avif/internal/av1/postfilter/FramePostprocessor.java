// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.postfilter;

import pzopt.vendor.avif.internal.av1.decode.FrameSyntaxDecodeResult;
import pzopt.vendor.avif.internal.av1.decode.RestorationUnitMap;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.image.DecodedSurface;

import java.util.Objects;

/// Runs the decoder postfilter stages in AV1 presentation order.
///
/// Stored reference surfaces are defined as post-filter, pre-grain snapshots. This class enforces
/// that ordering regardless of whether a particular stage currently changes samples.

public final class FramePostprocessor {
    /// Prevents instantiation of this stateless pipeline.
    private FramePostprocessor() {
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @return the post-filter, pre-grain decoded planes
    public static DecodedSurface postprocess(DecodedSurface decodedPlanes, FrameHeader frameHeader) {
        return postprocess(decodedPlanes, frameHeader, null);
    }

    /// Runs postfiltering on one reconstructed frame.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
    /// @return the post-filter, pre-grain decoded planes
    public static DecodedSurface postprocess(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        return finish(prepare(decodedPlanes, frameHeader, syntaxDecodeResult));
    }

    /// Extracts syntax-dependent state needed by the postfilter stages.
    ///
    /// The returned object retains only compact loop-filter, CDEF, and restoration state. It does
    /// not retain `syntaxDecodeResult`, so callers may release a large frame syntax tree before
    /// [#finish(PreparedFrame)] allocates any destination planes.
    ///
    /// @param decodedPlanes the reconstructed planes to post-process
    /// @param frameHeader the normalized frame header that owns the planes
    /// @param syntaxDecodeResult the decoded frame syntax that carries block-level postfilter state, or `null`
    /// @return the prepared postfilter operation
    public static PreparedFrame prepare(
            DecodedSurface decodedPlanes,
            FrameHeader frameHeader,
            FrameSyntaxDecodeResult syntaxDecodeResult
    ) {
        DecodedSurface checkedDecodedPlanes = Objects.requireNonNull(decodedPlanes, "decodedPlanes");
        FrameHeader checkedFrameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
        LoopFilterApplier.PreparedApplication preparedLoopFilter = LoopFilterApplier.prepare(
                checkedDecodedPlanes,
                checkedFrameHeader,
                syntaxDecodeResult
        );
        CdefApplier.PreparedApplication preparedCdef = CdefApplier.prepare(
                checkedDecodedPlanes,
                checkedFrameHeader.cdef(),
                syntaxDecodeResult
        );
        RestorationUnitMap restorationUnitMap = null;
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                checkedDecodedPlanes.hasChroma()
        )) {
            if (syntaxDecodeResult == null) {
                throw new IllegalStateException("Active AV1 loop restoration requires decoded restoration unit syntax");
            }
            restorationUnitMap = syntaxDecodeResult.restorationUnitMap();
        }
        return new PreparedFrame(
                checkedDecodedPlanes,
                checkedFrameHeader,
                preparedLoopFilter,
                preparedCdef,
                restorationUnitMap
        );
    }

    /// Runs loop filtering, CDEF, super-resolution, and restoration for one prepared frame.
    ///
    /// @param preparedFrame the syntax-independent prepared postfilter operation
    /// @return the post-filter, pre-grain decoded planes
    public static DecodedSurface finish(PreparedFrame preparedFrame) {
        PreparedFrame prepared = Objects.requireNonNull(preparedFrame, "preparedFrame");
        DecodedSurface afterLoopFilter = LoopFilterApplier.applyPrepared(
                prepared.reconstructedPlanes,
                prepared.preparedLoopFilter
        );
        FrameHeader checkedFrameHeader = prepared.frameHeader;
        DecodedSurface afterCdef = CdefApplier.applyPrepared(afterLoopFilter, prepared.preparedCdef);
        DecodedSurface afterSuperResolution = SuperResolutionUpscaler.apply(afterCdef, checkedFrameHeader);
        DecodedSurface restorationBoundary = afterSuperResolution;
        if (RestorationApplier.hasActiveRestoration(
                checkedFrameHeader.restoration(),
                afterLoopFilter.hasChroma()
        ) && afterLoopFilter != afterCdef) {
            restorationBoundary = SuperResolutionUpscaler.apply(afterLoopFilter, checkedFrameHeader);
        }
        return RestorationApplier.applyPrepared(
                afterSuperResolution,
                restorationBoundary,
                checkedFrameHeader.restoration(),
                prepared.restorationUnitMap
        );
    }

    /// Syntax-independent state needed to finish postfiltering one decoded frame.
    ///
    /// Instances are created by [#prepare(DecodedSurface, FrameHeader, FrameSyntaxDecodeResult)] and
    /// deliberately do not retain the complete block syntax tree.
    
    public static final class PreparedFrame {
        /// The reconstructed surface before pixel-domain postfiltering.
        private final DecodedSurface reconstructedPlanes;

        /// The normalized frame header.
        private final FrameHeader frameHeader;

        /// The compact prepared loop-filter state.
        private final LoopFilterApplier.PreparedApplication preparedLoopFilter;

        /// The compact prepared CDEF state.
        private final CdefApplier.PreparedApplication preparedCdef;

        /// The compact restoration-unit state, or `null` when restoration is inactive.
        private final RestorationUnitMap restorationUnitMap;

        /// Creates one syntax-independent prepared postfilter operation.
        ///
        /// @param reconstructedPlanes the reconstructed surface before pixel-domain postfiltering
        /// @param frameHeader the normalized frame header
        /// @param preparedLoopFilter the compact prepared loop-filter state
        /// @param preparedCdef the compact prepared CDEF state
        /// @param restorationUnitMap the compact restoration-unit state, or `null`
        private PreparedFrame(
                DecodedSurface reconstructedPlanes,
                FrameHeader frameHeader,
                LoopFilterApplier.PreparedApplication preparedLoopFilter,
                CdefApplier.PreparedApplication preparedCdef,
                RestorationUnitMap restorationUnitMap
        ) {
            this.reconstructedPlanes = Objects.requireNonNull(reconstructedPlanes, "reconstructedPlanes");
            this.frameHeader = Objects.requireNonNull(frameHeader, "frameHeader");
            this.preparedLoopFilter = Objects.requireNonNull(preparedLoopFilter, "preparedLoopFilter");
            this.preparedCdef = Objects.requireNonNull(preparedCdef, "preparedCdef");
            this.restorationUnitMap = restorationUnitMap;
        }
    }
}
