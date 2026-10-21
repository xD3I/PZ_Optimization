// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.decode;

import pzopt.vendor.avif.av1.Av1FrameType;
import pzopt.vendor.avif.internal.av1.entropy.CdfContext;
import pzopt.vendor.avif.internal.av1.entropy.MsacDecoder;
import pzopt.vendor.avif.internal.av1.model.BlockSize;
import pzopt.vendor.avif.internal.av1.model.CompoundInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.FilterIntraMode;
import pzopt.vendor.avif.internal.av1.model.FrameHeader;
import pzopt.vendor.avif.internal.av1.model.InterIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.LumaIntraPredictionMode;
import pzopt.vendor.avif.internal.av1.model.MotionVector;
import pzopt.vendor.avif.internal.av1.model.MotionMode;
import pzopt.vendor.avif.internal.av1.model.PartitionType;
import pzopt.vendor.avif.internal.av1.model.SingleInterPredictionMode;
import pzopt.vendor.avif.internal.av1.model.TransformSize;
import pzopt.vendor.avif.internal.av1.model.TransformType;
import pzopt.vendor.avif.internal.av1.model.UvIntraPredictionMode;

import java.util.Objects;

/// Typed reader for the first block-level AV1 syntax elements inside one tile bitstream.
///
/// This reader is intentionally small and currently covers only syntax elements already backed by
/// `CdfContext`: partitioning, skip, skip mode, intra/inter, compound and single-reference
/// selection, inter prediction-mode symbols, inter-intra syntax, `intrabc`, Y/UV intra prediction modes, palette
/// presence and size signaling, CDEF and delta-q/delta-lf side syntax, motion-vector residuals,
/// loop-restoration unit syntax, filter intra, angle deltas, and CFL alpha.

public final class TileSyntaxReader {
    /// The `mv_joint` symbol that leaves both motion-vector components unchanged.
    private static final int MOTION_VECTOR_JOINT_NONE = 0;

    /// The `mv_joint` bit that signals a horizontal motion-vector residual.
    private static final int MOTION_VECTOR_JOINT_HORIZONTAL = 1;

    /// The `mv_joint` bit that signals a vertical motion-vector residual.
    private static final int MOTION_VECTOR_JOINT_VERTICAL = 2;

    /// The Wiener coefficient lower bounds in AV1 coefficient order.
    private static final int [] WIENER_TAPS_MIN = {-5, -23, -17};

    /// The Wiener coefficient upper bounds in AV1 coefficient order.
    private static final int [] WIENER_TAPS_MAX = {10, 8, 46};

    /// The Wiener coefficient subexponential `k` parameters in AV1 coefficient order.
    private static final int [] WIENER_TAPS_K = {1, 2, 3};

    /// The self-guided projection coefficient lower bounds.
    private static final int [] SELF_GUIDED_PROJECTION_MIN = {-96, -32};

    /// The self-guided projection coefficient upper bounds.
    private static final int [] SELF_GUIDED_PROJECTION_MAX = {31, 95};

    /// The self-guided projection coefficient subexponential `k` parameter.
    private static final int SELF_GUIDED_PROJECTION_SUBEXP_K = 4;

    /// The self-guided projection coefficient precision.
    private static final int SELF_GUIDED_PROJECTION_BITS = 7;

    /// The `dav1d` transform-type lookup table shared by intra and inter transform sets.
    private static final TransformType [] TRANSFORM_TYPES_PER_SET = {
            TransformType.IDTX,
            TransformType.DCT_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.IDTX,
            TransformType.DCT_DCT,
            TransformType.V_DCT,
            TransformType.H_DCT,
            TransformType.ADST_ADST,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.IDTX,
            TransformType.V_DCT,
            TransformType.H_DCT,
            TransformType.DCT_DCT,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.FLIPADST_DCT,
            TransformType.DCT_FLIPADST,
            TransformType.ADST_ADST,
            TransformType.FLIPADST_FLIPADST,
            TransformType.ADST_FLIPADST,
            TransformType.FLIPADST_ADST,
            TransformType.IDTX,
            TransformType.V_DCT,
            TransformType.H_DCT,
            TransformType.V_ADST,
            TransformType.H_ADST,
            TransformType.V_FLIPADST,
            TransformType.H_FLIPADST,
            TransformType.DCT_DCT,
            TransformType.ADST_DCT,
            TransformType.DCT_ADST,
            TransformType.FLIPADST_DCT,
            TransformType.DCT_FLIPADST,
            TransformType.ADST_ADST,
            TransformType.FLIPADST_FLIPADST,
            TransformType.ADST_FLIPADST,
            TransformType.FLIPADST_ADST
    };

    /// The tile-local decode state that owns the mutable decoder and CDF context.
    private final TileDecodeContext tileContext;

    /// The tile-local arithmetic decoder.
    private final MsacDecoder msacDecoder;

    /// The tile-local mutable CDF context.
    private final CdfContext cdfContext;

    /// Whether malformed syntax outside conformance limits must be rejected.
    private final boolean strictStdCompliance;

    /// Creates a typed syntax reader over one tile-local decode context.
    ///
    /// @param tileContext the tile-local decode context that owns the mutable decoder and CDF context
    public TileSyntaxReader(TileDecodeContext tileContext) {
        this(tileContext, false);
    }

    /// Creates a typed syntax reader with a strict conformance policy.
    ///
    /// @param tileContext the tile-local decode context that owns the mutable decoder and CDF context
    /// @param strictStdCompliance whether malformed syntax outside conformance limits must be rejected
    public TileSyntaxReader(TileDecodeContext tileContext, boolean strictStdCompliance) {
        this.tileContext = Objects.requireNonNull(tileContext, "tileContext");
        this.msacDecoder = tileContext.msacDecoder();
        this.cdfContext = tileContext.cdfContext();
        this.strictStdCompliance = strictStdCompliance;
    }

    /// Returns the tile-local decode context that owns this syntax reader.
    ///
    /// @return the tile-local decode context that owns this syntax reader
    public TileDecodeContext tileContext() {
        return tileContext;
    }

    /// Decodes one skip flag using the supplied skip-context index.
    ///
    /// @param context the zero-based skip-context index in `[0, 3)`
    /// @return the decoded skip flag
    public boolean readSkipFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipCdf(context));
    }

    /// Decodes one skip-mode flag using the supplied skip-mode context index.
    ///
    /// @param context the zero-based skip-mode context index in `[0, 3)`
    /// @return the decoded skip-mode flag
    public boolean readSkipModeFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSkipModeCdf(context));
    }

    /// Decodes one intra/inter decision for inter and switch frames.
    ///
    /// For key and intra-only frames the bitstream does not signal this decision, so this method
    /// returns `true` without consuming entropy-coded bits.
    ///
    /// @param context the zero-based intra-context index in `[0, 4)`
    /// @return `true` when the block is intra-coded, otherwise `false`
    public boolean readIntraBlockFlag(int context) {
        Av1FrameType frameType = tileContext.frameHeader().frameType();
        if (frameType == Av1FrameType.KEY || frameType == Av1FrameType.INTRA) {
            return true;
        }
        return !msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntraCdf(context));
    }

    /// Decodes one compound-reference decision for inter and switch frames.
    ///
    /// @param context the zero-based compound-reference context index in `[0, 5)`
    /// @return whether the block uses compound references
    public boolean readCompoundReferenceFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundReferenceCdf(context));
    }

    /// Decodes one compound-reference direction decision.
    ///
    /// @param context the zero-based compound-direction context index in `[0, 5)`
    /// @return whether the compound block uses bi-directional references
    public boolean readCompoundDirectionFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundDirectionCdf(context));
    }

    /// Decodes one single-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based single-reference table index in `[0, 6)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded single-reference selection flag
    public boolean readSingleReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound forward-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound forward-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound forward-reference selection flag
    public boolean readCompoundForwardReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundForwardReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound backward-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound backward-reference table index in `[0, 2)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound backward-reference selection flag
    public boolean readCompoundBackwardReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundBackwardReferenceCdf(tableIndex, context));
    }

    /// Decodes one compound unidirectional-reference selection flag from the supplied table and context.
    ///
    /// @param tableIndex the zero-based compound unidirectional-reference table index in `[0, 3)`
    /// @param context the zero-based context index in `[0, 3)`
    /// @return the decoded compound unidirectional-reference selection flag
    public boolean readCompoundUnidirectionalReferenceFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableCompoundUnidirectionalReferenceCdf(tableIndex, context));
    }

    /// Decodes one single-reference inter-mode `newmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `newmv` context index in `[0, 6)`
    /// @return whether the block follows the global/ref-mv branch instead of the new-mv branch
    public boolean readSingleInterNewMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterNewMvCdf(context));
    }

    /// Decodes one single-reference inter-mode `globalmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `globalmv` context index in `[0, 2)`
    /// @return whether the block uses nearest/ref-mv instead of global motion
    public boolean readSingleInterGlobalMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterGlobalMvCdf(context));
    }

    /// Decodes one single-reference inter-mode `refmv` flag from the supplied context index.
    ///
    /// @param context the zero-based `refmv` context index in `[0, 6)`
    /// @return whether the block uses near motion vectors instead of the nearest candidate
    public boolean readSingleInterReferenceMvFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSingleInterReferenceMvCdf(context));
    }

    /// Decodes one dynamic-reference-list selection bit from the supplied context index.
    ///
    /// @param context the zero-based dynamic-reference-list context index in `[0, 3)`
    /// @return the decoded dynamic-reference-list selection bit
    public boolean readDrlBit(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableDrlCdf(context));
    }

    /// Decodes one compound inter prediction mode from the supplied context index.
    ///
    /// @param context the zero-based compound inter-mode context index in `[0, 8)`
    /// @return the decoded compound inter prediction mode
    public CompoundInterPredictionMode readCompoundInterMode(int context) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCompoundInterModeCdf(context), 7);
        return CompoundInterPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one block motion mode from the supplied block-size context index.
    ///
    /// @param context the zero-based block-size context index in `[0, 22)`
    /// @return the decoded block motion mode
    public MotionMode readMotionMode(int context) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableMotionModeCdf(context), 2);
        return MotionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one OBMC enable flag from the supplied block-size context index.
    ///
    /// @param context the zero-based block-size context index in `[0, 22)`
    /// @return whether the current block uses OBMC instead of simple prediction
    public boolean readUseObmc(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableObmcCdf(context));
    }

    /// Decodes one masked-compound enable flag from the supplied context index.
    ///
    /// @param context the zero-based masked-compound context index in `[0, 6)`
    /// @return whether the current compound block uses a segment or wedge mask
    public boolean readUseMaskedCompound(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableMaskCompoundCdf(context));
    }

    /// Decodes one joint-compound averaging flag from the supplied context index.
    ///
    /// @param context the zero-based joint-compound context index in `[0, 6)`
    /// @return whether the current compound block uses simple averaging instead of weighted averaging
    public boolean readUseAverageCompound(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableJointCompoundCdf(context));
    }

    /// Decodes one wedge-vs-segment compound flag from the supplied context index.
    ///
    /// @param context the zero-based wedge context index in `[0, 9)`
    /// @return whether the current masked compound block uses a segment mask instead of a wedge mask
    public boolean readUseSegmentCompound(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableWedgeCompoundCdf(context));
    }

    /// Decodes one equiprobable compound-mask sign flag.
    ///
    /// @return the decoded compound-mask sign flag
    public boolean readCompoundMaskSign() {
        return msacDecoder.decodeBooleanEqui();
    }

    /// Decodes one inter-intra enable flag from the supplied context index.
    ///
    /// @param context the zero-based inter-intra context index in `[0, 4)`
    /// @return whether the current single-reference inter block uses inter-intra prediction
    public boolean readUseInterIntra(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableInterIntraCdf(context));
    }

    /// Decodes one inter-intra prediction mode from the supplied context index.
    ///
    /// @param context the zero-based inter-intra mode context index in `[0, 4)`
    /// @return the decoded inter-intra prediction mode
    public InterIntraPredictionMode readInterIntraMode(int context) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterIntraModeCdf(context), 3);
        return InterIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one inter-intra wedge enable flag from the supplied context index.
    ///
    /// @param context the zero-based wedge context index in `[0, 7)`
    /// @return whether the current inter-intra block uses a wedge mask
    public boolean readUseInterIntraWedge(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableInterIntraWedgeCdf(context));
    }

    /// Decodes one inter-intra wedge index from the supplied context index.
    ///
    /// @param context the zero-based wedge context index in `[0, 9)`
    /// @return the decoded wedge index in `[0, 16)`
    public int readWedgeIndex(int context) {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutableWedgeIndexCdf(context), 15);
    }

    /// Decodes one switchable interpolation-filter symbol for the supplied direction and context.
    ///
    /// Direction `0` decodes the vertical filter symbol and direction `1` decodes the horizontal
    /// filter symbol. AV1 maps the three switchable symbols to its regular, smooth, and sharp
    /// 8-tap kernels.
    ///
    /// @param direction the zero-based interpolation-filter direction index in `[0, 2)`
    /// @param context the zero-based switchable interpolation-filter context index in `[0, 8)`
    /// @return the decoded switchable interpolation filter
    public FrameHeader.InterpolationFilter readInterpolationFilter(int direction, int context) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterpolationFilterCdf(direction, context), 2);
        return switch (symbol) {
            case 0 -> FrameHeader.InterpolationFilter.EIGHT_TAP_REGULAR;
            case 1 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SMOOTH;
            case 2 -> FrameHeader.InterpolationFilter.EIGHT_TAP_SHARP;
            default -> throw new IllegalStateException("Unsupported switchable interpolation-filter symbol: " + symbol);
        };
    }

    /// Decodes one luma transform-depth symbol for the supplied maximum transform size.
    ///
    /// AV1 only signals transform depth for switchable transform mode when the maximum transform
    /// size exceeds 4x4. The returned depth is therefore zero for `TX_4X4`.
    ///
    /// @param maxTransformSize the largest luma transform size allowed for the current block
    /// @param context the zero-based transform-size context index in `[0, 3)`
    /// @return the decoded luma transform depth
    public int readTransformDepth(TransformSize maxTransformSize, int context) {
        TransformSize nonNullMaxTransformSize = Objects.requireNonNull(maxTransformSize, "maxTransformSize");
        if (nonNullMaxTransformSize.maxSquareLevel() <= 0) {
            return 0;
        }
        return msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableTransformSizeCdf(nonNullMaxTransformSize.maxSquareLevel() - 1, context),
                Math.min(nonNullMaxTransformSize.maxSquareLevel(), 2)
        );
    }

    /// Decodes one inter var-tx split flag from the supplied `dav1d` table and context.
    ///
    /// @param tableIndex the zero-based `dav1d` transform-partition table index in `[0, 7)`
    /// @param context the zero-based transform-partition context index in `[0, 3)`
    /// @return whether the current transform region splits to smaller transforms
    public boolean readTransformSplitFlag(int tableIndex, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableTransformPartitionCdf(tableIndex, context));
    }

    /// Decodes one explicitly signaled luma intra transform type.
    ///
    /// @param transformSize the active luma transform size
    /// @param yMode the luma intra prediction mode used as transform-type context
    /// @param reducedTransformSet whether the active frame uses the reduced transform-type set
    /// @return the decoded luma intra transform type
    public TransformType readIntraTransformType(
            TransformSize transformSize,
            LumaIntraPredictionMode yMode,
            boolean reducedTransformSet
    ) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        LumaIntraPredictionMode nonNullYMode = Objects.requireNonNull(yMode, "yMode");
        int minSquareLevel = nonNullTransformSize.minSquareLevel();
        int yModeIndex = nonNullYMode.symbolIndex();
        if (reducedTransformSet || minSquareLevel == TransformSize.TX_16X16.minSquareLevel()) {
            int symbol = msacDecoder.decodeSymbolAdapt(
                    cdfContext.mutableIntraTransformTypeSet2Cdf(minSquareLevel, yModeIndex),
                    4
            );
            return TRANSFORM_TYPES_PER_SET[symbol];
        }

        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableIntraTransformTypeSet1Cdf(minSquareLevel, yModeIndex),
                6
        );
        return TRANSFORM_TYPES_PER_SET[symbol + 5];
    }

    /// Decodes one explicitly signaled luma inter transform type.
    ///
    /// @param transformSize the active luma transform size
    /// @param reducedTransformSet whether the active frame uses the reduced transform-type set
    /// @return the decoded luma inter transform type
    public TransformType readInterTransformType(TransformSize transformSize, boolean reducedTransformSet) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int minSquareLevel = nonNullTransformSize.minSquareLevel();
        if (reducedTransformSet || nonNullTransformSize.maxSquareLevel() == TransformSize.TX_32X32.maxSquareLevel()) {
            boolean useDctDct = msacDecoder.decodeBooleanAdapt(cdfContext.mutableInterTransformTypeSet3Cdf(minSquareLevel));
            return useDctDct ? TransformType.DCT_DCT : TransformType.IDTX;
        }
        if (minSquareLevel == TransformSize.TX_16X16.minSquareLevel()) {
            int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterTransformTypeSet2Cdf(), 11);
            return TRANSFORM_TYPES_PER_SET[symbol + 12];
        }

        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableInterTransformTypeSet1Cdf(minSquareLevel), 15);
        return TRANSFORM_TYPES_PER_SET[symbol + 24];
    }

    /// Decodes one luma coefficient-skip flag for the supplied transform size and context.
    ///
    /// @param transformSize the luma transform size that selects the AV1 coefficient-context group
    /// @param context the zero-based coefficient-skip context index in `[0, 13)`
    /// @return whether the current transform block is signaled as all-zero
    public boolean readCoefficientSkipFlag(TransformSize transformSize, int context) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return msacDecoder.decodeBooleanAdapt(
                cdfContext.mutableCoefficientSkipCdf(nonNullTransformSize.coefficientContextIndex(), context)
        );
    }

    /// Decodes the end-of-block prefix for one transform block.
    ///
    /// The returned value follows the AV1 `eob` scan-index convention where `0` means a DC-only
    /// block, positive values indicate that at least one AC coefficient is present, and `-1` is
    /// never returned.
    ///
    /// @param transformSize the active transform size
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param oneDimensional whether the active transform type belongs to a 1D transform class
    /// @return the decoded end-of-block scan index
    public int readEndOfBlockIndex(TransformSize transformSize, boolean chroma, boolean oneDimensional) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        int tx2dSizeContext = Math.min(nonNullTransformSize.log2Width4(), 3)
                + Math.min(nonNullTransformSize.log2Height4(), 3);
        int eob = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableEndOfBlockPrefixCdf(tx2dSizeContext, chroma, oneDimensional),
                4 + tx2dSizeContext
        );
        if (eob > 1) {
            int eobBin = eob - 2;
            boolean eobHighBit = msacDecoder.decodeBooleanAdapt(
                    cdfContext.mutableEndOfBlockHighBitCdf(nonNullTransformSize.coefficientContextIndex(), chroma, eobBin)
            );
            eob = ((eobHighBit ? 1 : 0) | 2) << eobBin;
            eob |= msacDecoder.decodeBools(eobBin);
        }
        return eob;
    }

    /// Decodes one end-of-block/DC base token for the supplied transform context and table context.
    ///
    /// AV1 stores this symbol as `token_minus_1`, so the returned token is in `[1, 3]`.
    ///
    /// @param transformSize the active transform size
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based end-of-block base-token context in `[0, 4)`
    /// @return the decoded end-of-block/DC base token in `[1, 3]`
    public int readEndOfBlockBaseToken(TransformSize transformSize, boolean chroma, int context) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return 1 + msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableEndOfBlockBaseTokenCdf(nonNullTransformSize.coefficientContextIndex(), chroma, context),
                2
        );
    }

    /// Decodes one coefficient base token for the supplied transform context and table context.
    ///
    /// The returned token is in `[0, 3]`, where zero means the coefficient level is zero and three
    /// enters the high-token extension path.
    ///
    /// @param transformSize the active transform size
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based coefficient base-token context
    /// @return the decoded coefficient base token in `[0, 3]`
    public int readBaseToken(TransformSize transformSize, boolean chroma, int context) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableBaseTokenCdf(nonNullTransformSize.coefficientContextIndex(), chroma, context),
                3
        );
    }

    /// Decodes one coefficient high token from the supplied `br_tok` context.
    ///
    /// @param transformSize the active transform size
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based `br_tok` context index
    /// @return the decoded high token in `[3, 15]`
    public int readHighToken(TransformSize transformSize, boolean chroma, int context) {
        TransformSize nonNullTransformSize = Objects.requireNonNull(transformSize, "transformSize");
        return msacDecoder.decodeHighToken(
                cdfContext.mutableHighTokenCdf(Math.min(nonNullTransformSize.coefficientContextIndex(), 3), chroma, context)
        );
    }

    /// Decodes one DC-sign flag for the supplied plane and context.
    ///
    /// @param chroma whether the syntax belongs to a chroma plane
    /// @param context the zero-based DC-sign context in `[0, 3)`
    /// @return whether the decoded DC coefficient is negative
    public boolean readDcSignFlag(boolean chroma, int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableDcSignCdf(chroma, context));
    }

    /// Decodes one coefficient sign flag from the equiprobable residual-sign path.
    ///
    /// @return whether the decoded coefficient is negative
    public boolean readCoefficientSignFlag() {
        return msacDecoder.decodeBooleanEqui();
    }

    /// Decodes one AV1 coefficient Golomb extension value.
    ///
    /// @return the decoded coefficient Golomb extension value
    public int readCoefficientGolomb() {
        int length = 0;
        int value = 1;
        while (true) {
            boolean lengthBit = msacDecoder.decodeBooleanEqui();
            if (strictStdCompliance && length == 19 && !lengthBit) {
                IllegalArgumentException cause = new IllegalArgumentException(
                        "Coefficient Golomb length exceeds the AV1 limit"
                );
                throw new InvalidFrameSyntaxException(cause.getMessage(), cause);
            }
            if (lengthBit || length >= 32) {
                break;
            }
            length++;
        }
        while (length-- > 0) {
            value = (value << 1) | (msacDecoder.decodeBooleanEqui() ? 1 : 0);
        }
        return value - 1;
    }

    /// Decodes an unsigned literal made of equiprobable binary values.
    ///
    /// @param bitCount the number of equiprobable bits to decode
    /// @return the decoded unsigned literal
    public int readUnsignedBits(int bitCount) {
        return msacDecoder.decodeBools(bitCount);
    }

    /// Decodes one signed and resolution-scaled delta-q value.
    ///
    /// @param resolutionLog2 the delta-q resolution log2
    /// @return the decoded signed delta-q value after resolution scaling
    public int readDeltaQValue(int resolutionLog2) {
        return readSignedDeltaValue(cdfContext.mutableDeltaQCdf(), resolutionLog2);
    }

    /// Decodes one signed and resolution-scaled delta-lf value for the supplied context.
    ///
    /// @param context the zero-based delta-lf context index in `[0, 5)`
    /// @param resolutionLog2 the delta-lf resolution log2
    /// @return the decoded signed delta-lf value after resolution scaling
    public int readDeltaLfValue(int context, int resolutionLog2) {
        return readSignedDeltaValue(cdfContext.mutableDeltaLfCdf(context), resolutionLog2);
    }

    /// Decodes one motion-vector residual around the supplied predictor.
    ///
    /// The active frame header supplies the motion-vector precision mode. This syntax is available
    /// for inter and switch frames. The returned vector is the fully decoded motion vector in
    /// eighth-pel units.
    ///
    /// @param referenceMotionVector the predictor that the residual is added to
    /// @return the fully decoded motion vector in eighth-pel units
    public MotionVector readMotionVectorResidual(MotionVector referenceMotionVector) {
        return readMotionVectorResidual(referenceMotionVector, false);
    }

    /// Decodes one intrabc displacement-vector residual around the supplied predictor.
    ///
    /// Intrabc displacement vectors use their own adaptive CDF state and integer-pel precision.
    /// This syntax is available only when the active frame enables `allow_intrabc`.
    ///
    /// @param referenceMotionVector the predictor that the residual is added to
    /// @return the fully decoded displacement vector in eighth-pel units
    public MotionVector readIntrabcMotionVectorResidual(MotionVector referenceMotionVector) {
        return readMotionVectorResidual(referenceMotionVector, true);
    }

    /// Decodes one motion- or displacement-vector residual from the selected adaptive CDF set.
    ///
    /// @param referenceMotionVector the predictor that the residual is added to
    /// @param intrabc whether to use the independent intrabc displacement-vector CDFs
    /// @return the fully decoded vector in eighth-pel units
    private MotionVector readMotionVectorResidual(MotionVector referenceMotionVector, boolean intrabc) {
        MotionVector nonNullReferenceMotionVector = Objects.requireNonNull(referenceMotionVector, "referenceMotionVector");
        Av1FrameType frameType = tileContext.frameHeader().frameType();
        if (intrabc && !tileContext.frameHeader().allowIntrabc()) {
            throw new IllegalStateException("Intrabc displacement-vector residuals require allow_intrabc");
        }
        if (!intrabc && frameType != Av1FrameType.INTER && frameType != Av1FrameType.SWITCH) {
            throw new IllegalStateException(
                    "Motion-vector residuals are only available in inter and switch frames"
            );
        }

        int motionVectorPrecision = intrabc
                ? -1
                : (tileContext.frameHeader().allowHighPrecisionMotionVectors() ? 1 : 0)
                - (tileContext.frameHeader().forceIntegerMotionVectors() ? 1 : 0);
        int[] jointCdf = intrabc
                ? cdfContext.mutableIntrabcMotionVectorJointCdf()
                : cdfContext.mutableMotionVectorJointCdf();
        int motionVectorJoint = msacDecoder.decodeSymbolAdapt(jointCdf, 3);
        if (motionVectorJoint == MOTION_VECTOR_JOINT_NONE) {
            return nonNullReferenceMotionVector;
        }

        int rowEighthPel = nonNullReferenceMotionVector.rowEighthPel();
        int columnEighthPel = nonNullReferenceMotionVector.columnEighthPel();
        if ((motionVectorJoint & MOTION_VECTOR_JOINT_VERTICAL) != 0) {
            rowEighthPel += readMotionVectorComponentDiff(0, motionVectorPrecision, intrabc);
        }
        if ((motionVectorJoint & MOTION_VECTOR_JOINT_HORIZONTAL) != 0) {
            columnEighthPel += readMotionVectorComponentDiff(1, motionVectorPrecision, intrabc);
        }
        return new MotionVector(rowEighthPel, columnEighthPel);
    }

    /// Decodes one single-reference inter prediction mode from the supplied contexts and already-forced segment flags.
    ///
    /// This method covers only the mode symbol itself. Motion-vector candidate lookup and residual
    /// decoding remain the responsibility of higher decode layers.
    ///
    /// @param newMvContext the zero-based `newmv` context index in `[0, 6)`
    /// @param globalMvContext the zero-based `globalmv` context index in `[0, 2)`
    /// @param referenceMvContext the zero-based `refmv` context index in `[0, 6)`
    /// @param segmentGlobalMotion whether segment features force `GLOBALMV`
    /// @param segmentSkip whether segment features force the global/nearest-ref branch
    /// @return the decoded single-reference inter prediction mode
    public SingleInterPredictionMode readSingleInterMode(
            int newMvContext,
            int globalMvContext,
            int referenceMvContext,
            boolean segmentGlobalMotion,
            boolean segmentSkip
    ) {
        if (segmentGlobalMotion || segmentSkip || readSingleInterNewMvFlag(newMvContext)) {
            if (segmentGlobalMotion || segmentSkip || !readSingleInterGlobalMvFlag(globalMvContext)) {
                return SingleInterPredictionMode.GLOBALMV;
            }
            return readSingleInterReferenceMvFlag(referenceMvContext)
                    ? SingleInterPredictionMode.NEARMV
                    : SingleInterPredictionMode.NEARESTMV;
        }
        return SingleInterPredictionMode.NEWMV;
    }

    /// Decodes one `use_intrabc` flag when the active frame allows it.
    ///
    /// When the frame does not allow `intrabc`, this method returns `false` without consuming
    /// entropy-coded bits.
    ///
    /// @return the decoded `use_intrabc` flag
    public boolean readUseIntrabcFlag() {
        if (!tileContext.frameHeader().allowIntrabc()) {
            return false;
        }
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableIntrabcCdf());
    }

    /// Decodes one concrete loop-restoration unit type for the supplied frame-level type.
    ///
    /// @param frameType the frame-level restoration type for the active plane
    /// @return one concrete loop-restoration unit type
    public FrameHeader.RestorationType readRestorationUnitType(FrameHeader.RestorationType frameType) {
        FrameHeader.RestorationType checkedFrameType = Objects.requireNonNull(frameType, "frameType");
        return switch (checkedFrameType) {
            case NONE -> FrameHeader.RestorationType.NONE;
            case WIENER -> msacDecoder.decodeBooleanAdapt(cdfContext.mutableRestorationWienerCdf())
                    ? FrameHeader.RestorationType.WIENER
                    : FrameHeader.RestorationType.NONE;
            case SELF_GUIDED -> msacDecoder.decodeBooleanAdapt(cdfContext.mutableRestorationSelfGuidedCdf())
                    ? FrameHeader.RestorationType.SELF_GUIDED
                    : FrameHeader.RestorationType.NONE;
            case SWITCHABLE -> switch (msacDecoder.decodeSymbolAdapt(cdfContext.mutableRestorationSwitchableCdf(), 2)) {
                case 0 -> FrameHeader.RestorationType.NONE;
                case 1 -> FrameHeader.RestorationType.WIENER;
                case 2 -> FrameHeader.RestorationType.SELF_GUIDED;
                default -> throw new IllegalStateException("Unsupported switchable restoration symbol");
            };
        };
    }

    /// Decodes one Wiener coefficient using AV1 restoration subexponential coding.
    ///
    /// @param coefficientIndex the Wiener coefficient index in `[0, 3)`
    /// @param reference the previous reference coefficient for this plane, pass, and coefficient
    /// @return one decoded Wiener coefficient
    public int readWienerCoefficient(int coefficientIndex, int reference) {
        int index = Objects.checkIndex(coefficientIndex, WIENER_TAPS_MIN.length);
        return readSignedSubexpWithReference(
                WIENER_TAPS_MIN[index],
                WIENER_TAPS_MAX[index],
                WIENER_TAPS_K[index],
                reference
        );
    }

    /// Decodes one self-guided projection coefficient using AV1 restoration subexponential coding.
    ///
    /// @param coefficientIndex the projection coefficient index in `[0, 2)`
    /// @param reference the previous reference coefficient for this plane and coefficient
    /// @return one decoded self-guided projection coefficient
    public int readSelfGuidedProjectionCoefficient(int coefficientIndex, int reference) {
        int index = Objects.checkIndex(coefficientIndex, SELF_GUIDED_PROJECTION_MIN.length);
        return readSignedSubexpWithReference(
                SELF_GUIDED_PROJECTION_MIN[index],
                SELF_GUIDED_PROJECTION_MAX[index],
                SELF_GUIDED_PROJECTION_SUBEXP_K,
                reference
        );
    }

    /// Returns the derived second self-guided projection coefficient when its filter radius is disabled.
    ///
    /// @param firstReference the current first projection coefficient reference
    /// @return the derived second projection coefficient
    public int derivedSelfGuidedProjectionCoefficient1(int firstReference) {
        return clip(
                (1 << SELF_GUIDED_PROJECTION_BITS) - firstReference,
                SELF_GUIDED_PROJECTION_MIN[1],
                SELF_GUIDED_PROJECTION_MAX[1]
        );
    }

    /// Decodes one inter-frame Y intra prediction mode using the supplied size-context index.
    ///
    /// @param sizeContext the zero-based Y-mode size context in `[0, 4)`
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readYMode(int sizeContext) {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableYModeCdf(sizeContext), 12);
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one key-frame Y intra prediction mode using the supplied above and left luma modes.
    ///
    /// @param aboveMode the already-decoded luma mode above the current block
    /// @param leftMode the already-decoded luma mode left of the current block
    /// @return the decoded luma intra prediction mode
    public LumaIntraPredictionMode readKeyFrameYMode(
            LumaIntraPredictionMode aboveMode,
            LumaIntraPredictionMode leftMode
    ) {
        LumaIntraPredictionMode nonNullAboveMode = Objects.requireNonNull(aboveMode, "aboveMode");
        LumaIntraPredictionMode nonNullLeftMode = Objects.requireNonNull(leftMode, "leftMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableKeyFrameYModeCdf(nonNullAboveMode.contextIndex(), nonNullLeftMode.contextIndex()),
                12
        );
        return LumaIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one UV intra prediction mode using the supplied Y mode and CFL availability.
    ///
    /// @param yMode the already-decoded luma intra prediction mode for the current block
    /// @param cflAllowed whether CFL is allowed for this block
    /// @return the decoded chroma intra prediction mode
    public UvIntraPredictionMode readUvMode(LumaIntraPredictionMode yMode, boolean cflAllowed) {
        LumaIntraPredictionMode nonNullYMode = Objects.requireNonNull(yMode, "yMode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableUvModeCdf(cflAllowed, nonNullYMode.symbolIndex()),
                cflAllowed ? 13 : 12
        );
        return UvIntraPredictionMode.fromSymbolIndex(symbol);
    }

    /// Decodes one `use_filter_intra` flag for the supplied block size.
    ///
    /// @param size the current block size
    /// @return whether filter intra is enabled for the current block
    public boolean readUseFilterIntra(BlockSize size) {
        BlockSize nonNullSize = Objects.requireNonNull(size, "size");
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableUseFilterIntraCdf(nonNullSize.cdfIndex()));
    }

    /// Decodes one temporal segmentation-prediction flag.
    ///
    /// @param context the zero-based segmentation-prediction context index in `[0, 3)`
    /// @return whether the block uses temporal segmentation prediction
    public boolean readSegmentPredictionFlag(int context) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableSegmentPredictionCdf(context));
    }

    /// Decodes one segment-id diff symbol for the supplied segment context.
    ///
    /// @param context the zero-based segment-id context index in `[0, 3)`
    /// @return the decoded segment-id diff symbol
    public int readSegmentId(int context) {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutableSegmentIdCdf(context), 7);
    }

    /// Decodes one luma palette-use flag for the supplied size and above/left palette contexts.
    ///
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @param paletteContext the zero-based above/left palette context in `[0, 3)`
    /// @return whether the current block uses a luma palette
    public boolean readUseLumaPalette(int sizeContext, int paletteContext) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableLumaPaletteCdf(sizeContext, paletteContext));
    }

    /// Decodes one palette size for the supplied plane and size context.
    ///
    /// AV1 codes palette sizes as `palette_size_minus_2`, so this method returns the decoded
    /// palette size in `[2, 8]`.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param sizeContext the zero-based palette size context in `[0, 7)`
    /// @return the decoded palette size in `[2, 8]`
    public int readPaletteSize(int plane, int sizeContext) {
        return msacDecoder.decodeSymbolAdapt(cdfContext.mutablePaletteSizeCdf(plane, sizeContext), 6) + 2;
    }

    /// Decodes one chroma palette-use flag for the supplied luma-palette context.
    ///
    /// @param paletteContext the zero-based chroma palette context in `[0, 2)`
    /// @return whether the current block uses a chroma palette
    public boolean readUseChromaPalette(int paletteContext) {
        return msacDecoder.decodeBooleanAdapt(cdfContext.mutableChromaPaletteCdf(paletteContext));
    }

    /// Decodes the first palette index in one color-map using the AV1 uniform coding rule.
    ///
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @return the decoded first palette index in `[0, paletteSize)`
    public int readPaletteInitialIndex(int paletteSize) {
        return msacDecoder.decodeUniform(paletteSize);
    }

    /// Decodes one palette color-map symbol for the supplied plane, palette size, and context.
    ///
    /// The returned symbol indexes the local palette-order permutation for the current pixel.
    ///
    /// @param plane the palette plane index, where `0` is luma and `1` is chroma
    /// @param paletteSize the decoded palette size in `[2, 8]`
    /// @param context the zero-based color-map context in `[0, 5)`
    /// @return the decoded palette color-map symbol in `[0, paletteSize)`
    public int readPaletteColorMapSymbol(int plane, int paletteSize, int context) {
        return msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableColorMapCdf(plane, paletteSize - 2, context),
                paletteSize - 1
        );
    }

    /// Decodes one filter-intra mode after `use_filter_intra` signaled `true`.
    ///
    /// @return the decoded filter-intra mode
    public FilterIntraMode readFilterIntraMode() {
        int symbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableFilterIntraCdf(), 4);
        return FilterIntraMode.fromSymbolIndex(symbol);
    }

    /// Decodes one partition symbol using the supplied partition block level and context index.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return the decoded partition type
    public PartitionType readPartition(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int[] cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context);
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdf,
                nonNullBlockLevel.symbolLimit()
        );
        return PartitionType.fromSymbolIndex(symbol);
    }

    /// Decodes one directional angle delta for the supplied luma intra prediction mode.
    ///
    /// @param mode the already-decoded directional luma intra prediction mode
    /// @return the decoded signed angle delta in `[-3, 3]`
    public int readYAngleDelta(LumaIntraPredictionMode mode) {
        LumaIntraPredictionMode nonNullMode = Objects.requireNonNull(mode, "mode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
                6
        );
        return symbol - 3;
    }

    /// Decodes one directional angle delta for the supplied chroma intra prediction mode.
    ///
    /// @param mode the already-decoded directional chroma intra prediction mode
    /// @return the decoded signed angle delta in `[-3, 3]`
    public int readUvAngleDelta(UvIntraPredictionMode mode) {
        UvIntraPredictionMode nonNullMode = Objects.requireNonNull(mode, "mode");
        int symbol = msacDecoder.decodeSymbolAdapt(
                cdfContext.mutableAngleDeltaCdf(nonNullMode.angleDeltaContextIndex()),
                6
        );
        return symbol - 3;
    }

    /// Decodes the signed CFL alpha pair for one block.
    ///
    /// @return the decoded signed CFL alpha pair for one block
    public CflAlpha readCflAlpha() {
        int signSymbol = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflSignCdf(), 7) + 1;
        int signU = signSymbol / 3;
        int signV = signSymbol - signU * 3;
        int alphaU = signU == 0 ? 0 : decodeSignedCflAlpha((signU == 2 ? 3 : 0) + signV, signU == 2);
        int alphaV = signV == 0 ? 0 : decodeSignedCflAlpha((signV == 2 ? 3 : 0) + signU, signV == 2);
        return new CflAlpha(alphaU, alphaV);
    }

    /// Decodes one signed CFL alpha value from the supplied sign context.
    ///
    /// @param context the zero-based CFL-alpha sign context
    /// @param positive whether the decoded alpha should be positive
    /// @return the decoded signed CFL alpha value
    private int decodeSignedCflAlpha(int context, boolean positive) {
        int alpha = msacDecoder.decodeSymbolAdapt(cdfContext.mutableCflAlphaCdf(context), 15) + 1;
        return positive ? alpha : -alpha;
    }

    /// Decodes one signed delta value that uses the AV1 delta-q/delta-lf coding rule.
    ///
    /// @param cdf the active AV1 inverse CDF table for the delta-magnitude prefix
    /// @param resolutionLog2 the resolution log2 applied to non-zero decoded magnitudes
    /// @return the decoded signed and resolution-scaled delta value
    private int readSignedDeltaValue(int[] cdf, int resolutionLog2) {
        int magnitude = msacDecoder.decodeSymbolAdapt(cdf, 3);
        if (magnitude == 3) {
            int bitCount = 1 + msacDecoder.decodeBools(3);
            magnitude = msacDecoder.decodeBools(bitCount) + 1 + (1 << bitCount);
        }
        if (magnitude == 0) {
            return 0;
        }
        if (msacDecoder.decodeBooleanEqui()) {
            magnitude = -magnitude;
        }
        return magnitude << resolutionLog2;
    }

    /// Decodes one signed subexponential value recentered around a reference.
    ///
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @param k the subexponential group width
    /// @param reference the recentering reference value
    /// @return the decoded signed subexponential value
    private int readSignedSubexpWithReference(int minimum, int maximum, int k, int reference) {
        return minimum + msacDecoder.decodeSubexp(reference - minimum, maximum + 1 - minimum, k);
    }

    /// Clips one integer into inclusive bounds.
    ///
    /// @param value the input value
    /// @param minimum the inclusive lower bound
    /// @param maximum the inclusive upper bound
    /// @return the clipped integer
    private static int clip(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /// Decodes one signed motion-vector component residual.
    ///
    /// @param component the zero-based motion-vector component index, where `0` is vertical and `1` is horizontal
    /// @param motionVectorPrecision the active motion-vector precision mode: `-1` forces integer,
    ///                              `0` disables high precision, and `1` allows high precision
    /// @param intrabc whether to use the independent intrabc displacement-vector CDFs
    /// @return the signed motion-vector component residual in eighth-pel units
    private int readMotionVectorComponentDiff(int component, int motionVectorPrecision, boolean intrabc) {
        int[] signCdf = intrabc
                ? cdfContext.mutableIntrabcMotionVectorSignCdf(component)
                : cdfContext.mutableMotionVectorSignCdf(component);
        int[] classCdf = intrabc
                ? cdfContext.mutableIntrabcMotionVectorClassCdf(component)
                : cdfContext.mutableMotionVectorClassCdf(component);
        boolean negative = msacDecoder.decodeBooleanAdapt(signCdf);
        int motionVectorClass = msacDecoder.decodeSymbolAdapt(classCdf, 10);
        int integerMagnitude;
        int fractionalPart = 3;
        int highPrecisionBit = 1;

        if (motionVectorClass == 0) {
            int[] class0Cdf = intrabc
                    ? cdfContext.mutableIntrabcMotionVectorClass0Cdf(component)
                    : cdfContext.mutableMotionVectorClass0Cdf(component);
            integerMagnitude = msacDecoder.decodeBooleanAdapt(class0Cdf) ? 1 : 0;
            if (motionVectorPrecision >= 0) {
                int[] class0FpCdf = intrabc
                        ? cdfContext.mutableIntrabcMotionVectorClass0FpCdf(component, integerMagnitude)
                        : cdfContext.mutableMotionVectorClass0FpCdf(component, integerMagnitude);
                fractionalPart = msacDecoder.decodeSymbolAdapt(
                        class0FpCdf,
                        3
                );
                if (motionVectorPrecision > 0) {
                    int[] class0HpCdf = intrabc
                            ? cdfContext.mutableIntrabcMotionVectorClass0HpCdf(component)
                            : cdfContext.mutableMotionVectorClass0HpCdf(component);
                    highPrecisionBit = msacDecoder.decodeBooleanAdapt(class0HpCdf) ? 1 : 0;
                }
            }
        } else {
            integerMagnitude = 1 << motionVectorClass;
            for (int bitIndex = 0; bitIndex < motionVectorClass; bitIndex++) {
                int[] classNCdf = intrabc
                        ? cdfContext.mutableIntrabcMotionVectorClassNCdf(component, bitIndex)
                        : cdfContext.mutableMotionVectorClassNCdf(component, bitIndex);
                boolean bit = msacDecoder.decodeBooleanAdapt(classNCdf);
                if (bit) {
                    integerMagnitude |= 1 << bitIndex;
                }
            }
            if (motionVectorPrecision >= 0) {
                int[] classNFpCdf = intrabc
                        ? cdfContext.mutableIntrabcMotionVectorClassNFpCdf(component)
                        : cdfContext.mutableMotionVectorClassNFpCdf(component);
                fractionalPart = msacDecoder.decodeSymbolAdapt(classNFpCdf, 3);
                if (motionVectorPrecision > 0) {
                    int[] classNHpCdf = intrabc
                            ? cdfContext.mutableIntrabcMotionVectorClassNHpCdf(component)
                            : cdfContext.mutableMotionVectorClassNHpCdf(component);
                    highPrecisionBit = msacDecoder.decodeBooleanAdapt(classNHpCdf) ? 1 : 0;
                }
            }
        }

        int diff = ((integerMagnitude << 3) | (fractionalPart << 1) | highPrecisionBit) + 1;
        return negative ? -diff : diff;
    }

    /// Decodes a partition decision when only a horizontal split or no split is allowed.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return `true` when the block splits into two square children, otherwise `false`
    public boolean readHorizontalSplitOnly(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int[] cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context);
        return msacDecoder.decodeBoolean(gatherVerticalAlikePartitionProbability(cdf, nonNullBlockLevel));
    }

    /// Decodes a partition decision when only a vertical split or no split is allowed.
    ///
    /// @param blockLevel the partition block level that selects the CDF shape
    /// @param context the zero-based partition context index in `[0, 4)`
    /// @return `true` when the block splits into two square children, otherwise `false`
    public boolean readVerticalSplitOnly(PartitionBlockLevel blockLevel, int context) {
        PartitionBlockLevel nonNullBlockLevel = Objects.requireNonNull(blockLevel, "blockLevel");
        int[] cdf = cdfContext.mutablePartitionCdf(nonNullBlockLevel.cdfIndex(), context);
        return msacDecoder.decodeBoolean(gatherHorizontalAlikePartitionProbability(cdf, nonNullBlockLevel));
    }

    /// Gathers the effective split probability from a partition CDF when only horizontal partitioning or splitting is legal.
    ///
    /// @param cdf the active partition CDF
    /// @param blockLevel the partition block level that constrains the symbol set
    /// @return the effective split probability in AV1 Q15 form
    private static int gatherVerticalAlikePartitionProbability(int[] cdf, PartitionBlockLevel blockLevel) {
        int symbolLimit = blockLevel.symbolLimit();
        int probability = partitionElementProbability(cdf, PartitionType.VERTICAL.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_TOP_SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_LEFT_SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_RIGHT_SPLIT.symbolIndex(), symbolLimit);
        if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
            probability += partitionElementProbability(cdf, PartitionType.VERTICAL_4.symbolIndex(), symbolLimit);
        }
        return probability;
    }

    /// Gathers the effective split probability from a partition CDF when only vertical partitioning or splitting is legal.
    ///
    /// @param cdf the active partition CDF
    /// @param blockLevel the partition block level that constrains the symbol set
    /// @return the effective split probability in AV1 Q15 form
    private static int gatherHorizontalAlikePartitionProbability(int[] cdf, PartitionBlockLevel blockLevel) {
        int symbolLimit = blockLevel.symbolLimit();
        int probability = partitionElementProbability(cdf, PartitionType.HORIZONTAL.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_TOP_SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_BOTTOM_SPLIT.symbolIndex(), symbolLimit);
        probability += partitionElementProbability(cdf, PartitionType.T_LEFT_SPLIT.symbolIndex(), symbolLimit);
        if (blockLevel != PartitionBlockLevel.BLOCK_128X128) {
            probability += partitionElementProbability(cdf, PartitionType.HORIZONTAL_4.symbolIndex(), symbolLimit);
        }
        return probability;
    }

    /// Returns the probability mass of one partition symbol from an AV1 inverse CDF table.
    ///
    /// @param cdf the active partition inverse CDF
    /// @param symbol the partition symbol index
    /// @param symbolLimit the maximum symbol index covered by the CDF
    /// @return the probability mass of the supplied symbol in AV1 Q15 form
    private static int partitionElementProbability(int[] cdf, int symbol, int symbolLimit) {
        int upper = symbol == 0 ? 32768 : cdf[symbol - 1];
        int lower = symbol == symbolLimit ? 0 : cdf[symbol];
        return upper - lower;
    }

    /// Partition block levels that select one of the five AV1 partition CDF groups.
    
    public enum PartitionBlockLevel {
        /// Partition decisions for 128x128 blocks.
        BLOCK_128X128(0, 7),
        /// Partition decisions for 64x64 blocks.
        BLOCK_64X64(1, 9),
        /// Partition decisions for 32x32 blocks.
        BLOCK_32X32(2, 9),
        /// Partition decisions for 16x16 blocks.
        BLOCK_16X16(3, 9),
        /// Partition decisions for 8x8 blocks.
        BLOCK_8X8(4, 3);

        /// The CDF-table index inside `CdfContext`.
        private final int cdfIndex;

        /// The maximum decoded symbol value for this partition level.
        private final int symbolLimit;

        /// Creates one partition block level entry.
        ///
        /// @param cdfIndex the CDF-table index inside `CdfContext`
        /// @param symbolLimit the maximum decoded symbol value for this partition level
        PartitionBlockLevel(int cdfIndex, int symbolLimit) {
            this.cdfIndex = cdfIndex;
            this.symbolLimit = symbolLimit;
        }

        /// Returns the CDF-table index inside `CdfContext`.
        ///
        /// @return the CDF-table index inside `CdfContext`
        public int cdfIndex() {
            return cdfIndex;
        }

        /// Returns the maximum decoded symbol value for this partition level.
        ///
        /// @return the maximum decoded symbol value for this partition level
        public int symbolLimit() {
            return symbolLimit;
        }
    }

    /// The signed CFL alpha pair decoded for one block.
    ///
    /// @param alphaU the signed CFL alpha for chroma U
    /// @param alphaV the signed CFL alpha for chroma V
    
    public record CflAlpha(int alphaU, int alphaV) {
    }
}
