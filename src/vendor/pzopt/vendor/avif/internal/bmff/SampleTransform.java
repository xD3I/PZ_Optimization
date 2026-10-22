// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
package pzopt.vendor.avif.internal.bmff;

import pzopt.vendor.avif.AvifBitDepth;
import pzopt.vendor.avif.Av1ChromaFormat;
import pzopt.vendor.avif.Av1DecodedPlane;
import pzopt.vendor.avif.Av1DecodedPlanes;
import java.util.Objects;

/// Parsed AVIF Sample Transform expression and its ordered input image sources.
///
/// The expression uses the postfix token encoding from a `sato` derived image item. Arithmetic is
/// evaluated in its signaled signed 8-, 16-, 32-, or 64-bit domain with saturation after each
/// arithmetic operation. Color output is clamped to the unsigned range selected by the `sato`
/// item's `pixi` property and, when present, its limited-range `nclx` color information.

public final class SampleTransform {
    /// The constant operand token code.
    private static final int CONSTANT = 0;
    /// The first one-based input-image token code.
    private static final int FIRST_INPUT = 1;
    /// The last one-based input-image token code.
    private static final int LAST_INPUT = 32;
    /// The first unary-operator token code.
    private static final int FIRST_UNARY_OPERATOR = 64;
    /// The negation operator token code.
    private static final int NEGATION = 64;
    /// The absolute-value operator token code.
    private static final int ABSOLUTE = 65;
    /// The bitwise-not operator token code.
    private static final int NOT = 66;
    /// The bit-scan-reverse operator token code.
    private static final int BIT_SCAN_REVERSE = 67;
    /// The last unary-operator token code.
    private static final int LAST_UNARY_OPERATOR = 67;
    /// The first binary-operator token code.
    private static final int FIRST_BINARY_OPERATOR = 128;
    /// The addition operator token code.
    private static final int SUM = 128;
    /// The subtraction operator token code.
    private static final int DIFFERENCE = 129;
    /// The multiplication operator token code.
    private static final int PRODUCT = 130;
    /// The division operator token code.
    private static final int QUOTIENT = 131;
    /// The bitwise-and operator token code.
    private static final int AND = 132;
    /// The bitwise-or operator token code.
    private static final int OR = 133;
    /// The bitwise-exclusive-or operator token code.
    private static final int XOR = 134;
    /// The exponentiation operator token code.
    private static final int POWER = 135;
    /// The minimum operator token code.
    private static final int MINIMUM = 136;
    /// The maximum operator token code.
    private static final int MAXIMUM = 137;
    /// The last binary-operator token code.
    private static final int LAST_BINARY_OPERATOR = 137;

    /// The reconstructed sample bit depth.
    private final AvifBitDepth bitDepth;
    /// Whether color output uses the full unsigned sample range.
    private final boolean fullRange;
    /// The signed intermediate arithmetic bit depth.
    private final int intermediateBitDepth;
    /// The inclusive minimum intermediate value.
    private final long intermediateMinValue;
    /// The inclusive maximum intermediate value.
    private final long intermediateMaxValue;
    /// Postfix token codes in evaluation order.
    private final int [] tokenCodes;
    /// Signed constant values corresponding to constant tokens.
    private final long [] constantValues;
    /// Ordered Sample Transform inputs.
    private final Input [] inputs;
    /// The zero-based input index containing the primary image item.
    private final int primaryInputIndex;

    /// Creates a parsed Sample Transform.
    ///
    /// Input-image tokens use their one-based input index as the token code. Values in
    /// `constantValues` are read only where the matching token code is zero.
    ///
    /// @param bitDepth the reconstructed sample bit depth
    /// @param fullRange whether color output uses the full unsigned sample range
    /// @param intermediateBitDepth the signed intermediate arithmetic bit depth
    /// @param tokenCodes the postfix token codes
    /// @param constantValues the signed values associated with constant tokens
    /// @param inputs the ordered derived-image inputs
    /// @param primaryInputIndex the zero-based input index containing the primary image item
    public SampleTransform(
            AvifBitDepth bitDepth,
            boolean fullRange,
            int intermediateBitDepth,
            int[] tokenCodes,
            long[] constantValues,
            Input[] inputs,
            int primaryInputIndex
    ) {
        this.bitDepth = Objects.requireNonNull(bitDepth, "bitDepth");
        this.fullRange = fullRange;
        if (intermediateBitDepth != 8
                && intermediateBitDepth != 16
                && intermediateBitDepth != 32
                && intermediateBitDepth != 64) {
            throw new IllegalArgumentException("Unsupported intermediate bit depth: " + intermediateBitDepth);
        }
        this.intermediateBitDepth = intermediateBitDepth;
        this.intermediateMinValue = intermediateBitDepth == 64
                ? Long.MIN_VALUE
                : -(1L << (intermediateBitDepth - 1));
        this.intermediateMaxValue = intermediateBitDepth == 64
                ? Long.MAX_VALUE
                : (1L << (intermediateBitDepth - 1)) - 1L;
        this.tokenCodes = Objects.requireNonNull(tokenCodes, "tokenCodes").clone();
        this.constantValues = Objects.requireNonNull(constantValues, "constantValues").clone();
        this.inputs = Objects.requireNonNull(inputs, "inputs").clone();
        if (this.tokenCodes.length == 0 || this.tokenCodes.length > 255) {
            throw new IllegalArgumentException("Sample Transform token count must be in [1, 255]");
        }
        if (this.constantValues.length != this.tokenCodes.length) {
            throw new IllegalArgumentException("constantValues length must match tokenCodes length");
        }
        if (this.inputs.length == 0 || this.inputs.length > LAST_INPUT) {
            throw new IllegalArgumentException("Sample Transform input count must be in [1, 32]");
        }
        if (primaryInputIndex < 0 || primaryInputIndex >= this.inputs.length) {
            throw new IllegalArgumentException("primaryInputIndex out of range: " + primaryInputIndex);
        }
        this.primaryInputIndex = primaryInputIndex;
        for (int i = 0; i < this.inputs.length; i++) {
            Objects.requireNonNull(this.inputs[i], "inputs[" + i + "]");
        }
        validateExpression();
    }

    /// Returns the reconstructed sample bit depth.
    ///
    /// @return the reconstructed sample bit depth
    public AvifBitDepth bitDepth() {
        return bitDepth;
    }

    /// Returns whether color output uses the full unsigned sample range.
    ///
    /// @return whether color output uses the full unsigned sample range
    public boolean fullRange() {
        return fullRange;
    }

    /// Returns the number of ordered input images.
    ///
    /// @return the input image count
    public int inputCount() {
        return inputs.length;
    }

    /// Returns one ordered input descriptor.
    ///
    /// @param index the zero-based input index
    /// @return the input descriptor
    public Input input(int index) {
        return inputs[index];
    }

    /// Returns the index of the input containing the primary image item.
    ///
    /// @return the zero-based primary input index
    public int primaryInputIndex() {
        return primaryInputIndex;
    }

    /// Applies this expression to decoded input planes.
    ///
    /// Every input must have identical coded and render dimensions, chroma layout, and plane
    /// dimensions. Input bit depths may differ. The returned planes use tightly packed rows and the
    /// bit depth and color range declared by the Sample Transform.
    ///
    /// @param inputPlanes decoded planes in `dimg` reference order
    /// @return the reconstructed planes
    public Av1DecodedPlanes apply(Av1DecodedPlanes [] inputPlanes) {
        return applyPlanes(inputPlanes, fullRange);
    }

    /// Applies this expression to decoded alpha input planes.
    ///
    /// Alpha output always uses the complete unsigned range selected by the Sample Transform bit
    /// depth, independently of the color image's `nclx` range signaling.
    ///
    /// @param inputPlanes decoded alpha planes in `dimg` reference order
    /// @return the reconstructed alpha planes
    public Av1DecodedPlanes applyAlpha(Av1DecodedPlanes [] inputPlanes) {
        return applyPlanes(inputPlanes, true);
    }

    /// Applies this expression with an explicit output range policy.
    ///
    /// @param inputPlanes decoded planes in `dimg` reference order
    /// @param outputFullRange whether the output uses the full unsigned sample range
    /// @return the reconstructed planes
    private Av1DecodedPlanes applyPlanes(
            Av1DecodedPlanes [] inputPlanes,
            boolean outputFullRange
    ) {
        Av1DecodedPlanes[] checkedInputs = Objects.requireNonNull(inputPlanes, "inputPlanes");
        if (checkedInputs.length != inputs.length) {
            throw new IllegalArgumentException(
                    "Decoded Sample Transform input count mismatch: " + checkedInputs.length + " != " + inputs.length
            );
        }
        Av1DecodedPlanes first = Objects.requireNonNull(checkedInputs[0], "inputPlanes[0]");
        for (int i = 1; i < checkedInputs.length; i++) {
            validateCompatiblePlanes(first, Objects.requireNonNull(checkedInputs[i], "inputPlanes[" + i + "]"));
        }

        Av1DecodedPlane lumaPlane = applyPlane(lumaPlanes(checkedInputs), false, outputFullRange);
        Av1DecodedPlane chromaUPlane = null;
        Av1DecodedPlane chromaVPlane = null;
        if (first.chromaFormat() != Av1ChromaFormat.MONOCHROME) {
            chromaUPlane = applyPlane(chromaPlanes(checkedInputs, true), true, outputFullRange);
            chromaVPlane = applyPlane(chromaPlanes(checkedInputs, false), true, outputFullRange);
        }
        return new Av1DecodedPlanes(
                bitDepth,
                first.chromaFormat(),
                first.codedWidth(),
                first.codedHeight(),
                first.renderWidth(),
                first.renderHeight(),
                lumaPlane,
                chromaUPlane,
                chromaVPlane
        );
    }

    /// Validates the postfix expression and its input references.
    private void validateExpression() {
        int stackSize = 0;
        for (int tokenIndex = 0; tokenIndex < tokenCodes.length; tokenIndex++) {
            int tokenCode = tokenCodes[tokenIndex];
            if (tokenCode == CONSTANT) {
                long constant = constantValues[tokenIndex];
                if (constant < intermediateMinValue || constant > intermediateMaxValue) {
                    throw new IllegalArgumentException(
                            "Sample Transform constant exceeds intermediate range: " + constant
                    );
                }
                stackSize++;
            } else if (tokenCode >= FIRST_INPUT && tokenCode <= LAST_INPUT) {
                if (tokenCode > inputs.length) {
                    throw new IllegalArgumentException("Sample Transform input index out of range: " + tokenCode);
                }
                stackSize++;
            } else if (tokenCode >= FIRST_UNARY_OPERATOR && tokenCode <= LAST_UNARY_OPERATOR) {
                if (stackSize < 1) {
                    throw new IllegalArgumentException("Sample Transform unary operator has no operand");
                }
            } else if (tokenCode >= FIRST_BINARY_OPERATOR && tokenCode <= LAST_BINARY_OPERATOR) {
                if (stackSize < 2) {
                    throw new IllegalArgumentException("Sample Transform binary operator has fewer than two operands");
                }
                stackSize--;
            } else {
                throw new IllegalArgumentException("Reserved Sample Transform token code: " + tokenCode);
            }
        }
        if (stackSize != 1) {
            throw new IllegalArgumentException("Sample Transform expression leaves " + stackSize + " stack values");
        }
    }

    /// Validates that two decoded input images have the same plane geometry.
    ///
    /// @param expected the first decoded input
    /// @param actual another decoded input
    private static void validateCompatiblePlanes(Av1DecodedPlanes expected, Av1DecodedPlanes actual) {
        if (actual.chromaFormat() != expected.chromaFormat()
                || actual.codedWidth() != expected.codedWidth()
                || actual.codedHeight() != expected.codedHeight()) {
            throw new IllegalArgumentException("Sample Transform input plane layouts differ");
        }
    }

    /// Collects luma planes from decoded inputs.
    ///
    /// @param inputPlanes the decoded inputs
    /// @return the ordered luma planes
    private static Av1DecodedPlane [] lumaPlanes(Av1DecodedPlanes [] inputPlanes) {
        Av1DecodedPlane[] planes = new Av1DecodedPlane[inputPlanes.length];
        for (int i = 0; i < inputPlanes.length; i++) {
            planes[i] = inputPlanes[i].lumaPlane();
        }
        return planes;
    }

    /// Collects one chroma component from decoded inputs.
    ///
    /// @param inputPlanes the decoded inputs
    /// @param chromaU whether to collect U rather than V
    /// @return the ordered chroma planes
    private static Av1DecodedPlane [] chromaPlanes(
            Av1DecodedPlanes [] inputPlanes,
            boolean chromaU
    ) {
        Av1DecodedPlane[] planes = new Av1DecodedPlane[inputPlanes.length];
        for (int i = 0; i < inputPlanes.length; i++) {
            Av1DecodedPlane plane = chromaU ? inputPlanes[i].chromaUPlane() : inputPlanes[i].chromaVPlane();
            if (plane == null) {
                throw new IllegalArgumentException("Sample Transform chroma input is missing");
            }
            planes[i] = plane;
        }
        return planes;
    }

    /// Applies the expression to one component plane.
    ///
    /// @param inputPlanes the matching component plane from every input image
    /// @param chroma whether the component is chroma rather than luma
    /// @param outputFullRange whether the output uses the full unsigned sample range
    /// @return the reconstructed component plane
    private Av1DecodedPlane applyPlane(
            Av1DecodedPlane [] inputPlanes,
            boolean chroma,
            boolean outputFullRange
    ) {
        Av1DecodedPlane first = inputPlanes[0];
        int width = first.width();
        int height = first.height();
        for (int i = 1; i < inputPlanes.length; i++) {
            Av1DecodedPlane plane = inputPlanes[i];
            if (plane.width() != width || plane.height() != height) {
                throw new IllegalArgumentException("Sample Transform input component dimensions differ");
            }
        }

        short[] output = new short[Math.multiplyExact(width, height)];
        long[] stack = new long[tokenCodes.length];
        int rangeShift = bitDepth.bits() - 8;
        int minSample = outputFullRange ? 0 : 16 << rangeShift;
        int maxSample = outputFullRange
                ? bitDepth.maxSampleValue()
                : (chroma ? 240 : 235) << rangeShift;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long value = evaluateSample(inputPlanes, x, y, stack);
                output[y * width + x] = (short) Math.max(minSample, Math.min(maxSample, value));
            }
        }
        return new Av1DecodedPlane(width, height, width, output);
    }

    /// Evaluates one output sample.
    ///
    /// @param inputPlanes the matching component plane from every input image
    /// @param x the sample x coordinate
    /// @param y the sample y coordinate
    /// @param stack reusable expression stack storage
    /// @return the signed expression result before final unsigned clamping
    private long evaluateSample(
            Av1DecodedPlane [] inputPlanes,
            int x,
            int y,
            long[] stack
    ) {
        int stackSize = 0;
        for (int tokenIndex = 0; tokenIndex < tokenCodes.length; tokenIndex++) {
            int tokenCode = tokenCodes[tokenIndex];
            if (tokenCode == CONSTANT) {
                stack[stackSize++] = constantValues[tokenIndex];
            } else if (tokenCode <= LAST_INPUT) {
                int sample = inputPlanes[tokenCode - 1].sample(x, y);
                if (sample > intermediateMaxValue) {
                    throw new IllegalArgumentException(
                            "Sample Transform input exceeds intermediate range: " + sample
                    );
                }
                stack[stackSize++] = sample;
            } else if (tokenCode <= LAST_UNARY_OPERATOR) {
                stack[stackSize - 1] = applyUnary(stack[stackSize - 1], tokenCode);
            } else {
                stack[stackSize - 2] = applyBinary(stack[stackSize - 2], stack[stackSize - 1], tokenCode);
                stackSize--;
            }
        }
        return stack[0];
    }

    /// Applies one signed intermediate-width unary operation.
    ///
    /// @param operand the operand
    /// @param operator the operator token code
    /// @return the operation result
    private long applyUnary(long operand, int operator) {
        return switch (operator) {
            case NEGATION -> negate(operand);
            case ABSOLUTE -> operand >= 0 ? operand : negate(operand);
            case NOT -> clampIntermediate(~operand);
            case BIT_SCAN_REVERSE -> operand <= 0 ? 0 : 63 - Long.numberOfLeadingZeros(operand);
            default -> throw new IllegalArgumentException("Unsupported Sample Transform unary operator: " + operator);
        };
    }

    /// Applies one signed intermediate-width binary operation.
    ///
    /// @param left the left operand
    /// @param right the right operand
    /// @param operator the operator token code
    /// @return the operation result
    private long applyBinary(long left, long right, int operator) {
        return switch (operator) {
            case SUM -> add(left, right);
            case DIFFERENCE -> subtract(left, right);
            case PRODUCT -> multiply(left, right);
            case QUOTIENT -> divide(left, right);
            case AND -> left & right;
            case OR -> left | right;
            case XOR -> left ^ right;
            case POWER -> power(left, right);
            case MINIMUM -> Math.min(left, right);
            case MAXIMUM -> Math.max(left, right);
            default -> throw new IllegalArgumentException("Unsupported Sample Transform binary operator: " + operator);
        };
    }

    /// Computes signed integer exponentiation using the Sample Transform edge-case rules.
    ///
    /// @param base the base operand
    /// @param exponent the exponent operand
    /// @return the saturated signed intermediate-width result
    private long power(long base, long exponent) {
        if (base == 0 || base == 1) {
            return base;
        }
        if (base == -1) {
            return (exponent & 1) == 0 ? 1 : -1;
        }
        if (exponent == 0) {
            return 1;
        }
        if (exponent == 1) {
            return base;
        }
        if (exponent < 0) {
            return 0;
        }

        long result = 1;
        long factor = base;
        long remaining = exponent;
        while (remaining != 0) {
            if ((remaining & 1L) != 0) {
                result = multiply(result, factor);
            }
            remaining >>>= 1;
            if (remaining != 0) {
                factor = multiply(factor, factor);
            }
        }
        return result;
    }

    /// Negates one intermediate value with signed saturation.
    ///
    /// @param value the value to negate
    /// @return the saturated negated value
    private long negate(long value) {
        if (value == Long.MIN_VALUE) {
            return intermediateMaxValue;
        }
        return clampIntermediate(-value);
    }

    /// Adds two intermediate values with signed saturation.
    ///
    /// @param left the left operand
    /// @param right the right operand
    /// @return the saturated sum
    private long add(long left, long right) {
        try {
            return clampIntermediate(Math.addExact(left, right));
        } catch (ArithmeticException exception) {
            return left < 0 ? intermediateMinValue : intermediateMaxValue;
        }
    }

    /// Subtracts two intermediate values with signed saturation.
    ///
    /// @param left the left operand
    /// @param right the right operand
    /// @return the saturated difference
    private long subtract(long left, long right) {
        try {
            return clampIntermediate(Math.subtractExact(left, right));
        } catch (ArithmeticException exception) {
            return left < 0 ? intermediateMinValue : intermediateMaxValue;
        }
    }

    /// Multiplies two intermediate values with signed saturation.
    ///
    /// @param left the left operand
    /// @param right the right operand
    /// @return the saturated product
    private long multiply(long left, long right) {
        try {
            return clampIntermediate(Math.multiplyExact(left, right));
        } catch (ArithmeticException exception) {
            return (left < 0) == (right < 0) ? intermediateMaxValue : intermediateMinValue;
        }
    }

    /// Divides two intermediate values with the Sample Transform zero-divisor rule.
    ///
    /// @param left the dividend
    /// @param right the divisor
    /// @return the saturated quotient, or `left` when `right` is zero
    private long divide(long left, long right) {
        if (right == 0) {
            return left;
        }
        if (left == Long.MIN_VALUE && right == -1) {
            return intermediateMaxValue;
        }
        return clampIntermediate(left / right);
    }

    /// Clamps one signed value to the configured intermediate range.
    ///
    /// @param value the value to clamp
    /// @return the saturated intermediate value
    private long clampIntermediate(long value) {
        if (value <= intermediateMinValue) {
            return intermediateMinValue;
        }
        if (value >= intermediateMaxValue) {
            return intermediateMaxValue;
        }
        return value;
    }

    /// One ordered Sample Transform input and its matching optional alpha image.
    ///
    /// @param colorSource the color image source
    /// @param colorBitDepth the coded bit depth required for a matching alpha image
    /// @param alphaSource the matching alpha image source, or `null`
    
    public record Input(
            AvifImageSource colorSource,
            AvifBitDepth colorBitDepth,
            AvifImageSource alphaSource
    ) {
        /// Creates an ordered Sample Transform input.
        public Input {
            Objects.requireNonNull(colorSource, "colorSource");
            Objects.requireNonNull(colorBitDepth, "colorBitDepth");
        }
    }

}
