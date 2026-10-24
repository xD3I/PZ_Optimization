// The GPU half of harness/ground/rig.py (2026-10-24): draws a field of floor tiles into an offscreen "chunk texture" with
// a ground floor variant fragment shader (pzopt.GroundBake.variantSource of the game's tileWithDepth / opaqueWithDepth)
// the way the bake does: logical pixels = gl_FragCoord (y grows south, as the game's flipped bake projection), the
// variant's pzgMap / pzgBake / class map / ground state uniforms set as GroundBake.chunkStart sets them. A compatibility
// context (the game's tile programs are GLSL 1.20-1.30 with varyings and gl_FragColor; moderngl's EGL contexts are core).
//   cc -O2 -o build/floorrig tools/ground/floorrig.c -lEGL -lGL
//   build/floorrig <job file>
// Job file (text, one item a line):
//   size W H                       the bake texture
//   frag <path>                    the variant fragment source
//   atlas <path.rgba> W H          RGBA8 atlas, rows top first
//   class <path.r8>                512 x 512 class map bytes (row = world y & 511)
//   state <path.rgba>              1024 x 1024 ground state (optional)
//   map A B C D                    pzgMap
//   bake L VIEW                    pzgBake.x (level), .y (dev view)
//   stateinfo TPS SIZE NOW ON      pzgStateInfo
//   uniform1f NAME V / uniform1i NAME V
//   sampler NAME UNIT <path.rgba> W H   an extra RGBA8 texture for a patch
//   quad x0 y0 x1 y1 u0 v0 u1 v1 r g b a     a sprite (logical px, atlas uv 0..1, vertex colour)
//   out <path.rgba>                the result (rows: logical y 0 first)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *slurp(const char *p, long *len) {
   FILE *f = fopen(p, "rb");
   if (!f) { perror(p); exit(2); }
   fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
   char *b = malloc(n + 1); if (fread(b, 1, n, f) != (size_t)n) { perror(p); exit(2); } b[n] = 0; fclose(f);
   if (len) *len = n;
   return b;
}

static GLuint unit(GLenum type, const char *src, const char *what) {
   GLuint s = glCreateShader(type);
   glShaderSource(s, 1, &src, NULL);
   glCompileShader(s);
   GLint ok; char log[16384];
   glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   glGetShaderInfoLog(s, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "%s: compile FAILED\n%s\n", what, log); exit(1); }
   return s;
}

static GLuint tex2d(int w, int h, GLenum ifmt, GLenum fmt, const void *data, int linear, int repeat) {
   GLuint t; glGenTextures(1, &t);
   glBindTexture(GL_TEXTURE_2D, t);
   glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
   glTexImage2D(GL_TEXTURE_2D, 0, ifmt, w, h, 0, fmt, GL_UNSIGNED_BYTE, data);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
   return t;
}

#define MAXQ 200000
static float quads[MAXQ][12];

int main(int argc, char **argv) {
   if (argc < 2) { fprintf(stderr, "usage: floorrig job.txt\n"); return 2; }
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   if (!eglInitialize(d, NULL, NULL)) { fprintf(stderr, "no EGL display\n"); return 1; }
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                  EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (!eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   fprintf(stderr, "GL %s\n", glGetString(GL_RENDERER));

   FILE *job = fopen(argv[1], "r");
   if (!job) { perror(argv[1]); return 2; }
   char line[4096], a[1024], b[1024];
   int W = 256, H = 256, nq = 0;
   char *frag = NULL, out[1024] = "";
   float map[4] = {0}, bake[2] = {0}, sinfo[4] = {8, 1024, 0, 0};
   GLuint atlas = 0, classTex = 0, stateTex = 0;
   struct { char name[128]; float v; int isInt; } un[64]; int nun = 0;
   struct { char name[128]; int unit; GLuint t; } sm[8]; int nsm = 0;
   while (fgets(line, sizeof line, job)) {
      if (sscanf(line, "size %d %d", &W, &H) == 2) continue;
      if (sscanf(line, "frag %1023s", a) == 1) { frag = slurp(a, NULL); continue; }
      int w, h, u;
      if (sscanf(line, "atlas %1023s %d %d", a, &w, &h) == 3) { atlas = tex2d(w, h, GL_RGBA8, GL_RGBA, slurp(a, NULL), 0, 0); continue; }
      if (sscanf(line, "class %1023s", a) == 1) { classTex = tex2d(512, 512, GL_R8, GL_RED, slurp(a, NULL), 0, 1); continue; }
      if (sscanf(line, "stateinfo %f %f %f %f", &sinfo[0], &sinfo[1], &sinfo[2], &sinfo[3]) == 4) continue;
      if (strncmp(line, "state ", 6) == 0 && sscanf(line, "state %1023s", a) == 1) { stateTex = tex2d(1024, 1024, GL_RGBA8, GL_RGBA, slurp(a, NULL), 1, 1); continue; }
      if (sscanf(line, "map %f %f %f %f", &map[0], &map[1], &map[2], &map[3]) == 4) continue;
      if (sscanf(line, "bake %f %f", &bake[0], &bake[1]) == 2) continue;
      float v;
      if (sscanf(line, "uniform1f %127s %f", un[nun].name, &v) == 2) { un[nun].v = v; un[nun++].isInt = 0; continue; }
      if (sscanf(line, "uniform1i %127s %f", un[nun].name, &v) == 2) { un[nun].v = v; un[nun++].isInt = 1; continue; }
      if (sscanf(line, "sampler %127s %d %1023s %d %d", b, &u, a, &w, &h) == 5) {
         strcpy(sm[nsm].name, b); sm[nsm].unit = u; sm[nsm].t = tex2d(w, h, GL_RGBA8, GL_RGBA, slurp(a, NULL), 1, 1); nsm++; continue;
      }
      float *q = quads[nq];
      if (nq < MAXQ && sscanf(line, "quad %f %f %f %f %f %f %f %f %f %f %f %f", q, q + 1, q + 2, q + 3, q + 4, q + 5, q + 6, q + 7, q + 8, q + 9, q + 10, q + 11) == 12) { nq++; continue; }
      if (sscanf(line, "out %1023s", out) == 1) continue;
   }
   if (!frag || !atlas || !out[0]) { fprintf(stderr, "job needs frag, atlas, out\n"); return 2; }
   // the stock vertex outputs the fragment programs read: col, texCoord, texCoord2 (the depth texture: one white texel)
   const char *vert =
      "#version 120\n"
      "attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aCol;\n"
      "uniform vec2 uSize;\n"
      "varying vec4 col; varying vec2 texCoord; varying vec2 texCoord2;\n"
      "void main() { col = aCol; texCoord = aUv; texCoord2 = vec2(0.5); gl_Position = vec4(aPos / uSize * 2.0 - 1.0, 0.5, 1.0); }\n";
   GLuint p = glCreateProgram();
   glAttachShader(p, unit(GL_VERTEX_SHADER, vert, "vertex"));
   glAttachShader(p, unit(GL_FRAGMENT_SHADER, frag, "variant"));
   glBindAttribLocation(p, 0, "aPos"); glBindAttribLocation(p, 1, "aUv"); glBindAttribLocation(p, 2, "aCol");
   glLinkProgram(p);
   GLint ok; char log[16384];
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "link FAILED\n%s\n", log); return 1; }
   unsigned char white[4] = {255, 255, 255, 255}, zero4[4] = {0, 0, 0, 0};
   GLuint depthTex = tex2d(1, 1, GL_RGBA8, GL_RGBA, white, 0, 0);
   if (!classTex) { unsigned char *z = calloc(512 * 512, 1); classTex = tex2d(512, 512, GL_R8, GL_RED, z, 0, 1); }
   if (!stateTex) stateTex = tex2d(1, 1, GL_RGBA8, GL_RGBA, zero4, 1, 1);
   glUseProgram(p);
#define U1I(n, v) do { GLint l = glGetUniformLocation(p, n); if (l >= 0) glUniform1i(l, v); } while (0)
#define U1F(n, v) do { GLint l = glGetUniformLocation(p, n); if (l >= 0) glUniform1f(l, v); } while (0)
   U1I("DIFFUSE", 0); U1I("DEPTH", 1); U1I("pzgClassTex", 2); U1I("pzgStateTex", 3);
   U1I("drawPixels", 1); U1F("zDepthBlendZ", 0.5f); U1F("zDepthBlendToZ", 0.5f);
   { GLint l = glGetUniformLocation(p, "pzgMap"); if (l >= 0) glUniform4f(l, map[0], map[1], map[2], map[3]); }
   { GLint l = glGetUniformLocation(p, "pzgBake"); if (l >= 0) glUniform4f(l, bake[0], bake[1], 0, 0); }
   { GLint l = glGetUniformLocation(p, "pzgStateInfo"); if (l >= 0) glUniform4f(l, sinfo[0], sinfo[1], sinfo[2], sinfo[3]); }
   { GLint l = glGetUniformLocation(p, "uSize"); if (l >= 0) glUniform2f(l, (float)W, (float)H); }
   for (int i = 0; i < nun; i++) { if (un[i].isInt) U1I(un[i].name, (int)un[i].v); else U1F(un[i].name, un[i].v); }
   glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, atlas);
   glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, depthTex);
   glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, classTex);
   glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, stateTex);
   for (int i = 0; i < nsm; i++) {
      U1I(sm[i].name, 4 + i);
      glActiveTexture(GL_TEXTURE4 + i); glBindTexture(GL_TEXTURE_2D, sm[i].t);
   }
   GLuint ct, db, fbo;
   glActiveTexture(GL_TEXTURE7); // the target's own unit: unit 0 keeps the atlas
   glGenTextures(1, &ct); glBindTexture(GL_TEXTURE_2D, ct);
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
   glGenRenderbuffers(1, &db); glBindRenderbuffer(GL_RENDERBUFFER, db);
   glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
   glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, ct, 0);
   glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, db);
   if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { fprintf(stderr, "fbo incomplete\n"); return 1; }
   glActiveTexture(GL_TEXTURE0);
   glViewport(0, 0, W, H);
   glClearColor(0, 0, 0, 0); glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
   glDisable(GL_DEPTH_TEST);
   glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
   // two triangles a quad: pos, uv, colour
   float *vb = malloc((size_t)nq * 6 * 8 * sizeof(float));
   for (int i = 0; i < nq; i++) {
      float *q = quads[i];
      float cs[6][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 0}, {1, 1}, {0, 1}};
      for (int k = 0; k < 6; k++) {
         float *o = vb + ((size_t)i * 6 + k) * 8;
         o[0] = q[0] + (q[2] - q[0]) * cs[k][0]; o[1] = q[1] + (q[3] - q[1]) * cs[k][1];
         o[2] = q[4] + (q[6] - q[4]) * cs[k][0]; o[3] = q[5] + (q[7] - q[5]) * cs[k][1];
         o[4] = q[8]; o[5] = q[9]; o[6] = q[10]; o[7] = q[11];
      }
   }
   GLuint vao, vbo;
   glGenVertexArrays(1, &vao); glBindVertexArray(vao);
   glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
   glBufferData(GL_ARRAY_BUFFER, (size_t)nq * 6 * 8 * sizeof(float), vb, GL_STATIC_DRAW);
   glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 32, (void *)0);
   glEnableVertexAttribArray(1); glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 32, (void *)8);
   glEnableVertexAttribArray(2); glVertexAttribPointer(2, 4, GL_FLOAT, GL_FALSE, 32, (void *)16);
   glDrawArrays(GL_TRIANGLES, 0, nq * 6);
   glFinish();
   unsigned char *px = malloc((size_t)W * H * 4);
   glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
   GLenum e = glGetError();
   if (e != GL_NO_ERROR) { fprintf(stderr, "gl error 0x%x\n", e); return 1; }
   FILE *of = fopen(out, "wb");
   fwrite(px, 1, (size_t)W * H * 4, of);
   fclose(of);
   fprintf(stderr, "%d quads into %d x %d\n", nq, W, H);
   return 0;
}
