"""Headless compatibility-profile EGL context matching PZ's fixed-function GL state.

ModernGL's standalone EGL backend requests a core profile. PZ materials retain
compatibility GLSL, and the renderer uses attribute stacks, so testing them in a
core context would test a different API contract. No window or game is created.
"""

import ctypes as ct
from ctypes.util import find_library


class CompatibilityContext:
    def __init__(self):
        self.egl = ct.CDLL(find_library("EGL"))
        self.display = self.surface = self.context = None
        signatures = {
            "eglGetPlatformDisplay": (
                ct.c_void_p,
                [ct.c_uint, ct.c_void_p, ct.c_void_p],
            ),
            "eglInitialize": (ct.c_uint, [ct.c_void_p, ct.c_void_p, ct.c_void_p]),
            "eglBindAPI": (ct.c_uint, [ct.c_uint]),
            "eglChooseConfig": (
                ct.c_uint,
                [ct.c_void_p, ct.c_void_p, ct.c_void_p, ct.c_int, ct.c_void_p],
            ),
            "eglCreateContext": (
                ct.c_void_p,
                [ct.c_void_p, ct.c_void_p, ct.c_void_p, ct.c_void_p],
            ),
            "eglCreatePbufferSurface": (
                ct.c_void_p,
                [ct.c_void_p, ct.c_void_p, ct.c_void_p],
            ),
            "eglMakeCurrent": (
                ct.c_uint,
                [ct.c_void_p, ct.c_void_p, ct.c_void_p, ct.c_void_p],
            ),
            "eglDestroySurface": (ct.c_uint, [ct.c_void_p, ct.c_void_p]),
            "eglDestroyContext": (ct.c_uint, [ct.c_void_p, ct.c_void_p]),
            "eglTerminate": (ct.c_uint, [ct.c_void_p]),
            "eglGetError": (ct.c_uint, []),
        }
        for name, (result, arguments) in signatures.items():
            function = getattr(self.egl, name)
            function.restype = result
            function.argtypes = arguments
        try:
            # EGL_PLATFORM_SURFACELESS_MESA, EGL_OPENGL_API.
            self.display = self.egl.eglGetPlatformDisplay(0x31DD, None, None)
            self.check(self.display, "get surfaceless display")
            self.check(self.egl.eglInitialize(self.display, None, None), "initialize")
            self.check(self.egl.eglBindAPI(0x30A2), "bind OpenGL")
            # Pbuffer, OpenGL rendering, RGBA8.
            attributes = self.attributes(
                0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8
            )
            config, count = ct.c_void_p(), ct.c_int()
            self.check(
                self.egl.eglChooseConfig(
                    self.display, attributes, ct.byref(config), 1, ct.byref(count)
                ),
                "choose config",
            )
            self.check(count.value, "find pbuffer config")
            # EGL_CONTEXT_MAJOR_VERSION, MINOR_VERSION, OPENGL_PROFILE_MASK_KHR.
            context_attributes = self.attributes(0x3098, 4, 0x30FB, 3, 0x30FD, 2)
            self.context = self.egl.eglCreateContext(
                self.display, config, None, context_attributes
            )
            self.check(self.context, "create OpenGL 4.3 compatibility context")
            self.surface = self.egl.eglCreatePbufferSurface(
                self.display, config, self.attributes(0x3057, 1, 0x3056, 1)
            )
            self.check(self.surface, "create pbuffer")
            self.check(
                self.egl.eglMakeCurrent(
                    self.display, self.surface, self.surface, self.context
                ),
                "make current",
            )
        except Exception:
            self.close()
            raise

    @staticmethod
    def attributes(*values):
        return (ct.c_int * (len(values) + 1))(*values, 0x3038)

    def check(self, result, operation):
        if not result:
            raise RuntimeError(
                f"EGL {operation} failed: 0x{self.egl.eglGetError():04x}"
            )

    def close(self):
        if self.display:
            self.egl.eglMakeCurrent(self.display, None, None, None)
            if self.surface:
                self.egl.eglDestroySurface(self.display, self.surface)
            if self.context:
                self.egl.eglDestroyContext(self.display, self.context)
            self.egl.eglTerminate(self.display)
        self.display = self.surface = self.context = None
