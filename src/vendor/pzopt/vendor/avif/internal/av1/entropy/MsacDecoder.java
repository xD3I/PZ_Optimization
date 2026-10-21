// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.av1.entropy;

import pzopt.vendor.avif.internal.av1.model.TileBitstream;

import java.util.Objects;

/// AV1 multi-symbol arithmetic decoder for a single tile bitstream.

public final class MsacDecoder {
    /// The AV1 entropy probability precision shift.
    private static final int PROBABILITY_SHIFT = 6;
    /// The minimum probability quantum used by AV1 entropy decoding.
    private static final int MINIMUM_PROBABILITY = 4;
    /// The decoder window size in bits.
    private static final int WINDOW_SIZE = Long.SIZE;

    /// The backing bytes that contain the entropy-coded tile payload.
    private final byte [] data;
    /// The exclusive end offset of the readable slice inside `data`.
    private final int endOffset;
    /// Whether decoded symbols are allowed to update their CDFs in place.
    private final boolean allowCdfUpdate;
    /// The next unread byte offset inside `data`.
    private int position;
    /// The decoder difference register.
    private long difference;
    /// The decoder range register.
    private int range;
    /// The number of valid bits currently buffered in `difference`.
    private int count;

    /// Creates an arithmetic decoder over a tile bitstream.
    ///
    /// @param bitstream the tile bitstream to decode
    /// @param disableCdfUpdate whether CDF updates are disabled for the active frame
    public MsacDecoder(TileBitstream bitstream, boolean disableCdfUpdate) {
        this(
                Objects.requireNonNull(bitstream, "bitstream").copyBytes(),
                0,
                bitstream.dataLength(),
                disableCdfUpdate
        );
    }

    /// Creates an arithmetic decoder over a byte slice.
    ///
    /// @param data the backing bytes that contain the tile payload
    /// @param dataOffset the first readable byte inside `data`
    /// @param dataLength the readable byte length inside `data`
    /// @param disableCdfUpdate whether CDF updates are disabled for the active frame
    public MsacDecoder(byte[] data, int dataOffset, int dataLength, boolean disableCdfUpdate) {
        this.data = Objects.requireNonNull(data, "data");
        if (dataOffset < 0 || dataOffset > data.length) {
            throw new IllegalArgumentException("dataOffset out of range: " + dataOffset);
        }
        if (dataLength < 0 || dataOffset + dataLength > data.length) {
            throw new IllegalArgumentException("dataLength out of range: " + dataLength);
        }

        this.position = dataOffset;
        this.endOffset = dataOffset + dataLength;
        this.allowCdfUpdate = !disableCdfUpdate;
        this.range = 0x8000;
        this.count = -15;
        refill();
    }

    /// Returns whether decoded symbols are allowed to update their CDFs in place.
    ///
    /// @return whether decoded symbols are allowed to update their CDFs in place
    public boolean allowCdfUpdate() {
        return allowCdfUpdate;
    }

    /// Returns the current decoder range register.
    ///
    /// @return the current decoder range register
    public int range() {
        return range;
    }

    /// Returns the current buffered-bit count used by the entropy decoder.
    ///
    /// @return the current buffered-bit count used by the entropy decoder
    public int count() {
        return count;
    }

    /// Decodes an equiprobable binary value.
    ///
    /// @return the decoded binary value
    public boolean decodeBooleanEqui() {
        int localRange = range;
        long localDifference = difference;
        int value = ((localRange >>> 8) << 7) + MINIMUM_PROBABILITY;
        long split = ((long) value) << (WINDOW_SIZE - 16);
        boolean upperPartition = Long.compareUnsigned(localDifference, split) >= 0;
        localDifference -= upperPartition ? split : 0L;
        value += upperPartition ? (localRange - (value << 1)) : 0;
        normalize(localDifference, value);
        return !upperPartition;
    }

    /// Decodes a binary value with the supplied probability that the bit is one.
    ///
    /// @param probability the Q15 probability that the decoded bit is one
    /// @return the decoded binary value
    public boolean decodeBoolean(int probability) {
        if (probability < 0 || probability > 32768) {
            throw new IllegalArgumentException("probability out of range: " + probability);
        }

        int localRange = range;
        long localDifference = difference;
        int value = ((localRange >>> 8) * (probability >>> PROBABILITY_SHIFT) >>> (7 - PROBABILITY_SHIFT))
                + MINIMUM_PROBABILITY;
        long split = ((long) value) << (WINDOW_SIZE - 16);
        boolean upperPartition = Long.compareUnsigned(localDifference, split) >= 0;
        localDifference -= upperPartition ? split : 0L;
        value += upperPartition ? (localRange - (value << 1)) : 0;
        normalize(localDifference, value);
        return !upperPartition;
    }

    /// Decodes a binary value and updates an AV1 boolean CDF in place when allowed.
    ///
    /// @param cdf the boolean CDF array in AV1 Q15 form
    /// @return the decoded binary value
    public boolean decodeBooleanAdapt(int[] cdf) {
        Objects.requireNonNull(cdf, "cdf");
        if (cdf.length < 2) {
            throw new IllegalArgumentException("Boolean CDF array must have at least two entries");
        }

        boolean bit = decodeBoolean(cdf[0]);
        if (allowCdfUpdate) {
            int countValue = cdf[1];
            int rate = 4 + (countValue >>> 4);
            if (bit) {
                cdf[0] += (32768 - cdf[0]) >>> rate;
            } else {
                cdf[0] -= cdf[0] >>> rate;
            }
            cdf[1] = countValue + (countValue < 32 ? 1 : 0);
        }
        return bit;
    }

    /// Decodes a symbol from an AV1 inverse cumulative distribution function.
    ///
    /// @param cdf the inverse CDF table in AV1 Q15 form
    /// @param symbolLimit the maximum decoded symbol value, with the update count stored at `cdf[symbolLimit]`
    /// @return the decoded symbol index
    public int decodeSymbolAdapt(int[] cdf, int symbolLimit) {
        Objects.requireNonNull(cdf, "cdf");
        if (symbolLimit <= 0 || symbolLimit > 15) {
            throw new IllegalArgumentException("symbolLimit out of range: " + symbolLimit);
        }
        if (cdf.length < symbolLimit + 1) {
            throw new IllegalArgumentException("CDF array length does not cover the requested symbol range");
        }

        int differenceHigh = (int) (difference >>> (WINDOW_SIZE - 16));
        int scaledRange = range >>> 8;
        int upperBound = range;
        int lowerBound;
        int symbol = -1;
        do {
            symbol++;
            lowerBound = upperBound;
            if (symbol == symbolLimit) {
                upperBound = 0;
            } else {
                upperBound = scaledRange * (cdf[symbol] >>> PROBABILITY_SHIFT);
                upperBound >>>= 7 - PROBABILITY_SHIFT;
                upperBound += MINIMUM_PROBABILITY * (symbolLimit - symbol);
            }
        } while (differenceHigh < upperBound);

        normalize(difference - (((long) upperBound) << (WINDOW_SIZE - 16)), lowerBound - upperBound);

        if (allowCdfUpdate) {
            int countValue = cdf[symbolLimit];
            int rate = 4 + (countValue >>> 4) + (symbolLimit > 2 ? 1 : 0);
            for (int i = 0; i < symbol; i++) {
                cdf[i] += (32768 - cdf[i]) >>> rate;
            }
            for (int i = symbol; i < symbolLimit; i++) {
                cdf[i] -= cdf[i] >>> rate;
            }
            cdf[symbolLimit] = countValue + (countValue < 32 ? 1 : 0);
        }

        return symbol;
    }

    /// Decodes the AV1 high-token extension tree used by coefficient coding.
    ///
    /// @param cdf the AV1 inverse CDF table in Q15 form
    /// @return the decoded high token value
    public int decodeHighToken(int[] cdf) {
        Objects.requireNonNull(cdf, "cdf");

        int tokenBranch = decodeSymbolAdapt(cdf, 3);
        int token = 3 + tokenBranch;
        if (tokenBranch == 3) {
            tokenBranch = decodeSymbolAdapt(cdf, 3);
            token = 6 + tokenBranch;
            if (tokenBranch == 3) {
                tokenBranch = decodeSymbolAdapt(cdf, 3);
                token = 9 + tokenBranch;
                if (tokenBranch == 3) {
                    token = 12 + decodeSymbolAdapt(cdf, 3);
                }
            }
        }
        return token;
    }

    /// Decodes an unsigned literal made of equiprobable binary values.
    ///
    /// @param bitCount the number of equiprobable bits to decode
    /// @return the decoded unsigned literal
    public int decodeBools(int bitCount) {
        if (bitCount < 0 || bitCount > Integer.SIZE) {
            throw new IllegalArgumentException("bitCount out of range: " + bitCount);
        }

        int value = 0;
        for (int i = 0; i < bitCount; i++) {
            value = (value << 1) | (decodeBooleanEqui() ? 1 : 0);
        }
        return value;
    }

    /// Decodes an AV1 uniformly distributed integer in `[0, symbolCount)`.
    ///
    /// @param symbolCount the exclusive upper bound of the decoded value
    /// @return the decoded uniformly distributed integer
    public int decodeUniform(int symbolCount) {
        if (symbolCount <= 0) {
            throw new IllegalArgumentException("symbolCount <= 0: " + symbolCount);
        }
        if (symbolCount == 1) {
            return 0;
        }

        int width = Integer.SIZE - Integer.numberOfLeadingZeros(symbolCount);
        int cutoff = (1 << width) - symbolCount;
        int value = decodeBools(width - 1);
        return value < cutoff ? value : (value << 1) - cutoff + (decodeBooleanEqui() ? 1 : 0);
    }

    /// Decodes an AV1 subexponential value recentered around the supplied reference.
    ///
    /// @param reference the recentering reference value
    /// @param symbolCount the exclusive upper bound of the decoded value
    /// @param k the subexponential group width
    /// @return the decoded subexponential value
    public int decodeSubexp(int reference, int symbolCount, int k) {
        if (symbolCount <= 0) {
            throw new IllegalArgumentException("symbolCount <= 0: " + symbolCount);
        }
        if (k < 0) {
            throw new IllegalArgumentException("k < 0: " + k);
        }

        int groupIndex = 0;
        int offset = 0;
        while (true) {
            int groupBits = groupIndex == 0 ? k : k + groupIndex - 1;
            int groupSize = 1 << groupBits;
            if (symbolCount <= offset + 3 * groupSize) {
                int value = decodeUniform(symbolCount - offset) + offset;
                return inverseRecenterFinite(reference, symbolCount, value);
            }

            if (!decodeBooleanEqui()) {
                int value = decodeBools(groupBits) + offset;
                return inverseRecenterFinite(reference, symbolCount, value);
            }

            groupIndex++;
            offset += groupSize;
        }
    }

    /// Re-centers a finite subexponential coded value around the supplied reference.
    ///
    /// @param reference the recentering reference value
    /// @param symbolCount the exclusive upper bound of the decoded value
    /// @param value the raw finite subexponential code value
    /// @return the decoded value in `[0, symbolCount)`
    private static int inverseRecenterFinite(int reference, int symbolCount, int value) {
        return reference * 2 <= symbolCount
                ? inverseRecenter(reference, value)
                : symbolCount - 1 - inverseRecenter(symbolCount - 1 - reference, value);
    }

    /// Refills the difference register from the remaining tile payload bytes.
    private void refill() {
        int shift = WINDOW_SIZE - count - 24;
        long localDifference = difference;
        do {
            if (position >= endOffset) {
                localDifference |= ~((~0xFFL) << shift);
                break;
            }
            localDifference |= ((long) ((data[position++] ^ 0xFF) & 0xFF)) << shift;
            shift -= Byte.SIZE;
        } while (shift >= 0);

        difference = localDifference;
        count = WINDOW_SIZE - shift - 24;
    }

    /// Renormalizes the difference and range registers after a symbol was decoded.
    ///
    /// @param localDifference the updated difference register
    /// @param localRange the updated range register
    private void normalize(long localDifference, int localRange) {
        int shift = 15 - (31 - Integer.numberOfLeadingZeros(localRange));
        int localCount = count;
        difference = localDifference << shift;
        range = localRange << shift;
        count = localCount - shift;
        if (Integer.compareUnsigned(localCount, shift) < 0) {
            refill();
        }
    }

    /// Re-centers a subexponential coded value around the supplied reference.
    ///
    /// @param reference the recentering reference value
    /// @param value the recentered code value
    /// @return the de-recentered value
    private static int inverseRecenter(int reference, int value) {
        if (value > (reference << 1)) {
            return value;
        }
        return (value & 1) == 0
                ? reference + (value >>> 1)
                : reference - ((value + 1) >>> 1);
    }
}
