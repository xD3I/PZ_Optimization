#version 330 compatibility

uniform sampler2D Texture;
uniform vec3 AmbientColour;
uniform vec3 Light0Direction, Light1Direction, Light2Direction, Light3Direction, Light4Direction;
uniform vec3 Light0Colour, Light1Colour, Light2Colour, Light3Colour, Light4Colour;
in vec2 texCoords;
in vec3 surfaceNormal;
layout(location = 0) out vec2 captured;

vec3 contribution(vec3 normal, vec3 direction, vec3 colour)
{
    float lengthSquared = dot(direction, direction);
    return lengthSquared > 0.0
               ? colour * max(dot(normal, direction * inversesqrt(lengthSquared)), 0.0)
               : vec3(0.0);
}

void main()
{
    if (texture(Texture, texCoords).a < 0.5)
        discard;
    vec3 normal = normalize(surfaceNormal);
    vec3 light = AmbientColour;
    light += contribution(normal, Light0Direction, Light0Colour);
    light += contribution(normal, Light1Direction, Light1Colour);
    light += contribution(normal, Light2Direction, Light2Colour);
    light += contribution(normal, Light3Direction, Light3Colour);
    light += contribution(normal, Light4Direction, Light4Colour);
    // Coverage is independent of lighting: darkness must not create contour boundaries.
    float brightness = dot(clamp(light, 0.0, 1.0), vec3(0.2126, 0.7152, 0.0722));
    captured = vec2(1.0, brightness);
}
