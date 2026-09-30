#version 330 core

uniform sampler2D outlineMask;
uniform ivec2 maskOrigin;  // this viewport's lower-left texel in the accumulated mask
uniform ivec4 viewport;    // destination framebuffer x, y, width, height
uniform vec4 outlineColor; // linear RGB, with opacity in A

// Composite after the mask pass with ONE, ONE_MINUS_SRC_ALPHA. Depth writes and
// depth testing are disabled by the caller. Do not bypass the world's visibility
// treatment, and do not modify targeting outlines or their framebuffer resources.
layout(location = 0) out vec4 colour;

void main()
{
    colour = vec4(0.0);
    ivec2 localPixel = ivec2(gl_FragCoord.xy) - viewport.xy;
    ivec2 maskPixel = maskOrigin + localPixel;
    if (any(lessThan(localPixel, ivec2(0))) || any(greaterThanEqual(localPixel, viewport.zw)) ||
        any(lessThan(maskPixel, ivec2(0))) ||
        any(greaterThanEqual(maskPixel, textureSize(outlineMask, 0))))
    {
        return;
    }
    vec2 mask = texelFetch(outlineMask, maskPixel, 0).rg;
    if (any(isnan(mask)) || any(isinf(mask)) || any(isnan(outlineColor)) ||
        any(isinf(outlineColor)))
    {
        return;
    }
    float opacity = clamp(outlineColor.a, 0.0, 1.0);
    float alpha = clamp(mask.r, 0.0, 1.0) * opacity;
    colour = vec4(outlineColor.rgb * clamp(mask.g, 0.0, mask.r) * opacity, alpha);
}
