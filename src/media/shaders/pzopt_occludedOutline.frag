#version 330 core

// One candidate at a time prevents overlapping zombies from joining silhouettes.
// RG8 stores coverage and surface brightness; the depth attachment keeps full precision without
// storing depth twice. Coordinates and texels use a bottom-left origin.
uniform sampler2D silhouette;
uniform sampler2D silhouetteDepth;
uniform ivec4 silhouetteRect;  // atlas x, y, width, height; includes the full silhouette
uniform ivec2 candidateOrigin; // tile's lower-left pixel relative to this player's viewport

// Opaque surfaces only, in the SAME depth convention as the silhouette. Clear = 1.
// Glass must be excluded during capture, not erased from a flattened depth image.
uniform usampler2D opaqueDepth; // positive float depth bits, accumulated with imageAtomicMin
uniform ivec2 depthOrigin;      // viewport's lower-left texel in opaqueDepth
uniform ivec4 viewport;         // framebuffer x, y, width, height
uniform bool clipEdges = false; // a viewport-clipped capture has unknown coverage beyond its bounds
uniform float candidateOpacity = 1.0;
uniform bool eligible;        // current character visibility, snapshotted for this player/frame
uniform int radius;           // inner contour width in render pixels, 1..4
uniform float depthTolerance; // normalized depth; equality never indicates occlusion

// Accumulate alpha and premultiplied brightness into RG8 with GL_MAX, then colour once.
// Sampling attachments of the active draw framebuffer is not permitted.
layout(location = 0) out vec2 coverage;

bool finiteUnit(float value)
{
    return !isnan(value) && !isinf(value) && value >= 0.0 && value <= 1.0;
}

bool inRect(ivec2 pixel, ivec2 size)
{
    return all(greaterThanEqual(pixel, ivec2(0))) && all(lessThan(pixel, size));
}

bool covered(ivec2 localPixel)
{
    if (!inRect(localPixel, silhouetteRect.zw))
    {
        return clipEdges;
    }
    float sampleValue = texelFetch(silhouette, silhouetteRect.xy + localPixel, 0).r;
    return finiteUnit(sampleValue) && sampleValue >= 0.5;
}

void main()
{
    coverage = vec2(0.0);
    if (!eligible || radius < 1 || radius > 4 || !finiteUnit(depthTolerance))
    {
        return;
    }

    // Check the entire tile, not just the current pixel: an invalid atlas rectangle
    // must not sample another candidate's tile while inspecting its neighbours.
    ivec2 atlasSize = textureSize(silhouette, 0);
    if (any(lessThan(silhouetteRect.xy, ivec2(0))) ||
        any(lessThanEqual(silhouetteRect.zw, ivec2(0))) ||
        any(greaterThan(silhouetteRect.xy, atlasSize - silhouetteRect.zw)))
    {
        return;
    }

    ivec2 viewPixel = ivec2(gl_FragCoord.xy) - viewport.xy;
    if (!inRect(viewPixel, viewport.zw))
    {
        return;
    }
    ivec2 localPixel = viewPixel - candidateOrigin;
    ivec2 depthPixel = depthOrigin + viewPixel;
    if (!inRect(localPixel, silhouetteRect.zw) || !covered(localPixel) ||
        !inRect(depthPixel, textureSize(opaqueDepth, 0)))
    {
        return;
    }

    ivec2 silhouettePixel = silhouetteRect.xy + localPixel;
    if (!inRect(silhouettePixel, textureSize(silhouetteDepth, 0)))
        return;
    float zombieDepth = texelFetch(silhouetteDepth, silhouettePixel, 0).r;
    float sceneDepth = uintBitsToFloat(texelFetch(opaqueDepth, depthPixel, 0).r);
    if (!finiteUnit(zombieDepth) || !finiteUnit(sceneDepth) ||
        !(zombieDepth - sceneDepth > depthTolerance))
    {
        return;
    }

    // The contour belongs to the COMPLETE silhouette. Neighbours' hidden status
    // is deliberately irrelevant: otherwise a car's top would draw a waistline.
    // This is an inner contour, so it never spills onto the visible upper body.
    for (int y = -4; y <= 4; ++y)
    {
        for (int x = -4; x <= 4; ++x)
        {
            if (x * x + y * y > radius * radius)
            {
                continue;
            }
            if (!covered(localPixel + ivec2(x, y)))
            {
                float light = texelFetch(silhouette, silhouettePixel, 0).g;
                // Native character/atlas shaders scale full ambient light by 0.45. Normalize
                // to that daylight reference, rather than mistaking it for 55% darkness.
                light = finiteUnit(light) ? clamp(light / 0.45, 0.0, 1.0) : 0.0;
                float alpha = finiteUnit(candidateOpacity) ? candidateOpacity * light : 0.0;
                // Premultiplied colour already includes this fade. Multiplying by light again
                // squares the attenuation and rounds dim contours to black in the RG8 mask.
                coverage = vec2(alpha);
                return;
            }
        }
    }
}
