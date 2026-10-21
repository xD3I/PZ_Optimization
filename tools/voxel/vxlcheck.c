// Compiles and links GLSL with the real driver (headless EGL, a 4.3 compatibility context): voxel lighting's compute
// programs and the chunk composite variants it patches, without launching the game.
//   cc -O2 -o /tmp/vxlcheck tools/voxel/vxlcheck.c -lEGL -lGL
//   /tmp/vxlcheck --cs prog.comp          a compute program
//   /tmp/vxlcheck vert.glsl frag.glsl     a vertex + fragment program (validated like the game's ShaderProgram.compile)
// Mesa instead of the default vendor: __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json
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

static int failed;

static GLuint unit(GLenum type, const char *path) {
   const char *src = slurp(path);
   GLuint s = glCreateShader(type);
   glShaderSource(s, 1, &src, NULL);
   glCompileShader(s);
   GLint ok; char log[16384];
   glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
   glGetShaderInfoLog(s, sizeof log, NULL, log);
   printf("%s: compile %s\n%s", path, ok ? "ok" : "FAILED", log);
   if (!ok) failed = 1;
   return s;
}

int main(int argc, char **argv) {
   EGLDisplay d = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
   if (d == EGL_NO_DISPLAY) d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
   eglInitialize(d, NULL, NULL);
   eglBindAPI(EGL_OPENGL_API);
   EGLint ca[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
   EGLConfig cfg; EGLint n;
   eglChooseConfig(d, ca, &cfg, 1, &n);
   EGLint cx[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 3,
                  EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT, EGL_NONE};
   EGLContext c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cx);
   if (c == EGL_NO_CONTEXT) {
      EGLint cc[] = {EGL_CONTEXT_MAJOR_VERSION, 4, EGL_CONTEXT_MINOR_VERSION, 3, EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT, EGL_NONE};
      c = eglCreateContext(d, cfg, EGL_NO_CONTEXT, cc);
   }
   EGLint pa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
   EGLSurface s = eglCreatePbufferSurface(d, cfg, pa);
   if (!eglMakeCurrent(d, s, s, c)) { fprintf(stderr, "no context\n"); return 1; }
   printf("GL %s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
   GLuint p = glCreateProgram();
   int draw = argc > 1 && strcmp(argv[1], "--draw") == 0; // draw a triangle with the program: radeonsi compiles (and with AMD_DEBUG=ps,stats prints its registers)
   if (draw) { argv++; argc--; }
   int cs = argc > 2 && strcmp(argv[1], "--cs") == 0;
   if (cs) {
      glAttachShader(p, unit(GL_COMPUTE_SHADER, argv[2]));
   } else {
      glAttachShader(p, unit(GL_VERTEX_SHADER, argv[1]));
      for (int i = 2; i < argc; i++) glAttachShader(p, unit(GL_FRAGMENT_SHADER, argv[i]));
   }
   glLinkProgram(p);
   GLint ok; char log[16384];
   glGetProgramiv(p, GL_LINK_STATUS, &ok);
   glGetProgramInfoLog(p, sizeof log, NULL, log);
   printf("link %s\n%s", ok ? "ok" : "FAILED", log);
   if (!ok || failed) return 1;
   if (!cs) {
      glValidateProgram(p); // the game validates with every sampler still on unit 0 (two sampler types on one unit fail on Mesa)
      glGetProgramiv(p, GL_VALIDATE_STATUS, &ok);
      glGetProgramInfoLog(p, sizeof log, NULL, log);
      printf("validate %s\n%s", ok ? "ok" : "FAILED", log);
      if (draw) {
         GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
         glUseProgram(p);
         glDrawArrays(GL_TRIANGLES, 0, 3);
         glFinish();
      }
   }
   return ok ? 0 : 1;
}
