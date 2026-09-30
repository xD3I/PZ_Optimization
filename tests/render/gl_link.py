"""Link multiple GLSL compilation units per stage, as PZ's ShaderProgram does."""

import ctypes as ct
from ctypes.util import find_library


def link_units(vertex_sources, fragment_sources):
    """Require every unit to compile and the complete program to link; owns no GL context."""
    gl = ct.CDLL(find_library("GL"))
    signatures = {
        "glCreateShader": (ct.c_uint, [ct.c_uint]),
        "glShaderSource": (
            None,
            [ct.c_uint, ct.c_int, ct.POINTER(ct.c_char_p), ct.c_void_p],
        ),
        "glCompileShader": (None, [ct.c_uint]),
        "glGetShaderiv": (None, [ct.c_uint, ct.c_uint, ct.POINTER(ct.c_int)]),
        "glGetShaderInfoLog": (None, [ct.c_uint, ct.c_int, ct.c_void_p, ct.c_void_p]),
        "glCreateProgram": (ct.c_uint, []),
        "glAttachShader": (None, [ct.c_uint, ct.c_uint]),
        "glLinkProgram": (None, [ct.c_uint]),
        "glGetProgramiv": (None, [ct.c_uint, ct.c_uint, ct.POINTER(ct.c_int)]),
        "glGetProgramInfoLog": (None, [ct.c_uint, ct.c_int, ct.c_void_p, ct.c_void_p]),
        "glDeleteProgram": (None, [ct.c_uint]),
        "glDeleteShader": (None, [ct.c_uint]),
    }
    for name, (result, arguments) in signatures.items():
        function = getattr(gl, name)
        function.restype = result
        function.argtypes = arguments
    shaders = []
    program = gl.glCreateProgram()
    try:
        for stage, sources in ((0x8B31, vertex_sources), (0x8B30, fragment_sources)):
            for source in sources:
                shader = gl.glCreateShader(stage)
                shaders.append(shader)
                strings = (ct.c_char_p * 1)(source.encode())
                gl.glShaderSource(shader, 1, strings, None)
                gl.glCompileShader(shader)
                status = ct.c_int()
                gl.glGetShaderiv(shader, 0x8B81, ct.byref(status))
                if not status.value:
                    log = ct.create_string_buffer(16384)
                    gl.glGetShaderInfoLog(shader, len(log), None, log)
                    raise AssertionError(log.value.decode())
                gl.glAttachShader(program, shader)
        gl.glLinkProgram(program)
        status = ct.c_int()
        gl.glGetProgramiv(program, 0x8B82, ct.byref(status))
        if not status.value:
            log = ct.create_string_buffer(16384)
            gl.glGetProgramInfoLog(program, len(log), None, log)
            raise AssertionError(log.value.decode())
    finally:
        gl.glDeleteProgram(program)
        for shader in shaders:
            gl.glDeleteShader(shader)
