// Runs one of voxel lighting's compute programs headless on the real driver against synthetic textures and prints what
// it wrote: a debugger for the traces (pzopt.VoxelLight) without the game.
//   cc -O2 -o /tmp/vxlrun tools/voxel/vxlrun.c -lEGL -lGL
//   /tmp/vxlrun prog.comp N LZ dispatchX dispatchY dispatchZ [name=v1,v2,v3,v4 | name=i:v1,v2,v3,v4 | name=1:v ...]
// Textures: occ (R32UI 256x256x16, zero; set words with occ=x,y,lz,value args) on unit 49, occ4 (R8UI 64x64x16, zero) on
// unit 51, uA (RGBA16F 3D N*2 x N*2 x LZ*2, zero) on unit 44; images: binding 0 = RGBA16F 3D N x N x LZ (zero, printed
// back as a slice: level 0..LZ-1 means of each channel and the value at the region's first cell), binding 1 = R8UI 2D array.
// VXLRUN_OUT=<file> also writes binding 0 whole (float32 RGBA, x fastest).
#include <EGL/egl.h>
#include <EGL/eglext.h>
#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *slurp(const char *p) {
   FILE *f = fopen(p, "rb");
   if (!f) { perror(p); exit(2); }
   fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
   char *b = malloc(n + 1); fread(b, 1, n, f); b[n] = 0; fclose(f);
   return b;
}

static GLuint tex3d(GLenum fmt, int x, int y, int z, int unit) {
   GLuint t; glGenTextures(1, &t);
   glActiveTexture(GL_TEXTURE0 + unit);
   glBindTexture(GL_TEXTURE_3D, t);
   glTexStorage3D(GL_TEXTURE_3D, 1, fmt, x, y, z);
   glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   return t;
}

int main(int argc, char **argv) {
   if (argc < 7) { fprintf(stderr, "usage: vxlrun prog.comp N LZ dx dy dz [uniforms...]\n"); return 2; }
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   eglInitialize(d, NULL, NULL);
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 3, EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (!eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   const char *src = slurp(argv[1]);
   int N = atoi(argv[2]), LZ = atoi(argv[3]);
   GLuint sh = glCreateShader(GL_COMPUTE_SHADER);
   glShaderSource(sh, 1, &src, NULL);
   glCompileShader(sh);
   GLint ok; char log[16384];
   glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
   if (!ok) { glGetShaderInfoLog(sh, sizeof log, NULL, log); printf("compile FAILED\n%s", log); return 1; }
   GLuint p = glCreateProgram();
   glAttachShader(p, sh);
   glLinkProgram(p);
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   if (!ok) { glGetProgramInfoLog(p, sizeof log, NULL, log); printf("link FAILED\n%s", log); return 1; }
   glUseProgram(p);
   GLuint occ = tex3d(GL_R32UI, 256, 256, 16, 49);
   unsigned *zero = calloc(256 * 256 * 16, 4);
   glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 256, 256, 16, GL_RED_INTEGER, GL_UNSIGNED_INT, zero);
   tex3d(GL_R8UI, 64, 64, 16, 51);
   glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 64, 64, 16, GL_RED_INTEGER, GL_UNSIGNED_BYTE, zero);
   tex3d(GL_RGBA16F, N * 2, N * 2, LZ * 2, 44);
   float *fz = calloc((size_t)N * 2 * N * 2 * LZ * 2 * 4, 4);
   glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, N * 2, N * 2, LZ * 2, GL_RGBA, GL_FLOAT, fz);
   GLuint out = tex3d(GL_RGBA16F, N, N, LZ, 53);
   glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, N, N, LZ, GL_RGBA, GL_FLOAT, fz);
   glBindImageTexture(0, out, 0, GL_TRUE, 0, GL_READ_WRITE, GL_RGBA16F);
   GLuint e; glGenTextures(1, &e);
   glActiveTexture(GL_TEXTURE0 + 54);
   glBindTexture(GL_TEXTURE_2D_ARRAY, e);
   glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1, GL_R8UI, N, N, LZ);
   glBindImageTexture(1, e, 0, GL_TRUE, 0, GL_READ_WRITE, GL_R8UI);
   int lo;
   if ((lo = glGetUniformLocation(p, "occ")) >= 0) glUniform1i(lo, 49);
   if ((lo = glGetUniformLocation(p, "occ4")) >= 0) glUniform1i(lo, 51);
   if ((lo = glGetUniformLocation(p, "uA")) >= 0) glUniform1i(lo, 44);
   for (int i = 7; i < argc; i++) {
      char name[64]; const char *eq = strchr(argv[i], '=');
      if (!eq) continue;
      snprintf(name, sizeof name, "%.*s", (int)(eq - argv[i]), argv[i]);
      const char *v = eq + 1;
      if (strcmp(name, "occfile") == 0 || strcmp(name, "occ4file") == 0) { // a dumped occupancy (devVoxelLightDumpAt's -occ.bin / -occ4.bin)
         int big = strcmp(name, "occfile") == 0;
         char *raw = slurp(v);
         glActiveTexture(GL_TEXTURE0 + (big ? 49 : 51));
         if (big) glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 256, 256, 16, GL_RED_INTEGER, GL_UNSIGNED_INT, raw);
         else glTexSubImage3D(GL_TEXTURE_3D, 0, 0, 0, 0, 64, 64, 16, GL_RED_INTEGER, GL_UNSIGNED_BYTE, raw);
         continue;
      }
      if (strcmp(name, "occ") == 0) { // occ=x,y,lz,value
         int ox, oy, oz; unsigned val; sscanf(v, "%d,%d,%d,%u", &ox, &oy, &oz, &val);
         glActiveTexture(GL_TEXTURE0 + 49);
         glTexSubImage3D(GL_TEXTURE_3D, 0, ox, oy, oz, 1, 1, 1, GL_RED_INTEGER, GL_UNSIGNED_INT, &val);
         continue;
      }
      lo = glGetUniformLocation(p, name);
      if (lo < 0) { printf("(uniform %s not active)\n", name); continue; }
      if (v[0] == 'i' && v[1] == ':') { int a[4] = {0}; sscanf(v + 2, "%d,%d,%d,%d", &a[0], &a[1], &a[2], &a[3]); glUniform4i(lo, a[0], a[1], a[2], a[3]); }
      else if (v[0] == '1' && v[1] == ':') { if (strchr(v + 2, '.')) glUniform1f(lo, atof(v + 2)); else glUniform1i(lo, atoi(v + 2)); }
      else { float a[4] = {0}; sscanf(v, "%f,%f,%f,%f", &a[0], &a[1], &a[2], &a[3]); glUniform4f(lo, a[0], a[1], a[2], a[3]); }
   }
   glDispatchCompute(atoi(argv[4]), atoi(argv[5]), atoi(argv[6]));
   glMemoryBarrier(GL_ALL_BARRIER_BITS);
   float *res = calloc((size_t)N * N * LZ * 4, 4);
   glActiveTexture(GL_TEXTURE0 + 53);
   glGetTexImage(GL_TEXTURE_3D, 0, GL_RGBA, GL_FLOAT, res);
   const char *raw = getenv("VXLRUN_OUT"); // the whole output image as float32 RGBA (x fastest)
   if (raw) { FILE *f = fopen(raw, "wb"); fwrite(res, 4, (size_t)N * N * LZ * 4, f); fclose(f); }
   for (int z = 0; z < LZ; z++) {
      double m[4] = {0}; int cnt = 0;
      for (int i = 0; i < N * N; i++) { float *t = res + ((size_t)z * N * N + i) * 4; if (t[0] || t[1] || t[2] || t[3]) cnt++; for (int k = 0; k < 4; k++) m[k] += t[k]; }
      printf("level index %d: written %d, means %.3f %.3f %.3f %.3f\n", z, cnt, m[0] / (N * N), m[1] / (N * N), m[2] / (N * N), m[3] / (N * N));
   }
   return 0;
}
