// GroundState's GPU half without the game (2026-10-24): compiles the compute source (GroundState.computeSource), clears a
// TN x TN RGBA8 map to the rest texel, runs the decay and stamp dispatches the way GroundState.renderFrame does (relax after
// each when asked), then reads a region back the way the persistence does (glGetTextureSubImage into a pixel pack buffer,
// a fence polled with a zero timeout, read through a persistent coherent mapping) and writes it out. tests/pzopt/GroundCommonTest drives it with the game thread's own
// binning; run under Mesa too (__EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json).
//   cc -O2 -o build/gsrun tools/ground/gsrun.c -lEGL -lGL
//   build/gsrun <job file>
// Job file (text):
//   src <path>                   the compute source
//   tn N                         map size (texels)
//   cells <path> N / prims <path> N / index <path> N / decay <path> N    little-endian uint32 / float32 arrays
//   relax 0|1 / tick T / weather r s t w
//   readback X Y W H <out.rgba>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static void *slurp(const char *p, long *len) {
   FILE *f = fopen(p, "rb");
   if (!f) { perror(p); exit(2); }
   fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
   char *b = malloc(n + 1); if (n && fread(b, 1, n, f) != (size_t)n) { perror(p); exit(2); } b[n] = 0; fclose(f);
   if (len) *len = n;
   return b;
}

static GLuint ssbo(const void *data, long bytes) {
   GLuint b; glGenBuffers(1, &b);
   glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
   glBufferData(GL_SHADER_STORAGE_BUFFER, bytes > 0 ? bytes : 16, data, GL_DYNAMIC_DRAW);
   return b;
}

int main(int argc, char **argv) {
   if (argc < 2) { fprintf(stderr, "usage: gsrun job.txt\n"); return 2; }
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   if (!eglInitialize(d, NULL, NULL)) { fprintf(stderr, "no context\n"); return 1; }
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 5, EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (c == EGL_NO_CONTEXT || !eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   fprintf(stderr, "GL %s\n", glGetString(GL_RENDERER));

   FILE *job = fopen(argv[1], "r");
   if (!job) { perror(argv[1]); return 2; }
   char line[4096], a[1024], out[1024] = "";
   char *src = NULL;
   int tn = 2048, relax = 0, tick = 1, nCells = 0, nPrims = 0, nIndex = 0, nDecay = 0, rx = 0, ry = 0, rw = 0, rh = 0;
   void *cells = NULL, *prims = NULL, *index = NULL, *decay = NULL;
   float weather[4] = {0, 0, 15, 0};
   while (fgets(line, sizeof line, job)) {
      int k;
      if (sscanf(line, "src %1023s", a) == 1) { src = slurp(a, NULL); continue; }
      if (sscanf(line, "tn %d", &tn) == 1) continue;
      if (sscanf(line, "relax %d", &relax) == 1) continue;
      if (sscanf(line, "tick %d", &tick) == 1) continue;
      if (sscanf(line, "weather %f %f %f %f", weather, weather + 1, weather + 2, weather + 3) == 4) continue;
      if (sscanf(line, "cells %1023s %d", a, &k) == 2) { cells = slurp(a, NULL); nCells = k; continue; }
      if (sscanf(line, "prims %1023s %d", a, &k) == 2) { prims = slurp(a, NULL); nPrims = k; continue; }
      if (sscanf(line, "index %1023s %d", a, &k) == 2) { index = slurp(a, NULL); nIndex = k; continue; }
      if (sscanf(line, "decay %1023s %d", a, &k) == 2) { decay = slurp(a, NULL); nDecay = k; continue; }
      if (sscanf(line, "readback %d %d %d %d %1023s", &rx, &ry, &rw, &rh, out) == 5) continue;
   }
   if (!src || !out[0]) { fprintf(stderr, "job needs src and readback\n"); return 2; }
   GLuint sh = glCreateShader(GL_COMPUTE_SHADER);
   glShaderSource(sh, 1, (const char **)&src, NULL);
   glCompileShader(sh);
   GLint ok; char log[16384];
   glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
   glGetShaderInfoLog(sh, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "compile FAILED\n%s\n", log); return 1; }
   GLuint p = glCreateProgram();
   glAttachShader(p, sh);
   glLinkProgram(p);
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "link FAILED\n%s\n", log); return 1; }
   printf("link ok\n");
   GLuint tex; glGenTextures(1, &tex);
   glBindTexture(GL_TEXTURE_2D, tex);
   glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, tn, tn);
   unsigned char rest[4] = {192, 0, 0, 0};
   glClearTexImage(tex, 0, GL_RGBA, GL_UNSIGNED_BYTE, rest);
   glUseProgram(p);
   glBindImageTexture(0, tex, 0, GL_FALSE, 0, GL_READ_WRITE, GL_RGBA8);
   glUniform1ui(glGetUniformLocation(p, "gsTick"), tick);
   glUniform4f(glGetUniformLocation(p, "gsWeather"), weather[0], weather[1], weather[2], weather[3]);
   GLint uMode = glGetUniformLocation(p, "gsMode");
   glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT);
   if (nCells > 0) {
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssbo(cells, (long)nCells * 16));
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ssbo(prims, (long)nPrims * 48));
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, ssbo(index, (long)nIndex * 4));
      glUniform1i(uMode, 0);
      glDispatchCompute(nCells, 1, 1);
      if (relax) { glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT); glUniform1i(uMode, 2); glDispatchCompute(nCells, 1, 1); }
      glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
   }
   if (nDecay > 0) {
      glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ssbo(decay, (long)nDecay * 16));
      glUniform1i(uMode, 1);
      glDispatchCompute(nDecay, 1, 1);
      if (relax) { glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT); glUniform1i(uMode, 2); glDispatchCompute(nDecay, 1, 1); }
   }
   glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_UPDATE_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);
   // the persistence's read-back: into a pack buffer, a fence, polled with a zero timeout
   GLuint pbo; glGenBuffers(1, &pbo);
   glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
   long bytes = (long)rw * rh * 4;
   // as GroundState: immutable client storage, mapped persistent + coherent for reading, read after the fence (no glGetBufferSubData)
   GLbitfield fl = GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
   glBufferStorage(GL_PIXEL_PACK_BUFFER, bytes, NULL, fl | GL_CLIENT_STORAGE_BIT);
   unsigned char *map = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, bytes, fl);
   if (!map) { fprintf(stderr, "map failed\n"); return 1; }
   glGetTextureSubImage(tex, 0, rx, ry, 0, rw, rh, 1, GL_RGBA, GL_UNSIGNED_BYTE, (GLsizei)bytes, (void *)0);
   glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
   GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
   glFlush();
   int polls = 0;
   for (;;) {
      GLenum r = glClientWaitSync(fence, 0, 0);
      polls++;
      if (r == GL_ALREADY_SIGNALED || r == GL_CONDITION_SATISFIED) break;
      if (r == GL_WAIT_FAILED) { fprintf(stderr, "fence failed\n"); return 1; }
   }
   glDeleteSync(fence);
   unsigned char *px = malloc(bytes);
   memcpy(px, map, bytes);
   GLenum e = glGetError();
   if (e != GL_NO_ERROR) { fprintf(stderr, "gl error 0x%x\n", e); return 1; }
   FILE *of = fopen(out, "wb");
   fwrite(px, 1, bytes, of);
   fclose(of);
   printf("ran ok: %d cells, %d prims, %d decay cells, read back %dx%d after %d polls\n", nCells, nPrims, nDecay, rw, rh, polls);
   return 0;
}
