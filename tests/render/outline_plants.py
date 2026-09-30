#!/usr/bin/env python3
"""Cached low-vegetation exclusion, including native sway-generated shader programs."""

import os
import subprocess
import unittest
from pathlib import Path

import moderngl
import numpy as np
import occluded_outline as outlines

VERTEX = """#version 330 compatibility
uniform float surfaceDepth;
out vec2 texCoord;
out vec4 col;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = p;
    col = vec4(1);
    gl_Position = vec4(p * 2.0 - 1.0, surfaceDepth * 2.0 - 1.0, 1);
}
"""
COMPOSITE = """#version 330 compatibility
uniform sampler2D DIFFUSE;
uniform sampler2D DEPTH;
uniform float chunkDepth = 0.0;
in vec2 texCoord;
in vec4 col;
void main() {
    vec4 c = texture(DIFFUSE, texCoord);
    if (c.a < 0.5) discard;
    gl_FragColor = c * col;
    gl_FragDepth = chunkDepth + texture(DEPTH, texCoord).r;
}
"""


class OutlinePlantTest(outlines.OccludedOutlineTest):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        game = os.environ.get("PZ_DIR")
        cls.native_cp = None
        if game:
            root = Path(__file__).resolve().parents[2]
            cls.native_cp = os.pathsep.join(
                [
                    cls.java_dir.name,
                    str(root / "build/classes"),
                    str(Path(game) / "projectzomboid.jar"),
                ]
            )
            subprocess.run(
                [
                    "javac",
                    "--release",
                    "25",
                    "-cp",
                    cls.native_cp,
                    "-d",
                    cls.java_dir.name,
                    str(root / "tests/render/java/pzopt/OutlinePlantShaderExport.java"),
                ],
                check=True,
            )

    def setUp(self):
        super().setUp()
        self.control(0, 1, 1)
        ordinary_control = self.ctx.buffer(np.zeros(8, dtype=np.int32).tobytes())
        ordinary_control.bind_to_uniform_block(8)
        self.resources.append(ordinary_control)
        self.packed_target(1, 1)
        self.texture(
            np.ones((1, 1), dtype=np.float32).view(np.uint32), dtype="u4"
        ).bind_to_image(7)

    def patch_material(self, filename, source, plant=False):
        source = super().patch_material(filename, source)
        return super().patch_material(filename, source, plant=True)

    def export_sway(self, kind, source="", taps=1, *properties):
        if self.native_cp is None:
            self.skipTest(
                "PZ_DIR and a built repository are required for native sway source generation"
            )
        return subprocess.run(
            [
                "java",
                "-Dpzopt.userOptionsFile=/dev/null",
                *properties,
                "-cp",
                self.native_cp,
                "pzopt.OutlinePlantShaderExport",
                kind,
                str(taps),
            ],
            input=source,
            text=True,
            capture_output=True,
            check=True,
        ).stdout

    def control(self, mode, width, height):
        if not hasattr(self, "plant_control"):
            self.plant_control = self.ctx.buffer(reserve=32)
            self.resources.append(self.plant_control)
            self.plant_control.bind_to_uniform_block(9)
        self.plant_control.write(
            np.array([0, 0, width, height, mode, 0, 0, 0], dtype=np.int32).tobytes()
        )

    def packed_target(self, width, height):
        target = self.texture(
            np.full((height, width), 0xFFFFFFFF, dtype=np.uint32), dtype="u4"
        )
        target.bind_to_image(4)
        return target

    @staticmethod
    def unpack(texture):
        return np.frombuffer(texture.read(), dtype=np.uint32) >> 16

    def test_cached_wall_survives_grass_in_both_draw_orders(self):
        source = """#version 330 compatibility
        uniform float surfaceDepth;
        void main() { gl_FragColor = vec4(1); gl_FragDepth = surfaceDepth; }
        """
        program = self.ctx.program(
            vertex_shader=VERTEX,
            fragment_shader=self.patch_material(
                "tileWithDepth.frag", source, plant=True
            ),
        )
        vao = self.ctx.vertex_array(program, [])
        self.resources.extend([program, vao])
        for grass_first in (True, False):
            with self.subTest(grass_first=grass_first):
                fbo, native = self.capture_target(8, 8)
                filtered = self.packed_target(8, 8)
                for grass in (True, False) if grass_first else (False, True):
                    self.control(0 if grass else 1, 8, 8)
                    program["surfaceDepth"].value = 0.2 if grass else 0.6
                    vao.render(vertices=3)
                self.ctx.memory_barrier()
                np.testing.assert_allclose(
                    self.unpack(filtered) / 65535, 0.6, atol=1 / 65535
                )
                np.testing.assert_allclose(
                    np.frombuffer(native.read(), dtype=np.float32), 0.2, atol=1e-6
                )
                np.testing.assert_allclose(self.read(fbo), 1)
                silhouette = np.zeros((8, 8, 2), dtype=np.float32)
                silhouette[:, :, 0] = 0.4
                silhouette[2:6, 2:6, 1] = 1
                scene = (self.unpack(filtered).reshape(8, 8) / 65535).astype(np.float32)
                self.ctx.enable_only(moderngl.NOTHING)
                self.assertFalse(self.draw(silhouette=silhouette, scene=scene).any())
                silhouette[:, :, 0] = 0.8
                self.assertTrue(self.draw(silhouette=silhouette, scene=scene).any())

    def test_filtered_composite_survives_native_discard(self):
        source = self.patch_material("chunkShader.frag", COMPOSITE, plant=True)
        program = self.ctx.program(vertex_shader=VERTEX, fragment_shader=source)
        vao = self.ctx.vertex_array(program, [])
        self.resources.extend([program, vao])
        fbo = self.target(8, 8, components=4)
        packed = np.full((8, 8), round(0.4 * 65535) << 16, dtype=np.uint32)
        self.texture(packed, dtype="u4").use(8)
        self.texture(np.zeros((1, 1, 4), dtype=np.float32)).use(0)
        output = self.packed_target(8, 8)
        self.control(2, 8, 8)
        program["chunkDepth"].value = 0.1
        vao.render(vertices=3)
        self.ctx.memory_barrier()
        np.testing.assert_allclose(self.unpack(output) / 65535, 0.5, atol=1 / 65535)
        self.assertFalse(self.read(fbo, 4).any())

    def test_actual_tree_shader_retains_depth_and_sway_attributes(self):
        source = self.export_sway("tree")
        vertex = """#version 330 compatibility
        out vec2 vUv; out float vDepth; out vec4 vCol; out vec3 vSw;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0, 1);
            vUv = p; vDepth = 0.4; vCol = vec4(1); vSw = vec3(1, 1, 8);
        }
        """
        program = self.ctx.program(vertex_shader=vertex, fragment_shader=source)
        vao = self.ctx.vertex_array(program, [])
        self.resources.extend([program, vao])
        self.texture(np.ones((1, 1, 4), dtype=np.float32)).use(0)
        self.target(8, 8, components=4)
        output = self.packed_target(8, 8)
        self.control(1, 8, 8)
        vao.render(vertices=3)
        self.ctx.memory_barrier()
        values = np.frombuffer(output.read(), dtype=np.uint32)
        depth = (round(0.4 * 65535) & ~1) | 1
        expected = (depth << 16) | ((128 + (depth & 15)) << 8) | 255
        np.testing.assert_array_equal(values, expected)

    def test_tree_wind_is_preserved_behind_foreground_grass(self):
        program = self.ctx.program(
            vertex_shader=VERTEX,
            fragment_shader=self.export_sway("composite", COMPOSITE, 4),
        )
        vao = self.ctx.vertex_array(program, [])
        self.resources.extend([program, vao])
        size = 24
        tree_depth = (round(0.4 * 65535) & ~1) | 1
        depths = np.ones((size, size), dtype=np.float32)
        depths[7:15, 7:15] = tree_depth / 65535
        attributes = np.zeros((size, size, 2), dtype=np.float32)
        attributes[7:15, 7:15] = (1, (tree_depth & 15) / 255)
        packed = np.full((size, size), 0xFFFFFFFF, dtype=np.uint32)
        packed[7:15, 7:15] = (tree_depth << 16) | ((tree_depth & 15) << 8) | 255
        self.texture(np.ones((1, 1, 4), dtype=np.float32)).use(0)
        self.texture(depths).use(1)
        self.texture(attributes).use(2)
        self.texture(np.ones((1, 1), dtype=np.float32)).use(3)
        self.texture(np.ones((1, 1), dtype=np.float32)).use(4)
        self.texture(packed, dtype="u4").use(8)
        values = {
            "DIFFUSE": 0,
            "DEPTH": 1,
            "pzSwAux": 2,
            "pzSwMask": 3,
            "pzSwGustTex": 4,
            "pzSwOn": (1, 1 / size, 1 / size, 0),
            "pzSwMap": (0, 0, 0, 0),
            "pzSwWind": (0.35, 1, 1, 0),
            "pzSwPh": (0, 0, 0, 0),
            "pzSwGust": (0, 0, 0, 0),
            "pzSwPx": (1, 6, 12, 18),
            "chunkDepth": 0,
        }
        for name, value in values.items():
            if name in program:
                program[name].value = value
        fbo, native = self.capture_target(size, size)
        output = self.packed_target(size, size)
        self.control(2, size, size)
        vao.render(vertices=3)
        self.ctx.memory_barrier()
        expected = self.unpack(output).copy()
        normal = np.frombuffer(native.read(), dtype=np.float32)
        np.testing.assert_allclose(expected / 65535, normal, atol=1 / 65535)
        self.assertTrue(np.any(expected != (packed.reshape(-1) >> 16)))
        # Normal rendering now sees grass instead of the tree; filtered depth must remain
        # identical to the normal, swaying tree from the preceding draw, not shift with grass.
        grass_depth = (round(0.2 * 65535) & ~1) | 1
        self.texture(np.full((size, size), grass_depth / 65535, dtype=np.float32)).use(
            1
        )
        grass_attributes = np.empty((size, size, 2), dtype=np.float32)
        grass_attributes[:] = (252 / 255, (grass_depth & 15) / 255)
        self.texture(grass_attributes).use(2)
        output.write(np.full((size, size), 0xFFFFFFFF, dtype=np.uint32).tobytes())
        fbo.clear(depth=1)
        vao.render(vertices=3)
        self.ctx.memory_barrier()
        np.testing.assert_array_equal(self.unpack(output), expected)

    def test_actual_sway_composites_link_with_independent_lookup(self):
        for taps, options in (
            (1, ()),
            (4, ()),
            (2, ("-Dpzopt.swayPrefetch=true",)),
            (4, ("-Dpzopt.swayAuxEager=true", "-Dpzopt.swayIterations=2")),
            (2, ("-Dpzopt.upscaler=dlss",)),
            (4, ("-Dpzopt.upscaler=dlss", "-Dpzopt.testSwayImages=true")),
        ):
            with self.subTest(taps=taps, options=options):
                source = self.export_sway("composite", COMPOSITE, taps, *options)
                self.assertIn("void pzoptPlantSway()", source)
                self.assertIn("pzoptPlantAux(", source)
                cache_lookup = source.split("void pzoptPlantSway()", 1)[1]
                self.assertNotIn("imageStore(pzSwMv", cache_lookup)
                program = self.ctx.program(vertex_shader=VERTEX, fragment_shader=source)
                program.release()


if __name__ == "__main__":
    unittest.main(verbosity=2)
