#version 330 compatibility

layout(location = 0) in vec4 vertex;
layout(location = 1) in vec4 normal;
layout(location = 2) in vec2 uv;
uniform mat4 ModelViewProjection;
uniform mat4 transform;
uniform float FinalScale = 1.0;
uniform float targetDepth = 0.5;
uniform vec2 UVScale = vec2(1.0);
out vec2 texCoords;
out vec3 surfaceNormal;

void main()
{
    vec4 position = transform * vec4(vertex.xyz, 1.0);
    surfaceNormal = (transform * vec4(normal.xyz, 0.0)).xyz;
    position.xyz *= FinalScale;
    gl_Position = ModelViewProjection * position;
    gl_Position.z += 2.0 * (targetDepth - 0.5);
    texCoords = uv * UVScale;
}
