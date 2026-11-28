package pzopt;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL40;

/**
 * The key light's shadow map from the occupancy mesh ({@link OccMesh}, binary greedy meshing; {@code godRaysVis=mesh} /
 * {@code mesh-dda}, 2026-11-28): the god rays' visibility per cell as a depth compare instead of the 2D DDA through the
 * grid ({@code GodRays.COMMON_GLSL} sunVis).
 *
 * <ul>
 *   <li>Solids: the merged floors, roofs, walls and the wall parts of windows and doorways drawn from the light into an
 *       orthographic depth map over the occupancy's window (256 x 256 squares x 16 levels round the camera chunk), one
 *       draw call by vertex pulling (12 vertices a quad: a window's sill and head parts).</li>
 *   <li>Crowns ({@code mesh} only): the merged crown boxes drawn into three float targets, additive (density x the
 *       distance toward the light, + on the faces turned to it, - on the others: the optical length through the boxes the
 *       texel's ray crosses), max (the topmost face toward the light) and min (the lowest away from it); a cell takes the
 *       share of that length above it. {@code mesh-dda} runs the DDA for the cells the map finds lit instead (exact
 *       crowns; the map only spares the cells in shade).</li>
 * </ul>
 * Drawn again only when the mesh, the light's step, the window (camera chunk) or the level range changed: in a still scene
 * and between sun steps nothing is drawn. World space as the compute's: x, y squares from the reference square (the camera
 * chunk's corner), z squares of height (levels x 2.449).
 */
final class OccMeshMap {
   private OccMeshMap() {
   }

   static final int MAP_UNIT = 36, CROWN_UNIT = 37, TOP_UNIT = 38, BOT_UNIT = 39;
   static final float LH = GodRays.LEVEL;
   static boolean failed;
   private static int prog, crownProg, vao, tbo, tboTex, fbo, depthTex, crownFbo;
   private static final int[] crownTex = new int[3];
   private static int res, cres, quadCap;
   private static int[] uP, uC;
   private static long upSerial = -1L, builtKey = Long.MIN_VALUE;
   private static int quads;
   private static IntBuffer upBuf;
   /** The light's view-projection (column-major) and its axes R, U, D (D toward the light), the depth range along D. */
   static final float[] VP = new float[16], AX = new float[9];
   static float cLo, cHi;
   static boolean valid;
   static long renders, renderNs, uploads, uploadNs, keeps, vertices;

   static final String VERT = String.join("\n",
      "#version 430",
      "layout(binding = 0) uniform isamplerBuffer uQ;",
      "uniform mat4 uVP;",
      "uniform ivec4 uRef;", // refX, refY (squares), the mesh's first level, crowns (1: draw the crown boxes)
      "uniform vec4 uD;", // the direction to the light (x, y, z squares; unit)
      "flat out float vS;",
      "flat out float vDen;",
      "out float vC;",
      "const float LH = 2.4494897;",
      "void main() {",
      "   int per = uRef.w == 1 ? 36 : 12;",
      "   int qi = gl_VertexID / per, v = gl_VertexID - qi * per;",
      "   ivec4 q = texelFetch(uQ, qi);",
      "   int z = q.z & 255, kind = (q.z >> 8) & 3, type = (q.z >> 10) & 3, den = (q.z >> 12) & 15;",
      "   int w = q.w & 255, h = (q.w >> 8) & 255;",
      "   vec3 b = vec3(float(q.x - uRef.x), float(q.y - uRef.y), float(z + uRef.z));",
      "   vS = 0.0; vDen = float(den); vC = 0.0;",
      "   vec3 P;",
      "   if (uRef.w == 1) {",
      "      if (kind != 3) { gl_Position = vec4(0.0); return; }",
      "      int face = v / 6, c = v - face * 6;",
      "      vec2 st = vec2((c == 1 || c == 4 || c == 5) ? 1.0 : 0.0, (c == 2 || c == 3 || c == 5) ? 1.0 : 0.0);",
      "      vec3 lo = vec3(b.xy, b.z * LH), hi = vec3(b.xy + vec2(w, h), (b.z + 1.0) * LH);",
      "      vec3 n;",
      "      int ax = face >> 1;", // 0 x, 1 y, 2 z faces; even the low side
      "      float side = (face & 1) == 0 ? 0.0 : 1.0;",
      "      if (ax == 0) { P = vec3(mix(lo.x, hi.x, side), mix(lo.y, hi.y, st.x), mix(lo.z, hi.z, st.y)); n = vec3(side * 2.0 - 1.0, 0, 0); }",
      "      else if (ax == 1) { P = vec3(mix(lo.x, hi.x, st.x), mix(lo.y, hi.y, side), mix(lo.z, hi.z, st.y)); n = vec3(0, side * 2.0 - 1.0, 0); }",
      "      else { P = vec3(mix(lo.x, hi.x, st.x), mix(lo.y, hi.y, st.y), mix(lo.z, hi.z, side)); n = vec3(0, 0, side * 2.0 - 1.0); }",
      "      vS = dot(n, uD.xyz) > 0.0 ? 1.0 : -1.0;",
      "      vC = dot(P, uD.xyz);",
      "   } else {",
      "      if (kind == 3) { gl_Position = vec4(0.0); return; }",
      "      int part = v / 6, c = v - part * 6;",
      "      vec2 st = vec2((c == 1 || c == 4 || c == 5) ? 1.0 : 0.0, (c == 2 || c == 3 || c == 5) ? 1.0 : 0.0);",
      // the blocking parts of the edge's levels (GodRays edgeBlocks: a window below 0.30 and above 0.84, a doorway above 0.80)
      "      float z0 = 0.0, z1 = float(h);",
      "      if (kind != 0 && type == 2) { z0 = part == 0 ? 0.0 : 0.84; z1 = part == 0 ? 0.30 : 1.0; }",
      "      else if (part == 1) { gl_Position = vec4(0.0); return; }",
      "      else if (kind != 0 && type == 3) { z0 = 0.80; z1 = 1.0; }",
      "      if (kind == 0) P = vec3(b.xy + st * vec2(w, h), b.z * LH);",
      "      else if (kind == 1) P = vec3(b.x + st.x * float(w), b.y, (b.z + mix(z0, z1, st.y)) * LH);",
      "      else P = vec3(b.x, b.y + st.x * float(w), (b.z + mix(z0, z1, st.y)) * LH);",
      "   }",
      "   gl_Position = uVP * vec4(P, 1.0);",
      "}",
      "");

   static final String DEPTH_FRAG = String.join("\n",
      "#version 430",
      "void main() {}",
      "");

   static final String CROWN_FRAG = String.join("\n",
      "#version 430",
      "flat in float vS;",
      "flat in float vDen;",
      "in float vC;",
      "layout(location = 0) out float oTau;",
      "layout(location = 1) out float oTop;",
      "layout(location = 2) out float oBot;",
      "void main() {",
      "   oTau = vS * vDen * vC;",
      "   oTop = vS > 0.0 ? vC : -1e9;",
      "   oBot = vS < 0.0 ? vC : 1e9;",
      "}",
      "");

   /** The visibility compute's mesh lookup, after GodRays.COMMON_GLSL (sunVis, leafNoise, uLim). */
   static final String LOOKUP_GLSL = String.join("\n",
      "uniform mat4 uMeshVP;",
      "uniform vec4 uMeshD;", // the direction to the light (unit), the compare bias (depth units)
      "uniform vec4 uMeshR;", // the light's right axis, the crowns on (1)
      "uniform vec4 uMeshU;", // its up axis
      "layout(binding = " + MAP_UNIT + ") uniform sampler2DShadow uMeshMap;",
      "layout(binding = " + CROWN_UNIT + ") uniform sampler2D uMeshTau;",
      "layout(binding = " + TOP_UNIT + ") uniform sampler2D uMeshTop;",
      "layout(binding = " + BOT_UNIT + ") uniform sampler2D uMeshBot;",
      "float visMesh(vec3 p) {",
      "   vec3 P = vec3(p.xy, p.z * LH);",
      "   vec4 lc = uMeshVP * vec4(P, 1.0);",
      "   vec3 s = lc.xyz * 0.5 + 0.5;",
      "   if (any(lessThan(s, vec3(0.0))) || any(greaterThan(s, vec3(1.0)))) return sunVis(p);", // outside the window: the DDA
      "   float lit = texture(uMeshMap, vec3(s.xy, s.z - uMeshD.w));",
      "   if (lit <= 0.0) return 0.0;",
      "#ifdef PZ_MESH_DDA",
      "   return lit * sunVis(p);",
      "#else",
      "   if (uMeshR.w < 0.5) return lit;",
      "   float tau = texture(uMeshTau, s.xy).r;",
      "   if (tau <= 1e-3) return lit;",
      "   float c = dot(P, uMeshD.xyz);",
      "   float top = texture(uMeshTop, s.xy).r, bot = texture(uMeshBot, s.xy).r;",
      "   float fr = clamp((top - c) / max(top - bot, 1e-3), 0.0, 1.0);", // the share of the crowns between the cell and the light
      "   float n = leafNoise(vec3(dot(P, uMeshR.xyz), dot(P, uMeshU.xyz), 0.0) * 1.4 + vec3(uRef.xy & 1023, 0.0));",
      "   float vis = lit * exp(-uLim.z * tau * fr * max(0.0, n * 1.8 - 0.55) * 1.2);",
      "   return vis < 0.02 ? 0.0 : vis;",
      "#endif",
      "}",
      "");

   private static boolean init() {
      res = Config.GOD_RAYS_MESH_RES;
      cres = Math.max(256, res / 4);
      prog = FogPass.link(VERT, DEPTH_FRAG, new String[0], new String[0]);
      crownProg = FogPass.link(VERT, CROWN_FRAG, new String[0], new String[] {"oTau", "oTop", "oBot"});
      if (prog == 0 || crownProg == 0) {
         Log.warn("occ mesh map: shaders did not link; the DDA");
         return false;
      }
      uP = locations(prog);
      uC = locations(crownProg);
      vao = GL30.glGenVertexArrays();
      depthTex = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL14.GL_DEPTH_COMPONENT16, res, res);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
      fbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
      GL11.glDrawBuffer(GL11.GL_NONE);
      GL11.glReadBuffer(GL11.GL_NONE);
      boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
      crownFbo = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, crownFbo);
      for (int i = 0; i < 3; i++) {
         crownTex[i] = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, crownTex[i]);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, GL30.GL_R32F, cres, cres);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, i == 0 ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, i == 0 ? GL11.GL_LINEAR : GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12_CLAMP);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12_CLAMP);
         GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, GL11.GL_TEXTURE_2D, crownTex[i], 0);
      }
      GL20.glDrawBuffers(new int[] {GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1, GL30.GL_COLOR_ATTACHMENT2});
      ok &= GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      if (!ok) {
         Log.warn("occ mesh map: framebuffers incomplete; the DDA");
         return false;
      }
      Log.info("occ mesh map: " + res + "^2 DEPTH16 + crowns " + cres + "^2 x 3 R32F ready (programs " + prog + "/" + crownProg + ")");
      return true;
   }

   private static final int GL12_CLAMP = 0x812F;

   private static int[] locations(int p) {
      return new int[] {GL20.glGetUniformLocation(p, "uVP"), GL20.glGetUniformLocation(p, "uRef"), GL20.glGetUniformLocation(p, "uD"), GL20.glGetUniformLocation(p, "uQ")};
   }

   /**
    * Render thread, before the visibility compute: the map for this frame's light and mesh (drawn again only when one of
    * them changed). prevFbo: the framebuffer to bind again. False: no map (the DDA).
    */
   static boolean render(GodRays.Frame f, int prevFbo, boolean crowns) {
      if (failed || f.meshQuads == null) {
         return false;
      }
      long t0 = System.nanoTime();
      try {
         if (prog == 0 && !init()) {
            failed = true;
            return false;
         }
         if (quadTex(f.meshQuads, f.meshSerial) == 0) {
            return false;
         }
         long key = ((((f.meshSerial * 31L + f.lightKey) * 131L + f.refX) * 7919L + f.refY) * 17L + f.meshZ0) * 3L + (crowns ? 1 : 0);
         if (key == builtKey && valid) {
            keeps++;
            return true;
         }
         lightBasis(f);
         GL11.glPushAttrib(GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT);
         ShadowAtlas.Tracked saved = ShadowAtlas.Tracked.save();
         try {
            zombie.core.opengl.GLStateRenderThread.ScissorTest.set(false);
            zombie.core.opengl.GLStateRenderThread.StencilTest.set(false);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL30.glBindVertexArray(vao);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tboTex);
            // the solids: depth only
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, res, res);
            zombie.core.opengl.GLStateRenderThread.Blend.set(false);
            zombie.core.opengl.GLStateRenderThread.DepthTest.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthMask.set(true);
            zombie.core.opengl.GLStateRenderThread.DepthFunc.set(GL11.GL_LESS);
            zombie.core.opengl.GLStateRenderThread.ColorMask.set(false, false, false, false);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            draw(prog, uP, f, false);
            // the crowns: additive / max / min, no depth
            if (crowns) {
               GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, crownFbo);
               GL11.glViewport(0, 0, cres, cres);
               zombie.core.opengl.GLStateRenderThread.DepthTest.set(false);
               zombie.core.opengl.GLStateRenderThread.DepthMask.set(false);
               zombie.core.opengl.GLStateRenderThread.ColorMask.set(true, true, true, true);
               GL30.glClearBufferfv(GL11.GL_COLOR, 0, new float[] {0F, 0F, 0F, 0F});
               GL30.glClearBufferfv(GL11.GL_COLOR, 1, new float[] {-1e9F, 0F, 0F, 0F});
               GL30.glClearBufferfv(GL11.GL_COLOR, 2, new float[] {1e9F, 0F, 0F, 0F});
               zombie.core.opengl.GLStateRenderThread.Blend.set(true);
               GL40.glBlendFunci(0, GL11.GL_ONE, GL11.GL_ONE);
               GL40.glBlendFunci(1, GL11.GL_ONE, GL11.GL_ONE);
               GL40.glBlendFunci(2, GL11.GL_ONE, GL11.GL_ONE);
               GL40.glBlendEquationi(0, GL14.GL_FUNC_ADD);
               GL40.glBlendEquationi(1, GL14.GL_MAX);
               GL40.glBlendEquationi(2, GL14.GL_MIN);
               draw(crownProg, uC, f, true);
               GL14.glBlendEquation(GL14.GL_FUNC_ADD);
               GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
         } finally {
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            zombie.core.ShaderHelper.forgetCurrentlyBound();
            zombie.core.DefaultShader.isActive = false;
            saved.restore();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glPopAttrib();
            zombie.core.SpriteRenderer.ringBuffer.restoreVbos = true;
            zombie.core.SpriteRenderer.ringBuffer.restoreBoundTextures = true;
         }
         builtKey = key;
         valid = true;
         renders++;
         renderNs += System.nanoTime() - t0;
         return true;
      } catch (Throwable t) {
         failed = true;
         valid = false;
         Log.warn("occ mesh map: failed, the DDA for the rest of the session: " + t);
         return false;
      }
   }

   /**
    * The mesh's quads in the texture buffer the map pass pulls from,
    * uploaded when serial changed (render thread). Returns the buffer texture (0: none).
    */
   static int quadTex(int[] q, long serial) {
      if (q == null) {
         return 0;
      }
      if (tboTex == 0) {
         tbo = GL15.glGenBuffers();
         tboTex = GL11.glGenTextures();
      }
      if (upSerial != serial) {
         long u0 = System.nanoTime();
         int n = q.length;
         if (upBuf == null || upBuf.capacity() < n) {
            upBuf = BufferUtils.createIntBuffer(Math.max(4096, n + n / 2));
         }
         upBuf.clear();
         upBuf.put(q).flip();
         GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, tbo);
         if (n > quadCap * 4 || quadCap == 0) {
            quadCap = Math.max(1024, n / 4 + n / 8);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, (long)quadCap * 16L, GL15.GL_DYNAMIC_DRAW);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, tboTex);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32I, tbo);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
         }
         GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0L, upBuf);
         GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, 0);
         quads = n / 4;
         upSerial = serial;
         uploads++;
         uploadNs += System.nanoTime() - u0;
      }
      return tboTex;
   }

   static int quads() {
      return quads;
   }

   private static void draw(int p, int[] u, GodRays.Frame f, boolean crowns) {
      GL20.glUseProgram(p);
      GL20.glUniformMatrix4fv(u[0], false, VP);
      GL20.glUniform4i(u[1], f.refX, f.refY, f.meshZ0, crowns ? 1 : 0);
      GL20.glUniform4f(u[2], AX[6], AX[7], AX[8], 0F);
      GL20.glUniform1i(u[3], 0);
      int verts = quads * (crowns ? 36 : 12);
      GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, verts);
      vertices += verts;
   }

   /** The light's axes and the orthographic box round the occupancy's window, as VP (render thread). */
   static void lightBasis(GodRays.Frame f) {
      double h = Math.hypot(f.lx, f.ly);
      double dx, dy, dz;
      if (h < 1e-3) {
         dx = 0; dy = 0; dz = 1;
      } else {
         dx = f.lx / h; dy = f.ly / h; dz = f.slope * LH;
      }
      double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
      dx /= dl; dy /= dl; dz /= dl;
      double ux = 0, uy = 0, uz = 1;
      if (Math.abs(dz) > 0.99) {
         ux = 1; uz = 0;
      }
      // R = normalize(U0 x D), U = D x R
      double rx = uy * dz - uz * dy, ry = uz * dx - ux * dz, rz = ux * dy - uy * dx;
      double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
      rx /= rl; ry /= rl; rz /= rl;
      double vx = dy * rz - dz * ry, vy = dz * rx - dx * rz, vz = dx * ry - dy * rx;
      int half = GodRays.OCC_CHUNKS / 2 * 8;
      double aLo = 1e30, aHi = -1e30, bLo = 1e30, bHi = -1e30, cL = 1e30, cH = -1e30;
      for (int i = 0; i < 8; i++) {
         double x = (i & 1) == 0 ? -half : half, y = (i & 2) == 0 ? -half : half;
         double z = ((i & 4) == 0 ? f.meshZ0 : f.meshZ0 + GodRays.OCC_L) * LH;
         double a = x * rx + y * ry + z * rz, b = x * vx + y * vy + z * vz, c = x * dx + y * dy + z * dz;
         aLo = Math.min(aLo, a); aHi = Math.max(aHi, a);
         bLo = Math.min(bLo, b); bHi = Math.max(bHi, b);
         cL = Math.min(cL, c); cH = Math.max(cH, c);
      }
      cL -= 1.0; cH += 1.0;
      cLo = (float)cL;
      cHi = (float)cH;
      // ndc x = 2 (a - aLo) / (aHi - aLo) - 1, y likewise, z = 2 (cH - c) / (cH - cL) - 1 (nearer the light = smaller)
      double sa = 2.0 / (aHi - aLo), sb = 2.0 / (bHi - bLo), sc = -2.0 / (cH - cL);
      double ta = -1.0 - aLo * sa, tb = -1.0 - bLo * sb, tc = -1.0 - cH * sc;
      // column-major: VP[col * 4 + row]
      VP[0] = (float)(rx * sa); VP[4] = (float)(ry * sa); VP[8] = (float)(rz * sa); VP[12] = (float)ta;
      VP[1] = (float)(vx * sb); VP[5] = (float)(vy * sb); VP[9] = (float)(vz * sb); VP[13] = (float)tb;
      VP[2] = (float)(dx * sc); VP[6] = (float)(dy * sc); VP[10] = (float)(dz * sc); VP[14] = (float)tc;
      VP[3] = 0F; VP[7] = 0F; VP[11] = 0F; VP[15] = 1F;
      AX[0] = (float)rx; AX[1] = (float)ry; AX[2] = (float)rz;
      AX[3] = (float)vx; AX[4] = (float)vy; AX[5] = (float)vz;
      AX[6] = (float)dx; AX[7] = (float)dy; AX[8] = (float)dz;
   }

   /** The compute program's lookup uniforms and textures (render thread, program bound). */
   private static final java.util.HashMap<Integer, int[]> LOOKUP_LOC = new java.util.HashMap<>();

   static void bind(int p, boolean crowns) {
      int[] l = LOOKUP_LOC.computeIfAbsent(p, k -> new int[] {GL20.glGetUniformLocation(k, "uMeshVP"), GL20.glGetUniformLocation(k, "uMeshD"),
         GL20.glGetUniformLocation(k, "uMeshR"), GL20.glGetUniformLocation(k, "uMeshU")});
      GL20.glUniformMatrix4fv(l[0], false, VP);
      // the compare bias: 0.05 squares along the light
      GL20.glUniform4f(l[1], AX[6], AX[7], AX[8], 0.05F / Math.max(1F, cHi - cLo));
      GL20.glUniform4f(l[2], AX[0], AX[1], AX[2], crowns ? 1F : 0F);
      GL20.glUniform4f(l[3], AX[3], AX[4], AX[5], 0F);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + MAP_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + CROWN_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, crownTex[0]);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + TOP_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, crownTex[1]);
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + BOT_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, crownTex[2]);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   static void unbind() {
      for (int u = MAP_UNIT; u <= BOT_UNIT; u++) {
         GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }

   static String stats() {
      String s = String.format(java.util.Locale.ROOT, "mesh map: %d draws (%.1f us cpu each, %.0f k vertices each), %d kept, %d uploads (%.1f us each), %d quads%s",
         renders, renders > 0 ? renderNs / 1000.0 / renders : 0.0, renders > 0 ? vertices / 1000.0 / renders : 0.0, keeps, uploads, uploads > 0 ? uploadNs / 1000.0 / uploads : 0.0, quads,
         failed ? " FAILED" : "");
      renders = renderNs = keeps = uploads = uploadNs = vertices = 0L;
      return s;
   }
}
