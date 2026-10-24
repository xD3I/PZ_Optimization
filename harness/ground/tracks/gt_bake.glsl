// The ground tracks drawn into a chunk texture (pzopt.GroundTracks): the chunk's floor diamond at its level, after the
// floors and the snow of the bake and before the blood and the objects, blended as a factor (dst * 2 src) so the floor
// art, the game's light and anything baked onto it later keep their own colour.
//#VERT
uniform mat4 uMVP;
uniform vec4 uGo; // goX, goY, tile scale, the floor level
out vec2 vP;     // world squares relative to the chunk's corner
void main() {
   int c = gl_VertexID;
   vec2 p = vec2((c == 1 || c == 3) ? 8.0 : 0.0, (c >= 2) ? 8.0 : 0.0);
   float ts = uGo.z;
   float sx = (p.x - p.y) * (32.0 * ts);
   float sy = (p.x + p.y) * (16.0 * ts) - uGo.w * (96.0 * ts);
   gl_Position = uMVP * vec4(sx + uGo.x, sy + uGo.y, 0.0, 1.0);
   gl_Position.z = 0.0;
   vP = p;
}
//#FRAG
uniform sampler2D uState;
uniform vec4 uUv;      // the chunk corner's uv in the state map, uv per square
uniform ivec4 uMat[2]; // the 64 squares' materials, 4 bits each (8 a word)
uniform vec4 uLight;   // the key light (world x east, y south, z up), w the direct-light share
uniform vec4 uWeather; // x wet, y snow depth (squares), z dev view
uniform vec3 uSky;
in vec2 vP;
out vec4 fragColor;
vec4 gtS(vec2 p) {
   vec4 r = texture(uState, uUv.xy + p * uUv.z);
   return vec4((r.x * 255.0 - 192.0) / 510.0, r.yzw);
}
// (gt_look.glsl follows)
void main() {
   ivec2 q = clamp(ivec2(floor(vP)), ivec2(0), ivec2(7));
   int i = q.x + q.y * 8;
   int w = i >> 3;
   int m = (uMat[w >> 2][w & 3] >> ((i & 7) * 4)) & 15;
   if (m == 0 || m > 5) {
      discard;
   }
   vec3 f = gtFactor(vP, m, uLight.xyz, uLight.w, uWeather.x, uWeather.y, uSky);
   if (uWeather.z == 1.0) {
      float h = gtS(vP).x;
      f = h < 0.0 ? vec3(1.0 + h * 8.0, 1.0 + h * 8.0, 2.0) : vec3(1.0 + h * 30.0, 1.0, 1.0);
   }
   fragColor = vec4(clamp(f * 0.5, 0.0, 1.0), 0.5);
}
