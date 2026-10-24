// Runs one GLSL fragment shader over a W x H grid headless (EGL, no window) and prints what it wrote: the GPU side of
// tests/pzopt/GroundHashTest (pzopt.GroundHash's GLSL against its Java twin, bit for bit). The fragment shader declares
// `out uvec4 pzgOut;` and writes four words per pixel; this prints "x y w0 w1 w2 w3" (hex) per pixel, after a line
// "GL <renderer>". A full-screen triangle from gl_VertexID; the vertex shader's version follows the fragment shader's.
//   cc -O2 -o build/glslrun tools/ground/glslrun.c -lEGL -lGL
//   build/glslrun frag.glsl W H [core]      core: a 3.3 core context (else 3.3 compatibility)
// Mesa: __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
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
   char *b = malloc(n + 1); if (fread(b, 1, n, f) != (size_t)n) { perror(p); exit(2); } b[n] = 0; fclose(f);
   return b;
}

static GLuint unit(GLenum type, const char *src, const char *what) {
   GLuint s = glCreateShader(type);
   glShaderSource(s, 1, &src, NULL);
   glCompileShader(s);
   GLint ok; char log[8192];
   glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   glGetShaderInfoLog(s, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "%s: compile FAILED\n%s\n", what, log); exit(1); }
   return s;
}

int main(int argc, char **argv) {
   if (argc < 4) { fprintf(stderr, "usage: glslrun frag W H [core]\n"); return 2; }
   int w = atoi(argv[2]), h = atoi(argv[3]);
   int core = argc > 4 && strcmp(argv[4], "core") == 0;
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   if (!eglInitialize(d, NULL, NULL)) { fprintf(stderr, "no EGL display\n"); return 1; }
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 3,
                  EGL_CONTEXT_OPENGL_PROFILE_MASK, core ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (!eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   printf("GL %s\n", glGetString(GL_RENDERER));
   char *frag = slurp(argv[1]);
   char ver[64] = "#version 130";
   const char *vp = strstr(frag, "#version");
   if (vp) { int i = 0; while (vp[i] && vp[i] != '\n' && i < 63) { ver[i] = vp[i]; i++; } ver[i] = 0; }
   char vert[512];
   snprintf(vert, sizeof vert, "%s\nvoid main() { vec2 p = vec2(float((gl_VertexID & 1) * 4 - 1), float((gl_VertexID & 2) * 2 - 1)); gl_Position = vec4(p, 0.0, 1.0); }\n", ver);
   GLuint p = glCreateProgram();
   glAttachShader(p, unit(GL_VERTEX_SHADER, vert, "vertex"));
   glAttachShader(p, unit(GL_FRAGMENT_SHADER, frag, argv[1]));
   glBindFragDataLocation(p, 0, "pzgOut");
   glLinkProgram(p);
   GLint ok; char log[8192];
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   if (!ok) { fprintf(stderr, "link FAILED\n%s\n", log); return 1; }
   GLuint tex, fbo, vao;
   glGenTextures(1, &tex);
   glBindTexture(GL_TEXTURE_2D, tex);
   glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32UI, w, h, 0, GL_RGBA_INTEGER, GL_UNSIGNED_INT, NULL);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
   glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
   glGenFramebuffers(1, &fbo);
   glBindFramebuffer(GL_FRAMEBUFFER, fbo);
   glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
   if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { fprintf(stderr, "fbo incomplete\n"); return 1; }
   glGenVertexArrays(1, &vao);
   glBindVertexArray(vao);
   glViewport(0, 0, w, h);
   glUseProgram(p);
   glDrawArrays(GL_TRIANGLES, 0, 3);
   unsigned *px = malloc((size_t)w * h * 16);
   glReadPixels(0, 0, w, h, GL_RGBA_INTEGER, GL_UNSIGNED_INT, px);
   GLenum e = glGetError();
   if (e != GL_NO_ERROR) { fprintf(stderr, "gl error 0x%x\n", e); return 1; }
   for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
         unsigned *q = px + ((size_t)y * w + x) * 4;
         printf("%d %d %08x %08x %08x %08x\n", x, y, q[0], q[1], q[2], q[3]);
      }
   }
   return 0;
}
