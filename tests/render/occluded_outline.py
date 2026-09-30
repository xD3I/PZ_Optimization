#!/usr/bin/env python3
"""Exercise the shipped outline GLSL on headless EGL, without running the game.

Install moderngl and numpy in a virtual environment, then run this file from any
working directory. These tests supply synthetic captures; they do not validate
PZ's material classification, depth capture, or character visibility integration.
"""

import os
import re
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

import moderngl
import numpy as np
from egl_compat import CompatibilityContext
from gl_link import link_units

SHADERS = Path(__file__).resolve().parents[2] / "src/media/shaders"


def reference_mask(silhouette, scene, radius=1, tolerance=0.0001):
    """Independent CPU oracle: erode full coverage, then intersect with occlusion."""
    depth, alpha = silhouette[:, :, 0], silhouette[:, :, 1]
    full = (alpha >= 0.5) & np.isfinite(alpha) & (alpha <= 1)
    padded = np.pad(full, radius)
    interior = full.copy()
    height, width = full.shape
    for y in range(-radius, radius + 1):
        for x in range(-radius, radius + 1):
            if x * x + y * y <= radius * radius:
                interior &= padded[
                    radius + y : radius + y + height,
                    radius + x : radius + x + width,
                ]
    hidden = np.isfinite(scene) & (scene >= 0) & (scene <= 1)
    hidden &= np.isfinite(depth) & (depth >= 0) & (depth <= 1)
    hidden &= depth - scene > tolerance
    return (full & ~interior & hidden).astype(np.float32)


class OccludedOutlineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.egl = CompatibilityContext()
        cls.addClassCleanup(cls.egl.close)
        cls.ctx = moderngl.create_context(require=430)
        cls.java_dir = tempfile.TemporaryDirectory(prefix="pz-outline-java-")
        cls.addClassCleanup(cls.java_dir.cleanup)
        root = SHADERS.parents[2]
        subprocess.run(
            [
                "javac",
                "--release",
                "25",
                "-d",
                cls.java_dir.name,
                str(root / "src/pzopt/pzopt/OccludedOutlineShaders.java"),
                str(root / "src/pzopt/pzopt/OutlinePlantShaders.java"),
                str(root / "tests/pzopt/OccludedOutlineShadersTest.java"),
            ],
            check=True,
        )
        vertex = (SHADERS / "pzopt_occludedOutline.vert").read_text()
        cls.program = cls.ctx.program(
            vertex_shader=vertex,
            fragment_shader=(SHADERS / "pzopt_occludedOutline.frag").read_text(),
        )
        cls.vao = cls.ctx.vertex_array(cls.program, [])
        cls.composite = cls.ctx.program(
            vertex_shader=vertex,
            fragment_shader=(
                SHADERS / "pzopt_occludedOutlineComposite.frag"
            ).read_text(),
        )
        cls.composite_vao = cls.ctx.vertex_array(cls.composite, [])
        # Synthetic geometry only: a real depth attachment proves that skipping
        # glass during capture preserves opaque geometry behind it in either order.
        cls.depth_program = cls.ctx.program(
            vertex_shader="""#version 330 core
                uniform float surfaceDepth;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, surfaceDepth * 2.0 - 1.0, 1.0);
                }
            """,
            fragment_shader="""#version 330 core
                uniform bool glass;
                void main() { if (glass) discard; }
            """,
        )
        cls.depth_vao = cls.ctx.vertex_array(cls.depth_program, [])
        print(f"GPU: {cls.ctx.info['GL_RENDERER']}; OpenGL {cls.ctx.version_code}")

    @classmethod
    def tearDownClass(cls):
        for resource in (
            cls.depth_vao,
            cls.depth_program,
            cls.composite_vao,
            cls.composite,
            cls.vao,
            cls.program,
            cls.ctx,
        ):
            resource.release()

    def setUp(self):
        self.resources = []
        self.ctx.enable_only(moderngl.NOTHING)
        self.ctx.scissor = None
        self.silhouette = np.zeros((24, 24, 2), dtype=np.float32)
        self.silhouette[3:21, 7:17] = (0.6, 1)
        self.scene = np.full((24, 24), 0.9, dtype=np.float32)
        self.scene[:12] = 0.3

    def tearDown(self):
        self.ctx.scissor = None
        self.ctx.enable_only(moderngl.NOTHING)
        for resource in reversed(self.resources):
            resource.release()

    def texture(self, image, dtype="f4"):
        height, width = image.shape[:2]
        components = image.shape[2] if image.ndim == 3 else 1
        texture = self.ctx.texture(
            (width, height),
            components,
            image.astype(np.uint32 if dtype == "u4" else np.float32).tobytes(),
            dtype=dtype,
        )
        texture.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.resources.append(texture)
        return texture

    def target(self, width, height, components=1):
        image = np.zeros((height, width, components), dtype=np.float32)
        texture = self.texture(image)
        fbo = self.ctx.framebuffer(color_attachments=[texture])
        self.resources.append(fbo)
        fbo.use()
        fbo.clear()
        return fbo

    def capture_target(self, width, height):
        colour = self.ctx.texture((width, height), 2, dtype="f1")
        depth = self.ctx.depth_texture((width, height))
        depth.compare_func = ""
        fbo = self.ctx.framebuffer(color_attachments=[colour], depth_attachment=depth)
        self.resources.extend([colour, depth, fbo])
        fbo.use()
        fbo.clear(depth=1)
        self.ctx.enable_only(moderngl.DEPTH_TEST)
        self.ctx.depth_func = "<"
        return fbo, depth

    def read(self, fbo, components=1):
        width, height = fbo.size
        image = np.frombuffer(
            fbo.read(components=components, dtype="f4"), dtype=np.float32
        )
        shape = (height, width) if components == 1 else (height, width, components)
        return image.reshape(shape)

    def draw(
        self, silhouette=None, scene=None, target=None, brightness=None, **overrides
    ):
        silhouette = self.silhouette if silhouette is None else silhouette
        scene = self.scene if scene is None else scene
        height, width = silhouette.shape[:2]
        if target is None:
            target = self.target(scene.shape[1], scene.shape[0])
        target.use()
        light = (
            np.ones((height, width), dtype=np.float32)
            if brightness is None
            else brightness
        )
        self.texture(np.stack((silhouette[:, :, 1], light), axis=-1)).use(0)
        self.texture(silhouette[:, :, 0]).use(1)
        self.texture(scene.astype(np.float32).view(np.uint32), dtype="u4").use(2)
        uniforms = {
            "silhouette": 0,
            "silhouetteDepth": 1,
            "opaqueDepth": 2,
            "silhouetteRect": (0, 0, width, height),
            "candidateOrigin": (0, 0),
            "depthOrigin": (0, 0),
            "viewport": (0, 0, *target.size),
            "eligible": True,
            "clipEdges": False,
            "candidateOpacity": 1.0,
            "radius": 1,
            "depthTolerance": 0.0001,
        }
        uniforms.update(overrides)
        for name, value in uniforms.items():
            self.program[name].value = value
        self.vao.render(mode=moderngl.TRIANGLES, vertices=3)
        return self.read(target)

    def patch_material(self, filename, source, plant=False):
        result = subprocess.run(
            [
                "java",
                "-cp",
                self.java_dir.name,
                "pzopt.OccludedOutlineShadersTest",
                "--plant" if plant else "--patch",
                filename,
            ],
            input=source,
            text=True,
            capture_output=True,
            check=True,
        )
        return result.stdout

    def test_characters_do_not_occlude_but_scenery_behind_them_does(self):
        vertex = """#version 330 compatibility
            uniform float surfaceDepth;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, surfaceDepth * 2.0 - 1.0, 1.0);
            }
        """
        fragment = """#version 330 compatibility
            void main() { gl_FragColor = vec4(1, 0.5, 0, 1); }
        """
        scenery = self.ctx.program(
            vertex_shader=vertex,
            fragment_shader=self.patch_material("tileWithDepth.frag", fragment),
        )
        scenery_array = self.ctx.vertex_array(scenery, [])
        control = self.ctx.buffer(reserve=32)
        control.bind_to_uniform_block(8)
        self.resources.extend([scenery, scenery_array, control])
        for name in (
            "basicEffect.frag",
            "basicEffect_instanced.frag",
            "DeadBodyAtlas.frag",
        ):
            with self.subTest(material=name):
                character = self.ctx.program(
                    vertex_shader=vertex,
                    fragment_shader=self.patch_material(name, fragment),
                )
                character_array = self.ctx.vertex_array(character, [])
                target, scene_depth = self.capture_target(8, 8)
                opaque = self.texture(
                    np.ones((8, 8), dtype=np.float32).view(np.uint32), dtype="u4"
                )
                opaque.bind_to_image(7)
                self.resources.extend([character, character_array])
                # 3D draws pause capture. Atlas sprites must be excluded even when their
                # depth-tested VBO enables world capture. Neither alters normal rendering.
                enabled = int(name == "DeadBodyAtlas.frag")
                control.write(
                    np.array([0, 0, 8, 8, enabled, 0, 0, 0], dtype=np.int32).tobytes()
                )
                character["surfaceDepth"].value = 0.2
                character_array.render(mode=moderngl.TRIANGLES, vertices=3)
                self.ctx.memory_barrier()
                np.testing.assert_allclose(
                    np.frombuffer(opaque.read(), dtype=np.uint32).view(np.float32), 1
                )
                np.testing.assert_allclose(
                    np.frombuffer(scene_depth.read(), dtype=np.float32), 0.2, atol=1e-6
                )
                np.testing.assert_allclose(
                    self.read(target, 4)[:, :, 0], 1.0, atol=1e-6
                )
                # Resume capture: nearer character depth must not hide actual scenery
                # from the independent occlusion image, or suppress the next draw.
                control.write(
                    np.array([0, 0, 8, 8, 1, 0, 0, 0], dtype=np.int32).tobytes()
                )
                scenery["surfaceDepth"].value = 0.4
                scenery_array.render(mode=moderngl.TRIANGLES, vertices=3)
                self.ctx.memory_barrier()
                np.testing.assert_allclose(
                    np.frombuffer(opaque.read(), dtype=np.uint32).view(np.float32),
                    0.4,
                    atol=1e-6,
                )
                np.testing.assert_allclose(
                    np.frombuffer(scene_depth.read(), dtype=np.float32), 0.2, atol=1e-6
                )

    def test_depth_rejected_body_still_contributes_behind_glass(self):
        vertex = """#version 330 compatibility
            uniform float surfaceDepth;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, surfaceDepth * 2.0 - 1.0, 1.0);
            }
        """
        for fragment_depth, mrt in (
            (False, False),
            (False, True),
            (True, False),
            (True, True),
        ):
            with self.subTest(fragment_depth=fragment_depth, mrt=mrt):
                source = """#version 330 compatibility
                    uniform float surfaceDepth;
                    uniform float materialOpacity;
                    uniform bool glass;
                    void main() {
                        float windowAlpha = glass ? 1.0 : 0.0;
                        gl_FragColor = vec4(1.0, 1.0, 1.0, materialOpacity);
                        DEPTH_ASSIGNMENT
                    }
                """.replace(
                    "DEPTH_ASSIGNMENT",
                    "gl_FragDepth = surfaceDepth;" if fragment_depth else "",
                )
                if mrt:
                    source = source.replace("gl_FragColor", "gl_FragData[0]")
                    source = source.replace(
                        "float windowAlpha",
                        "gl_FragData[1] = vec4(0.25); float windowAlpha",
                    )
                program = self.ctx.program(
                    vertex_shader=vertex,
                    fragment_shader=self.patch_material("vehicle_multiuv.frag", source),
                )
                array = self.ctx.vertex_array(program, [])
                scene_depth = self.ctx.depth_texture((8, 8))
                colour = self.texture(np.zeros((8, 8, 4), dtype=np.float32))
                fbo = self.ctx.framebuffer(
                    color_attachments=[colour], depth_attachment=scene_depth
                )
                opaque = self.texture(
                    np.full((8, 8), 1.0, dtype=np.float32).view(np.uint32), dtype="u4"
                )
                control = self.ctx.buffer(
                    np.array([0, 0, 8, 8, 1, 0, 0, 0], dtype=np.int32).tobytes()
                )
                self.resources.extend([program, array, scene_depth, fbo, control])
                control.bind_to_uniform_block(8)
                opaque.bind_to_image(7, read=True, write=True)
                fbo.use()
                fbo.clear(depth=1)
                self.ctx.enable_only(moderngl.DEPTH_TEST)
                self.ctx.depth_func = "<"
                program["materialOpacity"].value = 1.0
                # Opaque-looking glass writes the NORMAL nearest depth first.
                program["glass"].value = True
                program["surfaceDepth"].value = 0.2
                array.render(mode=moderngl.TRIANGLES, vertices=3)
                program["glass"].value = False
                program["surfaceDepth"].value = 0.4
                array.render(mode=moderngl.TRIANGLES, vertices=3)
                self.ctx.memory_barrier()
                stored = np.frombuffer(opaque.read(), dtype=np.uint32).view(np.float32)
                np.testing.assert_allclose(stored, 0.4, atol=1e-6)
                scene_depth.compare_func = ""
                np.testing.assert_allclose(
                    np.frombuffer(scene_depth.read(), dtype=np.float32), 0.2, atol=1e-6
                )
                # A paused/offscreen scope cannot contaminate the independent capture.
                control.write(
                    np.array([0, 0, 8, 8, 0, 0, 0, 0], dtype=np.int32).tobytes()
                )
                program["surfaceDepth"].value = 0.1
                array.render(mode=moderngl.TRIANGLES, vertices=3)
                self.ctx.memory_barrier()
                np.testing.assert_allclose(
                    np.frombuffer(opaque.read(), dtype=np.uint32).view(np.float32),
                    0.4,
                    atol=1e-6,
                )
                self.ctx.enable_only(moderngl.NOTHING)

    def test_current_game_materials_link(self):
        game = os.environ.get("PZ_DIR")
        if not game:
            self.skipTest(
                "Set PZ_DIR to validate the installed game's material shader families"
            )
        shaders = Path(game) / "media/shaders"

        def resolve_include(path, name):
            for parent in (path.parent, shaders):
                wanted = parent / (name + ".glsl")
                if wanted.exists():
                    return wanted
                if wanted.parent.is_dir():
                    for candidate in wanted.parent.iterdir():
                        if candidate.name.lower() == wanted.name.lower():
                            return candidate
            raise FileNotFoundError(f"Shader include {name} from {path}")

        def expand(path, library=False, seen=None):
            seen = set() if seen is None else seen
            if path in seen:
                return ""
            seen.add(path)
            text = path.read_text(encoding="utf-8-sig")

            def include(match):
                wanted = resolve_include(path, match[1])
                header = wanted.with_suffix(".h")
                declarations = expand(header, True, seen) if header.exists() else ""
                return declarations + "\n" + expand(wanted, True, seen)

            text = re.sub(r'#include\s+"([^"]+)"', include, text)
            if library:
                text = re.sub(r"(?m)^\s*#version[^\n]*", "", text)
            return text

        def separate_units(path):
            units = {}

            def compile_unit(unit):
                if unit in units:
                    return
                units[unit] = ""
                text = unit.read_text(encoding="utf-8-sig")

                def include(match):
                    wanted = resolve_include(unit, match[1])
                    compile_unit(wanted)
                    return wanted.with_suffix(".h").read_text(encoding="utf-8-sig")

                units[unit] = re.sub(r'#include\s+"([^"]+)"', include, text)

            compile_unit(path)
            # Macro-only libraries have no linkable declarations; their headers are already
            # expanded into the caller. Mesa rejects an otherwise empty translation unit.
            for unit, text in list(units.items()):
                body = re.sub(r"(?s)/\*.*?\*/|//[^\n]*", "", text)
                body = body.replace("\\\n", "")
                body = re.sub(r"(?m)^\s*#[^\n]*", "", body)
                if not body.strip():
                    del units[unit]
            return units

        materials = {
            name: shaders
            for name in (
                "basicEffect",
                "basicEffect_instanced",
                "vehicle",
                "vehicle_noreflect",
                "vehicle_multiuv",
                "vehicle_norandom_multiuv",
                "vehicle_multiuv_noreflect",
                "vehicle_norandom_multiuv_noreflect",
                "DeadBodyAtlas",
                "tileWithDepth",
                "opaqueWithDepth",
                "seamFix2",
                "CutawayAttached",
                "vboRenderer_PositionColorUV",
                "vboRenderer_PositionColorUVDepth",
            )
        }
        damnlib = os.environ.get("PZ_DAMNLIB_SHADERS")
        if damnlib:
            materials.update(
                {
                    name: Path(damnlib)
                    for name in (
                        "damn_vehicle_shader",
                        "damn_vehicle_noreflect_shader",
                        "damn_wheel_shader",
                    )
                }
            )
        for name, directory in materials.items():
            with self.subTest(material=name):
                vertex = directory / (name + ".vert")
                if name.startswith("damn_") or not vertex.exists():
                    vertex = directory / (name + "_static.vert")
                material = directory / (name + ".frag")
                source = self.patch_material(name + ".frag", expand(material))
                if name.startswith("basicEffect"):
                    self.assertIn("pzoptSurfaceLight = lighting * vertColour;", source)
                    self.assertIn("if (pzoptOcclusionFlags.y != 0)", source)
                # Instanced skinning intentionally defines different macros in its header and
                # separate library unit; flattening those units is not a valid engine program.
                if name != "basicEffect_instanced":
                    program = self.ctx.program(
                        vertex_shader=expand(vertex), fragment_shader=source
                    )
                    if name.startswith("damn_"):
                        self.check_vehicle_depth_capture(program)
                    program.release()
                # PZ normally links include libraries as separate compilation units.
                # Flattened-source success alone would not establish that contract.
                vertex_units = separate_units(vertex)
                fragment_units = separate_units(material)
                fragment_units[material] = self.patch_material(
                    name + ".frag", fragment_units[material]
                )
                link_units(vertex_units.values(), fragment_units.values())

    def check_vehicle_depth_capture(self, program):
        """Exercise real DAMNLib shaders: opaque body/roof/wheels, glass, and fading."""
        attributes = []
        values = {
            "vertex": [[-1, -1, 0, 1], [3, -1, 0, 1], [-1, 3, 0, 1]],
            "normal": [[0, 0, 1, 0]] * 3,
            "uv": [[0.5, 0.5]] * 3,
            "uv2": [[0.5, 0.5]] * 3,
        }
        for attribute, data in values.items():
            if attribute in program:
                buffer = self.ctx.buffer(np.array(data, dtype=np.float32).tobytes())
                self.resources.append(buffer)
                attributes.append((buffer, str(len(data[0])) + "f", attribute))
        array = self.ctx.vertex_array(program, attributes)
        control = self.ctx.buffer(
            np.array([0, 0, 4, 4, 1, 0, 0, 0], dtype=np.int32).tobytes()
        )
        control.bind_to_uniform_block(8)
        self.resources.extend([array, control])
        for uniform in ("ModelViewProjection", "transform"):
            program[uniform].write(np.eye(4, dtype=np.float32).tobytes())
        program["targetDepth"].value = 0.3
        for i in range(5):
            if f"Light{i}Direction" in program:
                program[f"Light{i}Direction"].value = (0, 0, 1)
        program["AmbientColour"].value = (1, 1, 1)
        self.texture(np.ones((1, 1, 4), dtype=np.float32)).use(0)
        is_body = "TextureMask" in program
        if is_body:
            mask = self.texture(np.ones((1, 1, 4), dtype=np.float32))
            mask.use(1)
            program["TextureMask"].value = 1
        opaque = self.texture(
            np.ones((4, 4), dtype=np.float32).view(np.uint32), dtype="u4"
        )
        opaque.bind_to_image(7)
        target = self.target(4, 4, components=4)
        self.ctx.enable_only(moderngl.NOTHING)
        # DAMNLib's windowAlpha also includes black roof pixels. Roofs must still occlude.
        cases = [((1, 0, 0, 1), 1, 0.3), ((0, 0, 0, 1), 1, 0.3), ((1, 0, 0, 1), 0.5, 1)]
        if is_body:
            cases.append(((0, 0.5, 0.5, 1), 1, 1))
        for colour, alpha, expected_depth in cases:
            with self.subTest(mask=colour, alpha=alpha):
                if is_body:
                    mask.write(np.array(colour, dtype=np.float32).tobytes())
                    program["TexturePainColor"].value = (0.5, 0.5, 0.5, alpha)
                else:
                    program["Alpha"].value = alpha
                opaque.write(
                    np.ones((4, 4), dtype=np.float32).view(np.uint32).tobytes()
                )
                array.render(mode=moderngl.TRIANGLES, vertices=3)
                self.ctx.memory_barrier()
                depth = np.frombuffer(opaque.read(), dtype=np.uint32).view(np.float32)
                np.testing.assert_allclose(depth, expected_depth, atol=1e-6)
                np.testing.assert_allclose(
                    self.read(target, 4)[:, :, 3], alpha, atol=1e-6
                )

    def test_model_capture_uses_world_depth_and_texture_coverage(self):
        positions = np.array(
            [
                [-0.5, -0.5, 0, 1],
                [0.5, -0.5, 0, 1],
                [-0.5, 0.5, 0, 1],
                [0.5, 0.5, 0, 1],
            ],
            dtype=np.float32,
        )
        uv = np.array([[0, 0], [1, 0], [0, 1], [1, 1]], dtype=np.float32)
        for static in (False, True):
            with self.subTest(static=static):
                vertex = (
                    "pzopt_occludedCapture_static.vert"
                    if static
                    else "pzopt_occludedCapture.vert"
                )
                program = self.ctx.program(
                    vertex_shader=(SHADERS / vertex).read_text(),
                    fragment_shader=(
                        SHADERS / "pzopt_occludedCapture.frag"
                    ).read_text(),
                )
                buffers = [
                    self.ctx.buffer(positions.tobytes()),
                    self.ctx.buffer(uv.tobytes()),
                ]
                content = [(buffers[0], "4f", "vertex"), (buffers[1], "2f", "uv")]
                matrix = np.eye(4, dtype=np.float32)
                program["ModelViewProjection"].write(matrix.T.tobytes())
                matrix[2, 3] = 0.1
                if static:
                    program["transform"].write(matrix.T.tobytes())
                else:
                    weights = np.tile(np.array([1, 0, 0, 0], dtype=np.float32), (4, 1))
                    indices = np.zeros((4, 4), dtype=np.float32)
                    buffers.extend(
                        [
                            self.ctx.buffer(weights.tobytes()),
                            self.ctx.buffer(indices.tobytes()),
                        ]
                    )
                    content.extend(
                        [
                            (buffers[2], "4f", "boneWeights"),
                            (buffers[3], "4f", "boneIndices"),
                        ]
                    )
                    palette = np.tile(matrix.T, (60, 1, 1))
                    program["MatrixPalette"].write(palette.tobytes())
                normals = self.ctx.buffer(
                    np.tile(np.array([0, 0, 1, 0], dtype=np.float32), (4, 1)).tobytes()
                )
                buffers.append(normals)
                content.append((normals, "4f", "normal"))
                program["AmbientColour"].value = (0.25, 0.25, 0.25)
                array = self.ctx.vertex_array(program, content)
                self.resources.extend([program, *buffers, array])
                texture = self.texture(np.ones((1, 1, 4), dtype=np.float32))
                texture.use(0)
                program["Texture"].value = 0
                program["FinalScale"].value = 1
                program["targetDepth"].value = 0.7
                program["UVScale"].value = (1, 1)
                target, captured_depth = self.capture_target(16, 16)
                array.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)
                self.assertEqual(self.read(target)[8, 8], 1)
                self.assertAlmostEqual(
                    self.read(target, 2)[8, 8, 1], 0.25, delta=1 / 255
                )
                z = np.frombuffer(captured_depth.read(), dtype=np.float32).reshape(
                    16, 16
                )
                np.testing.assert_allclose(z[8, 8], 0.75, atol=1e-6)
                # Different surface normals on one body must produce different light, even on black cloth.
                normals.write(
                    np.array(
                        [[-1, 0, 1, 0], [1, 0, 1, 0], [-1, 0, 1, 0], [1, 0, 1, 0]],
                        dtype=np.float32,
                    ).tobytes()
                )
                program["Light0Direction"].value = (1, 0, 0)
                program["Light0Colour"].value = (0.7, 0.7, 0.7)
                texture.write(np.array([0, 0, 0, 1], dtype=np.float32).tobytes())
                target.clear(depth=1)
                array.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)
                light = self.read(target, 2)[:, :, 1]
                self.assertGreater(light[8, 10], light[8, 5])
                self.assertGreaterEqual(light[8, 5], 0.24)
                texture.write(np.array([1, 1, 1, 0.49], dtype=np.float32).tobytes())
                target.clear()
                array.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)
                self.assertFalse(self.read(target).any())

    def test_atlas_bake_exports_lighting_not_albedo_and_restores_normal_output(self):
        vertex = """#version 330 compatibility
            out vec3 vertColour;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
                vertColour = vec3(1);
            }
        """
        fragment = """#version 330 compatibility
            in vec3 vertColour;
            void main() {
                vec3 lighting = vec3(gl_FragCoord.x < 2.0 ? 0.1 : 0.8);
                vec4 fragCol = vec4(lighting * vec3(0.02, 0.01, 0.005), 1);
                gl_FragColor = fragCol;
            }
        """
        control = self.ctx.buffer(reserve=32)
        control.bind_to_uniform_block(8)
        self.resources.append(control)
        opaque = self.texture(
            np.full((1, 4), np.float32(1).view(np.uint32), dtype=np.uint32), dtype="u4"
        )
        opaque.bind_to_image(7)
        target = self.target(4, 1, components=4)
        light = np.array([0.1, 0.1, 0.8, 0.8], dtype=np.float32)
        for instanced in (False, True):
            # Stock instanced materials write colour directly, without declaring fragCol.
            source = fragment
            name = "basicEffect.frag"
            if instanced:
                name = "basicEffect_instanced.frag"
                source = source.replace(
                    "in vec3 vertColour;", "in vec3 vertColour; out vec4 colour;"
                )
                source = source.replace("vec4 fragCol =", "colour =").replace(
                    "gl_FragColor = fragCol;", ""
                )
            program = self.ctx.program(
                vertex_shader=vertex, fragment_shader=self.patch_material(name, source)
            )
            array = self.ctx.vertex_array(program, [])
            self.resources.extend([program, array])
            for bake in (0, 1, 0):
                with self.subTest(instanced=instanced, bake=bake):
                    control.write(
                        np.array([0, 0, 4, 1, 0, bake, 0, 0], dtype=np.int32).tobytes()
                    )
                    array.render(mode=moderngl.TRIANGLES, vertices=3)
                    expected = light[:, None] * (
                        np.ones(3) if bake else np.array([0.02, 0.01, 0.005])
                    )
                    np.testing.assert_allclose(
                        self.read(target, 4)[0, :, :3], expected, atol=1e-6
                    )

    def test_atlas_capture_and_scene_seed(self):
        java = (SHADERS.parents[2] / "src/pzopt/pzopt/OccludedOutline.java").read_text()

        def embedded(name):
            match = re.search(
                r"String\s+" + name + r'\s*=\s*"""(.*?)"""', java, re.DOTALL
            )
            self.assertIsNotNone(match, name)
            return textwrap.dedent(match[1]).strip() + "\n"

        program = self.ctx.program(
            vertex_shader=embedded("ATLAS_VERTEX"),
            fragment_shader=embedded("ATLAS_FRAGMENT"),
        )
        array = self.ctx.vertex_array(program, [])
        self.resources.extend([program, array])
        diffuse = np.ones((2, 2, 4), dtype=np.float32)
        diffuse[0, 1, 3] = 0
        self.texture(diffuse).use(0)
        depth = np.array([[0.3, 0.4], [0, 0.6]], dtype=np.float32)
        self.texture(depth).use(1)
        program["diffuse"].value = 0
        program["depth"].value = 1
        lighting = np.array(
            [[[0.1] * 3, [0.2] * 3], [[0.3] * 3, [0.8] * 3]], dtype=np.float32
        )
        self.texture(lighting).use(2)
        program["surfaceLighting"].value = 2
        program["worldLight"].value = (0.5, 0.5, 0.5)
        program["matrix"].write(np.eye(4, dtype=np.float32).tobytes())
        program["rectangle"].value = (-1, -1, 2, 2)
        program["uvRect"].value = (0, 0, 1, 1)
        program["depthRange"].value = (0.2, 0.8)
        target, captured_depth = self.capture_target(2, 2)
        array.render(mode=moderngl.TRIANGLE_STRIP, vertices=4)
        np.testing.assert_array_equal(self.read(target), [[1, 0], [0, 1]])
        np.testing.assert_allclose(
            self.read(target, 2)[:, :, 1], [[0.05, 0], [0, 0.4]], atol=1 / 255
        )
        z = np.frombuffer(captured_depth.read(), dtype=np.float32).reshape(2, 2)
        np.testing.assert_allclose(z, [[0.38, 1], [1, 0.56]], atol=1e-6)
        self.ctx.enable_only(moderngl.NOTHING)

        seed = self.ctx.program(
            vertex_shader=(SHADERS / "pzopt_occludedOutline.vert").read_text(),
            fragment_shader=embedded("SEED"),
        )
        seed_array = self.ctx.vertex_array(seed, [])
        self.resources.extend([seed, seed_array])
        scene = np.arange(64, dtype=np.float32).reshape(8, 8) / 64
        self.texture(scene).use(0)
        output = self.texture(np.zeros((4, 4), dtype=np.uint32), dtype="u4")
        fbo = self.ctx.framebuffer(color_attachments=[output])
        self.resources.append(fbo)
        fbo.use()
        seed["sceneDepth"].value = 0
        seed["sourceOrigin"].value = (2, 3)
        seed_array.render(mode=moderngl.TRIANGLES, vertices=3)
        captured = (
            np.frombuffer(output.read(), dtype=np.uint32).view(np.float32).reshape(4, 4)
        )
        np.testing.assert_array_equal(captured, scene[3:7, 2:6])

    def test_clipped_capture_has_no_outline_at_unknown_view_edge(self):
        self.silhouette[:, :, :] = (0.6, 1.0)
        self.scene.fill(0.3)
        self.assertFalse(self.draw(clipEdges=True).any())

    def test_native_character_fade_scales_the_contour(self):
        np.testing.assert_array_equal(
            self.draw(candidateOpacity=0.25),
            reference_mask(self.silhouette, self.scene) * 0.25,
        )

    def test_half_hidden_zombie_has_no_waistline(self):
        mask = self.draw()
        np.testing.assert_array_equal(mask, reference_mask(self.silhouette, self.scene))
        self.assertEqual(mask[7, 7], 1)  # Hidden left contour.
        self.assertEqual(mask[17, 7], 0)  # Visible upper contour.
        self.assertEqual(mask[11, 12], 0)  # No edge across the car's upper boundary.
        self.assertEqual(mask[:12].sum(), 26)

    def test_fully_visible_zombie_has_no_outline(self):
        self.scene.fill(0.9)
        self.assertFalse(self.draw().any())

    def test_fully_hidden_zombie_gets_only_its_contour(self):
        self.scene.fill(0.3)
        mask = self.draw()
        np.testing.assert_array_equal(mask, reference_mask(self.silhouette, self.scene))
        self.assertEqual(mask[12, 12], 0)
        self.assertEqual(mask[12, 7], 1)

    def test_visibility_loss_removes_every_pixel(self):
        self.assertFalse(self.draw(eligible=False).any())

    def test_depth_equality_and_tolerance_do_not_outline(self):
        for surface in (0.6, 0.6001, 0.59995, 1.0):
            with self.subTest(surface=surface):
                self.scene.fill(surface)
                self.assertFalse(self.draw().any())

    def test_invalid_depth_fails_closed(self):
        for value in (np.nan, np.inf, -np.inf, -0.1, 1.1):
            with self.subTest(value=value):
                self.scene.fill(value)
                self.assertFalse(self.draw().any())
        self.scene.fill(0.3)
        for value in (np.nan, np.inf, -0.1, 1.1):
            with self.subTest(zombie_depth=value):
                self.silhouette[:, :, 0] = value
                self.assertFalse(self.draw().any())

    def test_invalid_parameters_fail_closed(self):
        for uniforms in (
            {"radius": 0},
            {"radius": 5},
            {"depthTolerance": -1.0},
            {"depthTolerance": float("nan")},
            {"silhouetteRect": (-1, 0, 24, 24)},
            {"silhouetteRect": (0, 0, 25, 24)},
            {"silhouetteRect": (0, 0, 24, 0)},
            {"viewport": (0, 0, 0, 24)},
            {"depthOrigin": (100, 100)},
        ):
            with self.subTest(uniforms=uniforms):
                self.assertFalse(self.draw(**uniforms).any())

    def test_all_supported_widths_match_erosion_reference(self):
        for radius in range(1, 5):
            with self.subTest(radius=radius):
                np.testing.assert_array_equal(
                    self.draw(radius=radius),
                    reference_mask(self.silhouette, self.scene, radius=radius),
                )

    def test_cutout_holes_are_not_opaque_rectangles(self):
        self.scene.fill(0.3)
        self.scene[7:10, 7] = 1
        mask = self.draw()
        self.assertFalse(mask[7:10, 7].any())
        self.assertEqual(mask[6, 7], 1)
        self.assertEqual(mask[10, 7], 1)

    def test_full_silhouette_holes_have_their_own_edges(self):
        self.silhouette[5:9, 11:14, 1] = 0
        np.testing.assert_array_equal(
            self.draw(), reference_mask(self.silhouette, self.scene)
        )

    def test_atlas_tile_does_not_read_its_neighbour(self):
        atlas = np.empty((24, 48, 2), dtype=np.float32)
        atlas[:] = (0.6, 1)
        atlas[:, 12:36] = self.silhouette
        mask = self.draw(silhouette=atlas, silhouetteRect=(12, 0, 24, 24))
        np.testing.assert_array_equal(mask, reference_mask(self.silhouette, self.scene))

    def test_viewport_and_depth_origins(self):
        scene = np.ones((40, 40), dtype=np.float32)
        scene[9:33, 5:29] = self.scene
        target = self.target(48, 48)
        mask = self.draw(
            scene=scene,
            target=target,
            viewport=(13, 7, 24, 24),
            depthOrigin=(5, 9),
        )
        expected = np.zeros((48, 48), dtype=np.float32)
        expected[7:31, 13:37] = reference_mask(self.silhouette, self.scene)
        np.testing.assert_array_equal(mask, expected)

    def test_viewport_clipping_does_not_create_a_silhouette_edge(self):
        self.scene.fill(0.3)
        mask = self.draw(candidateOrigin=(-10, 0))
        expected = np.zeros_like(self.scene)
        expected[:, :14] = reference_mask(self.silhouette, self.scene)[:, 10:]
        np.testing.assert_array_equal(mask, expected)
        self.assertEqual(mask[12, 0], 0)

    def test_overlapping_candidates_keep_their_own_contours(self):
        self.scene.fill(0.3)
        first = self.silhouette.copy()
        second = np.zeros_like(first)
        second[6:18, 12:22] = (0.7, 1)
        target = self.target(24, 24)
        self.ctx.enable(moderngl.BLEND)
        self.ctx.blend_equation = moderngl.MAX
        self.draw(silhouette=first, target=target)
        mask = self.draw(silhouette=second, target=target)
        expected = np.maximum(
            reference_mask(first, self.scene), reference_mask(second, self.scene)
        )
        np.testing.assert_array_equal(mask, expected)
        # The first contour lies inside the second silhouette.
        self.assertEqual(mask[10, 16], 1)
        self.assertLessEqual(mask.max(), 1)
        inactive = self.draw(silhouette=second, target=target, eligible=False)
        np.testing.assert_array_equal(inactive, expected)

    def test_glass_capture_preserves_opaque_depth_in_both_orders(self):
        depth = self.ctx.depth_texture((24, 24))
        depth.compare_func = ""
        self.resources.append(depth)
        fbo = self.ctx.framebuffer(depth_attachment=depth)
        self.resources.append(fbo)
        for order in ([(0.4, False), (0.2, True)], [(0.2, True), (0.4, False)]):
            with self.subTest(order=order):
                fbo.use()
                fbo.clear(depth=1)
                self.ctx.enable_only(moderngl.DEPTH_TEST)
                self.ctx.depth_func = "<"
                for surface_depth, glass in order:
                    self.depth_program["surfaceDepth"].value = surface_depth
                    self.depth_program["glass"].value = glass
                    self.depth_vao.render(mode=moderngl.TRIANGLES, vertices=3)
                captured = np.frombuffer(depth.read(), dtype=np.float32).reshape(24, 24)
                np.testing.assert_allclose(captured, 0.4, atol=1e-6)
                self.ctx.enable_only(moderngl.NOTHING)
                mask = self.draw(scene=captured)
                np.testing.assert_array_equal(
                    mask, reference_mask(self.silhouette, captured)
                )
        fbo.use()
        fbo.clear(depth=1)
        self.ctx.enable_only(moderngl.DEPTH_TEST)
        self.depth_program["glass"].value = True
        self.depth_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        captured = np.frombuffer(depth.read(), dtype=np.float32).reshape(24, 24)
        self.ctx.enable_only(moderngl.NOTHING)
        self.assertFalse(self.draw(scene=captured).any())

    def test_composite_is_premultiplied_and_viewport_local(self):
        target = self.target(40, 40, components=4)
        mask = np.zeros((32, 32, 2), dtype=np.float32)
        mask[4:12, 3:11] = 1
        self.texture(mask).use(0)
        self.composite["outlineMask"].value = 0
        self.composite["maskOrigin"].value = (3, 4)
        self.composite["viewport"].value = (7, 9, 8, 8)
        self.composite["outlineColor"].value = (1.0, 0.5, 0.25, 0.4)
        self.composite_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        expected = np.zeros((40, 40, 4), dtype=np.float32)
        expected[9:17, 7:15] = (0.4, 0.2, 0.1, 0.4)
        np.testing.assert_allclose(self.read(target, 4), expected, atol=1e-6)

    def test_lighting_is_per_pixel_without_new_contour_edges(self):
        self.scene[:] = 0.3
        light = np.broadcast_to(
            np.linspace(0, 1, 24, dtype=np.float32)[:, None], (24, 24)
        ).copy()
        light[:8] = 0
        target = self.target(24, 24, components=2)
        alpha = self.draw(target=target, brightness=light)
        contour = reference_mask(self.silhouette, self.scene)
        fade = np.clip(light / 0.45, 0, 1)
        np.testing.assert_allclose(alpha, contour * fade, atol=1e-6)
        np.testing.assert_allclose(
            self.read(target, 2)[:, :, 1], contour * fade, atol=1e-6
        )
        # A lighting boundary inside the body is not a geometric contour.
        self.assertEqual(alpha[8, 12], 0)
        self.assertEqual(alpha[5, 7], 0)
        self.assertGreater(alpha[19, 7], alpha[10, 7])
        for bad in (np.nan, np.inf, -1):
            light[:] = bad
            target.clear()
            self.assertFalse(self.draw(target=target, brightness=light).any())

    def test_dark_composite_dims_colour_and_fades_without_black_reveal(self):
        mask = np.array([[[0, 0], [0.5, 0.5], [1, 1]]], dtype=np.float32)
        self.texture(mask).use(0)
        target = self.target(3, 1, components=4)
        self.composite["outlineMask"].value = 0
        self.composite["maskOrigin"].value = (0, 0)
        self.composite["viewport"].value = (0, 0, 3, 1)
        self.composite["outlineColor"].value = (1, 0.5, 0.25, 1)
        self.composite_vao.render(mode=moderngl.TRIANGLES, vertices=3)
        np.testing.assert_allclose(
            self.read(target, 4),
            [[[0, 0, 0, 0], [0.5, 0.25, 0.125, 0.5], [1, 0.5, 0.25, 1]]],
        )

    def test_daylight_reference_keeps_outlines_bright(self):
        self.scene[:] = 0.3
        contour = reference_mask(self.silhouette, self.scene)
        for light, fade in ((0, 0), (0.045, 0.1), (0.225, 0.5), (0.45, 1), (1, 1)):
            with self.subTest(light=light):
                illumination = np.full((24, 24), light, dtype=np.float32)
                np.testing.assert_allclose(
                    self.draw(brightness=illumination), contour * fade, atol=1e-6
                )

    def test_dim_contour_survives_rg8_quantization(self):
        self.scene[:] = 0.3
        colour = self.ctx.texture((24, 24), 2, dtype="f1")
        target = self.ctx.framebuffer(color_attachments=[colour])
        self.resources.extend([colour, target])
        target.use()
        target.clear()
        light = np.full((24, 24), 0.05, dtype=np.float32)
        self.draw(target=target, brightness=light, candidateOpacity=0.7)
        contour = reference_mask(self.silhouette, self.scene)
        expected = contour * (0.05 / 0.45) * 0.7
        np.testing.assert_allclose(
            self.read(target, 2)[:, :, 1], expected, atol=1 / 255
        )

    def test_irregular_masks_match_reference(self):
        random = np.random.default_rng(3792740760)
        for radius in range(1, 5):
            with self.subTest(radius=radius):
                self.silhouette[:, :, 0] = random.uniform(0.1, 0.9, (24, 24))
                self.silhouette[:, :, 1] = random.choice(
                    [0.0, 0.49, 0.5, 1.0], (24, 24)
                )
                self.scene[:] = random.uniform(0.1, 0.9, (24, 24))
                np.testing.assert_array_equal(
                    self.draw(radius=radius),
                    reference_mask(self.silhouette, self.scene, radius=radius),
                )

    def test_composite_rejects_nonfinite_inputs(self):
        target = self.target(4, 4, components=4)
        self.composite["outlineMask"].value = 0
        self.composite["maskOrigin"].value = (0, 0)
        self.composite["viewport"].value = (0, 0, 4, 4)
        for bad in (float("nan"), float("inf"), -float("inf")):
            for invalid_colour in (False, True):
                with self.subTest(bad=bad, invalid_colour=invalid_colour):
                    value = 1 if invalid_colour else bad
                    self.texture(np.full((4, 4), value, dtype=np.float32)).use(0)
                    self.composite["outlineColor"].value = (
                        bad if invalid_colour else 1.0,
                        0.5,
                        0.25,
                        0.4,
                    )
                    self.composite_vao.render(mode=moderngl.TRIANGLES, vertices=3)
                    self.assertFalse(self.read(target, 4).any())


if __name__ == "__main__":
    unittest.main(verbosity=2)
