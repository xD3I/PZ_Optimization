#version 330 core

// Full-screen triangle. The capture/composite caller owns the viewport and scissor;
// scissor each candidate to its projected bounds rather than shading the whole view.
void main()
{
    vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
