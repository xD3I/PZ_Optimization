// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;

import pzopt.vendor.avif.AvifImageInfo;

import java.util.Objects;

/// Parsed AVIF container data required to decode still images and image sequences.
///
/// Still images expose normalized standalone or grid-derived [AvifImageSource] instances. Image
/// sequences instead expose one AV1 payload per sample. Display metadata, timing, and item
/// transforms are retained by [#info()].

public final class AvifContainer {
    /// The parsed image metadata.
    private final AvifImageInfo info;
    /// The Sample Transform selected as a preferred alternative, or `null`.
    private final SampleTransform sampleTransform;
    /// The primary standalone or grid-derived image source, or `null` for a sequence.
    private final AvifImageSource primarySource;
    /// The alpha auxiliary image source, or `null` when absent or sequence-based.
    private final AvifImageSource alphaSource;
    /// The depth auxiliary image source, or `null` when absent or sequence-based.
    private final AvifImageSource depthSource;
    /// The gain-map image source, or `null` when absent or descriptor-only.
    private final AvifImageSource gainMapSource;
    /// The AV1 OBU payloads for sequence color samples, or `null` for a still image.
    private final AvifPayload [] samplePayloads;
    /// The AV1 OBU payloads for sequence alpha samples, or `null` when absent.
    private final AvifPayload [] sequenceAlphaSamplePayloads;
    /// The AV1 OBU payloads for sequence depth samples, or `null` when absent.
    private final AvifPayload [] sequenceDepthSamplePayloads;

    /// Creates parsed still-image container data from normalized image sources.
    ///
    /// @param info the parsed still-image metadata
    /// @param primarySource the primary standalone or grid-derived image source
    /// @param alphaSource the alpha auxiliary image source, or `null`
    /// @param depthSource the depth auxiliary image source, or `null`
    /// @param gainMapSource the gain-map image source, or `null`
    /// @param sampleTransform the preferred Sample Transform, or `null`
    public AvifContainer(
            AvifImageInfo info,
            AvifImageSource primarySource,
            AvifImageSource alphaSource,
            AvifImageSource depthSource,
            AvifImageSource gainMapSource,
            SampleTransform sampleTransform
    ) {
        this.info = Objects.requireNonNull(info, "info");
        if (info.animated()) {
            throw new IllegalArgumentException("Still-image container metadata must not be animated");
        }
        this.sampleTransform = sampleTransform;
        this.primarySource = Objects.requireNonNull(primarySource, "primarySource");
        this.alphaSource = alphaSource;
        this.depthSource = depthSource;
        this.gainMapSource = gainMapSource;
        this.samplePayloads = null;
        this.sequenceAlphaSamplePayloads = null;
        this.sequenceDepthSamplePayloads = null;
    }

    /// Creates parsed image-sequence container data.
    ///
    /// @param info the parsed image-sequence metadata
    /// @param samplePayloads the AV1 OBU payloads for color samples in presentation order
    /// @param sequenceAlphaSamplePayloads the AV1 OBU payloads for alpha samples, or `null`
    /// @param sequenceDepthSamplePayloads the AV1 OBU payloads for depth samples, or `null`
    public AvifContainer(
            AvifImageInfo info,
            AvifPayload [] samplePayloads,
            AvifPayload [] sequenceAlphaSamplePayloads,
            AvifPayload [] sequenceDepthSamplePayloads
    ) {
        this.info = Objects.requireNonNull(info, "info");
        if (!info.animated()) {
            throw new IllegalArgumentException("Image-sequence container metadata must be animated");
        }
        Objects.requireNonNull(samplePayloads, "samplePayloads");
        if (samplePayloads.length != info.frameCount()) {
            throw new IllegalArgumentException("samplePayloads length must match the advertised frame count");
        }
        if (sequenceAlphaSamplePayloads != null && sequenceAlphaSamplePayloads.length != samplePayloads.length) {
            throw new IllegalArgumentException("sequenceAlphaSamplePayloads length must match samplePayloads length");
        }
        if (sequenceDepthSamplePayloads != null && sequenceDepthSamplePayloads.length != samplePayloads.length) {
            throw new IllegalArgumentException("sequenceDepthSamplePayloads length must match samplePayloads length");
        }
        this.sampleTransform = null;
        this.primarySource = null;
        this.alphaSource = null;
        this.depthSource = null;
        this.gainMapSource = null;
        this.samplePayloads = copyPayloadDescriptors(samplePayloads);
        this.sequenceAlphaSamplePayloads = sequenceAlphaSamplePayloads != null
                ? copyPayloadDescriptors(sequenceAlphaSamplePayloads)
                : null;
        this.sequenceDepthSamplePayloads = sequenceDepthSamplePayloads != null
                ? copyPayloadDescriptors(sequenceDepthSamplePayloads)
                : null;
    }

    /// Returns the parsed image metadata.
    ///
    /// @return the parsed image metadata
    public AvifImageInfo info() {
        return info;
    }

    /// Returns the preferred Sample Transform selected for this still image.
    ///
    /// @return the Sample Transform, or `null` when no preferred `sato` alternative is present
    public SampleTransform sampleTransform() {
        return sampleTransform;
    }

    /// Returns the primary standalone or grid-derived image source.
    ///
    /// @return the primary image source, or `null` for a sequence
    public AvifImageSource primarySource() {
        return primarySource;
    }

    /// Returns the alpha auxiliary image source.
    ///
    /// @return the alpha image source, or `null`
    public AvifImageSource alphaSource() {
        return alphaSource;
    }

    /// Returns the depth auxiliary image source.
    ///
    /// @return the depth image source, or `null`
    public AvifImageSource depthSource() {
        return depthSource;
    }

    /// Returns the gain-map image source.
    ///
    /// @return the gain-map image source, or `null`
    public AvifImageSource gainMapSource() {
        return gainMapSource;
    }

    /// Returns whether this container represents an AVIS image sequence.
    ///
    /// @return whether sequence sample payloads are present
    public boolean isSequence() {
        return samplePayloads != null;
    }

    /// Returns the AV1 OBU payloads for color samples in presentation order.
    ///
    /// @return immutable payload descriptors, or `null` for a still image
    public AvifPayload [] samplePayloads() {
        return samplePayloads != null ? samplePayloads.clone() : null;
    }

    /// Returns the AV1 OBU payloads for alpha samples in presentation order.
    ///
    /// @return immutable payload descriptors, or `null` when no sequence alpha track is present
    public AvifPayload [] sequenceAlphaSamplePayloads() {
        return sequenceAlphaSamplePayloads != null ? sequenceAlphaSamplePayloads.clone() : null;
    }

    /// Returns the AV1 OBU payloads for depth samples in presentation order.
    ///
    /// @return immutable payload descriptors, or `null` when no sequence depth track is present
    public AvifPayload [] sequenceDepthSamplePayloads() {
        return sequenceDepthSamplePayloads != null ? sequenceDepthSamplePayloads.clone() : null;
    }

    /// Copies and validates a payload descriptor array.
    ///
    /// @param payloads the source payload descriptors
    /// @return an independent descriptor array
    private static AvifPayload [] copyPayloadDescriptors(
            AvifPayload [] payloads
    ) {
        AvifPayload[] result = payloads.clone();
        for (int i = 0; i < payloads.length; i++) {
            Objects.requireNonNull(result[i], "payloads[" + i + "]");
        }
        return result;
    }
}
