package zombie.core.opengl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.VBO.GLVertexBufferObject;
import zombie.core.VBO.IGLBufferObject;
import zombie.core.math.PZMath;
import zombie.core.opengl.ShaderProgram.Uniform;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexElement;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexFormat;
import zombie.core.skinnedmodel.model.VertexBufferObject.VertexType;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.TextureID;
import zombie.popman.ObjectPool;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.UI3DScene.Ray;

public final class VBORenderer {
   private static VBORenderer instance;
   public final VertexFormat formatPositionColor = new VertexFormat(2);
   public final VertexFormat formatPositionColorUv = new VertexFormat(3);
   public final VertexFormat formatPositionColorUvDepth = new VertexFormat(4);
   public final VertexFormat formatPositionNormalColor = new VertexFormat(3);
   public final VertexFormat formatPositionNormalColorUv = new VertexFormat(4);
   private static final int BUFFER_SIZE = 4096;
   private static final int PZOPT_MAX_BATCH_BYTES = 1536 * 1024; // pzopt: keeps every vertex index inside the 16-bit index buffer
   private final int pzoptBatchBytes = pzoptBatchBytes(); // pzopt: element buffer size, stock 4 KB
   private final boolean pzoptFastQuads = pzopt.Overrides.enabled() && pzopt.Config.VBO_FAST_QUADS; // pzopt: single-advance textured quad path

   static {
      pzopt.Overrides.onClassLoaded("zombie.core.opengl.VBORenderer");
   }

   /** pzopt: the position/colour/uv shader for pzopt.RainTiles, created the same way getShaderForFormat does. */
   public VBOLinesShader pzoptShaderPositionColorUv() {
      this.shaderPositionColorUv = this.initShader("vboRenderer_PositionColorUV", this.shaderPositionColorUv);
      return this.shaderPositionColorUv;
   }

   private static int pzoptBatchBytes() { // pzopt: Config.vboBatchKb, 4 = stock
      if (!pzopt.Overrides.enabled()) {
         return 4096;
      }
      long bytes = (long)pzopt.Config.VBO_BATCH_KB * 1024L;
      return (int)Math.max(4096L, Math.min(PZOPT_MAX_BATCH_BYTES, bytes));
   }
   private int elementSize;
   private int numElements;
   private static final int INDEX_SIZE = 2;
   private int vertexOffset;
   private int normalOffset;
   private int colorOffset;
   private int uv1Offset;
   private int uv2Offset;
   private int depthOffset;
   private VBOLinesShader shaderPositionColor;
   private VBOLinesShader shaderPositionColorUv;
   private VBOLinesShader shaderPositionColorUvDepth;
   private VBOLinesShader shaderPositionNormalColor;
   private VBOLinesShader shaderPositionNormalColorUv;
   private VertexFormat format;
   private VertexFormat formatUsedByVertexAttribArray;
   private GLVertexBufferObject vbo;
   private GLVertexBufferObject ibo;
   private ByteBuffer elements;
   private ByteBuffer indices;
   private float dx;
   private float dy;
   private float dz;
   private ShaderProgram currentShaderProgram;
   private final VBORendererCommands commands = new VBORendererCommands(this);
   private final VBORendererCommands commandsReady = new VBORendererCommands(this);
   private Boolean forceDepthTest;
   private Float forceUserDepth;
   private final ObjectPool<VBORenderer.Run> runPool = new ObjectPool(VBORenderer.Run::new, "VBORenderer.runPool");
   private final ArrayList<VBORenderer.Run> runs = new ArrayList<>();
   private VBORenderer.Run runInProgress;
   private static final Vector3f tempVector3f_1 = new Vector3f();
   private static final Vector3f tempVector3f_2 = new Vector3f();

   public static VBORenderer getInstance() {
      if (instance == null) {
         instance = new VBORenderer();
      }

      return instance;
   }

   private VBORenderer.Run currentRun() {
      return this.runInProgress;
   }

   public VBORenderer() {
      this.formatPositionColor.setElement(0, VertexType.VertexArray, 12);
      this.formatPositionColor.setElement(1, VertexType.ColorArray, 16);
      this.formatPositionColor.calculate();
      this.formatPositionColorUv.setElement(0, VertexType.VertexArray, 12);
      this.formatPositionColorUv.setElement(1, VertexType.ColorArray, 16);
      this.formatPositionColorUv.setElement(2, VertexType.TextureCoordArray, 8);
      this.formatPositionColorUv.calculate();
      this.formatPositionColorUvDepth.setElement(0, VertexType.VertexArray, 12);
      this.formatPositionColorUvDepth.setElement(1, VertexType.ColorArray, 16);
      this.formatPositionColorUvDepth.setElement(2, VertexType.TextureCoordArray, 8);
      this.formatPositionColorUvDepth.setElement(3, VertexType.Depth, 4);
      this.formatPositionColorUvDepth.calculate();
      this.formatPositionNormalColor.setElement(0, VertexType.VertexArray, 12);
      this.formatPositionNormalColor.setElement(1, VertexType.NormalArray, 12);
      this.formatPositionNormalColor.setElement(2, VertexType.ColorArray, 16);
      this.formatPositionNormalColor.calculate();
      this.formatPositionNormalColorUv.setElement(0, VertexType.VertexArray, 12);
      this.formatPositionNormalColorUv.setElement(1, VertexType.NormalArray, 12);
      this.formatPositionNormalColorUv.setElement(2, VertexType.ColorArray, 16);
      this.formatPositionNormalColorUv.setElement(3, VertexType.TextureCoordArray, 8);
      this.formatPositionNormalColorUv.calculate();
      this.setFormat(this.formatPositionColorUv);
   }

   private void setFormat(VertexFormat format) {
      if (format != this.format) {
         this.format = format;
         this.elementSize = this.format.getStride();
         this.numElements = this.pzoptBatchBytes / this.elementSize; // pzopt: whole batch buffer
         this.vertexOffset = -1;
         this.normalOffset = -1;
         this.colorOffset = -1;
         this.uv1Offset = -1;
         this.uv2Offset = -1;
         this.depthOffset = -1;

         for (int i = 0; i < this.format.getNumElements(); i++) {
            VertexElement ve = this.format.getElement(i);
            switch (ve.type) {
               case VertexArray:
                  this.vertexOffset = ve.byteOffset;
                  break;
               case NormalArray:
                  this.normalOffset = ve.byteOffset;
                  break;
               case ColorArray:
                  this.colorOffset = ve.byteOffset;
                  break;
               case TextureCoordArray:
                  if (this.uv1Offset == -1) {
                     this.uv1Offset = ve.byteOffset;
                  } else {
                     this.uv2Offset = ve.byteOffset;
                  }
                  break;
               case Depth:
                  this.depthOffset = ve.byteOffset;
            }
         }
      }
   }

   private void create() {
      int minElementSize = 8;
      int maxElements = 512;
      this.elements = BufferUtils.createByteBuffer(this.pzoptBatchBytes); // pzopt: Config.vboBatchKb
      this.indices = BufferUtils.createByteBuffer(this.pzoptBatchBytes / 4); // pzopt: same vertex:index ratio as stock
      IGLBufferObject funcs = GLVertexBufferObject.funcs;
      this.vbo = new GLVertexBufferObject((long)this.pzoptBatchBytes, funcs.GL_ARRAY_BUFFER(), funcs.GL_STREAM_DRAW()); // pzopt
      this.vbo.create();
      this.ibo = new GLVertexBufferObject((long)(this.pzoptBatchBytes / 4), funcs.GL_ELEMENT_ARRAY_BUFFER(), funcs.GL_STREAM_DRAW()); // pzopt
      this.ibo.create();
   }

   public void setOffset(float dx, float dy, float dz) {
      this.dx = dx;
      this.dy = dy;
      this.dz = dz;
   }

   public VBORenderer addElement() {
      if (this.isFull()) {
         this.flush();
      }

      if (this.elements == null) {
         this.create();
      }

      short index = (short)(this.elements.position() / this.elementSize);
      this.indices.putShort(index);
      this.elements.position(this.elements.position() + this.elementSize);
      this.currentRun().vertexCount++;
      return this;
   }

   public VBORenderer putByte(byte value) {
      this.elements.put(value);
      return this;
   }

   public VBORenderer putFloat(float value) {
      this.elements.putFloat(value);
      return this;
   }

   public VBORenderer putInt(int value) {
      this.elements.putInt(value);
      return this;
   }

   public VBORenderer putShort(short value) {
      this.elements.putShort(value);
      return this;
   }

   public void setFloats1(int byteOffset, float f1) {
      if (byteOffset != -1) {
         int index = this.elements.position() - this.elementSize + byteOffset;
         this.elements.putFloat(index, f1);
      }
   }

   public void setFloats2(int byteOffset, float f1, float f2) {
      if (byteOffset != -1) {
         int index = this.elements.position() - this.elementSize + byteOffset;
         this.elements.putFloat(index, f1);
         index += 4;
         this.elements.putFloat(index, f2);
      }
   }

   public void setFloats3(int byteOffset, float f1, float f2, float f3) {
      if (byteOffset != -1) {
         int index = this.elements.position() - this.elementSize + byteOffset;
         this.elements.putFloat(index, f1);
         index += 4;
         this.elements.putFloat(index, f2);
         index += 4;
         this.elements.putFloat(index, f3);
      }
   }

   public void setFloats4(int byteOffset, float f1, float f2, float f3, float f4) {
      if (byteOffset != -1) {
         int index = this.elements.position() - this.elementSize + byteOffset;
         this.elements.putFloat(index, f1);
         index += 4;
         this.elements.putFloat(index, f2);
         index += 4;
         this.elements.putFloat(index, f3);
         index += 4;
         this.elements.putFloat(index, f4);
      }
   }

   public void setVertex(float x, float y, float z) {
      this.setFloats3(this.vertexOffset, x, y, z);
   }

   public void setNormal(float x, float y, float z) {
      this.setFloats3(this.normalOffset, x, y, z);
   }

   public void setColor(float r, float g, float b, float a) {
      this.setFloats4(this.colorOffset, r, g, b, a);
   }

   public void setUV1(float u, float v) {
      this.setFloats2(this.uv1Offset, u, v);
   }

   public void setUV2(float u, float v) {
      this.setFloats2(this.uv2Offset, u, v);
   }

   public void setDepth(float depth) {
      this.setFloats1(this.depthOffset, depth);
   }

   public void addElement(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
      this.addElement();
      this.setVertex(this.dx + x, this.dy + y, this.dz + z);
      this.setColor(r, g, b, a);
      this.setUV1(u, v);
   }

   public void addElementDepth(float x, float y, float z, float u, float v, float depth, float r, float g, float b, float a) {
      this.addElement(x, y, z, u, v, r, g, b, a);
      this.setDepth(depth);
   }

   public void addElement(float x, float y, float z, float r, float g, float b, float a) {
      this.addElement();
      this.setVertex(this.dx + x, this.dy + y, this.dz + z);
      this.setColor(r, g, b, a);
   }

   public void addLine(float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a) {
      this.reserve(2);
      this.addElement(x0, y0, z0, r, g, b, a);
      this.addElement(x1, y1, z1, r, g, b, a);
   }

   public void addLine(
      float x0, float y0, float z0, float x1, float y1, float z1, float r0, float g0, float b0, float a0, float r1, float g1, float b1, float a1
   ) {
      this.reserve(2);
      this.addElement(x0, y0, z0, r0, g0, b0, a0);
      this.addElement(x1, y1, z1, r1, g1, b1, a1);
   }

   public void addRectOutline(float x0, float y0, float x1, float y1, float z, float r, float g, float b, float a) {
      this.addLine(x0, y0, z, x1, y0, z, r, g, b, a);
      this.addLine(x1, y0, z, x1, y1, z, r, g, b, a);
      this.addLine(x1, y1, z, x0, y1, z, r, g, b, a);
      this.addLine(x0, y1, z, x0, y0, z, r, g, b, a);
   }

   public void addLineWithThickness(float x0, float y0, float z0, float x1, float y1, float z1, float thickness, float r, float g, float b, float a) {
      Vector3f vec1 = tempVector3f_1.set(x1 - x0, y1 - y0, 0.0F).normalize();
      Vector3f vec2 = vec1.cross(0.0F, 0.0F, 1.0F, tempVector3f_2);
      vec2.x *= thickness;
      vec2.y *= thickness;
      float fx1 = x0 - vec2.x / 2.0F;
      float fx2 = x0 + vec2.x / 2.0F;
      float bx1 = x1 - vec2.x / 2.0F;
      float bx2 = x1 + vec2.x / 2.0F;
      float fy1 = y0 - vec2.y / 2.0F;
      float fy2 = y0 + vec2.y / 2.0F;
      float by1 = y1 - vec2.y / 2.0F;
      float by2 = y1 + vec2.y / 2.0F;
      float u = 0.0F;
      float v = 0.0F;
      this.addQuad(fx1, fy1, 0.0F, 0.0F, fx2, fy2, 0.0F, 0.0F, bx2, by2, 0.0F, 0.0F, bx1, by1, 0.0F, 0.0F, z0, r, g, b, a);
   }

   public void addTriangle(
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float r,
      float g,
      float b,
      float a
   ) {
      this.reserve(3);
      this.addElement(x0, y0, z0, u0, v0, r, g, b, a);
      this.addElement(x1, y1, z1, u1, v1, r, g, b, a);
      this.addElement(x2, y2, z2, u2, v2, r, g, b, a);
   }

   public void addTriangleDepth(
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float depth0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float depth1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float depth2,
      float r,
      float g,
      float b,
      float a
   ) {
      if (this.currentRun().mode == 1) {
         this.reserve(6);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a);
      } else {
         this.reserve(3);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a);
      }
   }

   public void addTriangleDepth(
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float depth0,
      float alpha0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float depth1,
      float alpha1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float depth2,
      float alpha2,
      float r,
      float g,
      float b,
      float a
   ) {
      if (this.currentRun().mode == 1) {
         this.reserve(6);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a * alpha0);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a * alpha1);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a * alpha1);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a * alpha2);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a * alpha2);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a * alpha0);
      } else {
         this.reserve(3);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a * alpha0);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a * alpha1);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a * alpha2);
      }
   }

   public void addTriangle(float x0, float y0, float z0, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b, float a) {
      this.reserve(3);
      this.addElement(x0, y0, z0, r, g, b, a);
      this.addElement(x1, y1, z1, r, g, b, a);
      this.addElement(x2, y2, z2, r, g, b, a);
   }

   public void addQuad(float x0, float y0, float x1, float y1, float z, float r, float g, float b, float a) {
      if (this.currentRun().mode == 1) {
         this.reserve(6);
         this.addLine(x0, y0, z, x1, y0, z, r, g, b, a);
         this.addLine(x1, y0, z, x1, y1, z, r, g, b, a);
         this.addLine(x1, y1, z, x0, y1, z, r, g, b, a);
         this.addLine(x0, y1, z, x0, y0, z, r, g, b, a);
      } else if (this.currentRun().mode == 4) {
         this.reserve(6);
         this.addTriangle(x0, y0, z, x1, y0, z, x0, y1, z, r, g, b, a);
         this.addTriangle(x1, y0, z, x1, y1, z, x0, y1, z, r, g, b, a);
      } else {
         this.reserve(4);
         this.addElement(x0, y0, z, r, g, b, a);
         this.addElement(x1, y0, z, r, g, b, a);
         this.addElement(x1, y1, z, r, g, b, a);
         this.addElement(x0, y1, z, r, g, b, a);
      }
   }

   public void addQuad(float x0, float y0, float u0, float v0, float x1, float y1, float u1, float v1, float z, float r, float g, float b, float a) {
      this.addQuad(x0, y0, u0, v0, x1, y0, u1, v0, x1, y1, u1, v1, x0, y1, u0, v1, z, r, g, b, a);
   }

   public void addQuad(
      float x0,
      float y0,
      float u0,
      float v0,
      float x1,
      float y1,
      float u1,
      float v1,
      float x2,
      float y2,
      float u2,
      float v2,
      float x3,
      float y3,
      float u3,
      float v3,
      float z,
      float r,
      float g,
      float b,
      float a
   ) {
      this.addQuad(x0, y0, z, u0, v0, x1, y1, z, u1, v1, x2, y2, z, u2, v2, x3, y3, z, u3, v3, r, g, b, a);
   }

   public void addQuad(
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float x3,
      float y3,
      float z3,
      float u3,
      float v3,
      float r,
      float g,
      float b,
      float a
   ) {
      if (this.currentRun().mode == 1) {
         this.addLine(x0, y0, z0, x1, y1, z1, r, g, b, a);
         this.addLine(x1, y1, z1, x2, y2, z2, r, g, b, a);
         this.addLine(x2, y2, z2, x3, y3, z3, r, g, b, a);
         this.addLine(x3, y3, z3, x0, y0, z0, r, g, b, a);
      } else if (this.currentRun().mode == 4) {
         this.reserve(6);
         this.addTriangle(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x3, y3, z3, u3, v3, r, g, b, a);
         this.addTriangle(x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, r, g, b, a);
      } else if (this.pzoptFastQuads && this.vertexOffset != -1 && this.colorOffset != -1) { // pzopt: one position advance per quad
         this.pzoptAddQuad(x0, y0, z0, u0, v0, x1, y1, z1, u1, v1, x2, y2, z2, u2, v2, x3, y3, z3, u3, v3, r, g, b, a);
      } else {
         this.reserve(4);
         this.addElement(x0, y0, z0, u0, v0, r, g, b, a);
         this.addElement(x1, y1, z1, u1, v1, r, g, b, a);
         this.addElement(x2, y2, z2, u2, v2, r, g, b, a);
         this.addElement(x3, y3, z3, u3, v3, r, g, b, a);
      }
   }

   /* pzopt: the 4-vertex quad of the branch above written in one go: the same bytes (vertex, colour, uv1 at the
    * format offsets, other slots untouched like addElement leaves them), the four indices and a single position
    * advance, without the per-vertex isFull / currentRun / position() round trips. */
   private void pzoptAddQuad(
      float x0, float y0, float z0, float u0, float v0,
      float x1, float y1, float z1, float u1, float v1,
      float x2, float y2, float z2, float u2, float v2,
      float x3, float y3, float z3, float u3, float v3,
      float r, float g, float b, float a
   ) {
      if (this.elements == null) {
         this.create();
      }

      if (this.isFull() || !this.hasRoomFor(4)) {
         this.flush();
      }

      ByteBuffer e = this.elements;
      int stride = this.elementSize;
      int pos = e.position();
      int base = pos / stride;
      ByteBuffer ix = this.indices;
      ix.putShort((short)base);
      ix.putShort((short)(base + 1));
      ix.putShort((short)(base + 2));
      ix.putShort((short)(base + 3));
      float ox = this.dx;
      float oy = this.dy;
      float oz = this.dz;
      this.pzoptPutVertex(e, pos, ox + x0, oy + y0, oz + z0, u0, v0, r, g, b, a);
      this.pzoptPutVertex(e, pos + stride, ox + x1, oy + y1, oz + z1, u1, v1, r, g, b, a);
      this.pzoptPutVertex(e, pos + 2 * stride, ox + x2, oy + y2, oz + z2, u2, v2, r, g, b, a);
      this.pzoptPutVertex(e, pos + 3 * stride, ox + x3, oy + y3, oz + z3, u3, v3, r, g, b, a);
      e.position(pos + 4 * stride);
      this.currentRun().vertexCount += 4;
   }

   private void pzoptPutVertex(ByteBuffer e, int at, float x, float y, float z, float u, float v, float r, float g, float b, float a) { // pzopt
      int i = at + this.vertexOffset;
      e.putFloat(i, x);
      e.putFloat(i + 4, y);
      e.putFloat(i + 8, z);
      i = at + this.colorOffset;
      e.putFloat(i, r);
      e.putFloat(i + 4, g);
      e.putFloat(i + 8, b);
      e.putFloat(i + 12, a);
      if (this.uv1Offset != -1) {
         i = at + this.uv1Offset;
         e.putFloat(i, u);
         e.putFloat(i + 4, v);
      }
   }

   public void addQuadDepth(
      float x0,
      float y0,
      float z0,
      float u0,
      float v0,
      float depth0,
      float x1,
      float y1,
      float z1,
      float u1,
      float v1,
      float depth1,
      float x2,
      float y2,
      float z2,
      float u2,
      float v2,
      float depth2,
      float x3,
      float y3,
      float z3,
      float u3,
      float v3,
      float depth3,
      float r,
      float g,
      float b,
      float a
   ) {
      if (this.currentRun().mode == 4) {
         this.reserve(6);
         this.addTriangleDepth(x0, y0, z0, u0, v0, depth0, x1, y1, z1, u1, v1, depth1, x3, y3, z3, u3, v3, depth3, r, g, b, a);
         this.addTriangleDepth(x1, y1, z1, u1, v1, depth1, x2, y2, z2, u2, v2, depth2, x3, y3, z3, u3, v3, depth3, r, g, b, a);
      } else {
         this.reserve(4);
         this.addElementDepth(x0, y0, z0, u0, v0, depth0, r, g, b, a);
         this.addElementDepth(x1, y1, z1, u1, v1, depth1, r, g, b, a);
         this.addElementDepth(x2, y2, z2, u2, v2, depth2, r, g, b, a);
         this.addElementDepth(x3, y3, z3, u3, v3, depth3, r, g, b, a);
      }
   }

   public void addAABB(
      float x, float y, float z, float xMin, float yMin, float zMin, float xMax, float yMax, float zMax, float r, float g, float b, float a, boolean bQuads
   ) {
      if (bQuads) {
         Shader shader = ShaderManager.instance.getOrCreateShader("debug_chunk_state_geometry", false, false);
         ShaderProgram shaderProgram = shader.getShaderProgram();
         if (!shaderProgram.isCompiled()) {
            shaderProgram = null;
         }

         GL11.glEnable(3042);
         GL11.glBlendFunc(770, 771);
         GL11.glDisable(2884);
         GL11.glDepthFunc(515);
         GL11.glDepthMask(true);
         this.setDepthTestForAllRuns(Boolean.TRUE);
         this.addBox(xMax - xMin, yMax - yMin, zMax - zMin, r, g, b, a, shaderProgram);
         this.flush();
         this.setDepthTestForAllRuns(null);
         GL11.glDepthMask(false);
      } else {
         this.setOffset(x, y, z);
         this.startRun(this.formatPositionColor);
         this.setMode(1);
         this.setLineWidth(1.0F);
         this.addLine(xMax, yMax, zMax, xMin, yMax, zMax, r, g, b, a);
         this.addLine(xMax, yMax, zMax, xMax, yMin, zMax, r, g, b, a);
         this.addLine(xMax, yMax, zMax, xMax, yMax, zMin, r, g, b, a);
         this.addLine(xMin, yMax, zMax, xMin, yMin, zMax, r, g, b, a);
         this.addLine(xMin, yMax, zMax, xMin, yMax, zMin, r, g, b, a);
         this.addLine(xMax, yMax, zMin, xMax, yMin, zMin, r, g, b, a);
         this.addLine(xMax, yMax, zMin, xMin, yMax, zMin, r, g, b, a);
         this.addLine(xMin, yMax, zMin, xMin, yMin, zMin, r, g, b, a);
         this.addLine(xMax, yMin, zMin, xMin, yMin, zMin, r, g, b, a);
         this.addLine(xMax, yMin, zMax, xMax, yMin, zMin, r, g, b, a);
         this.addLine(xMin, yMin, zMax, xMin, yMin, zMin, r, g, b, a);
         this.addLine(xMax, yMin, zMax, xMin, yMin, zMax, r, g, b, a);
         this.endRun();
         this.flush();
         this.setOffset(0.0F, 0.0F, 0.0F);
      }
   }

   public void addAABB(float x, float y, float z, float width, float height, float length, float r, float g, float b) {
      float hx = width / 2.0F;
      float hy = height / 2.0F;
      float hz = length / 2.0F;
      this.addAABB(x, y, z, -hx, -hy, -hz, hx, hy, hz, r, g, b, 1.0F, false);
   }

   public void addAABB(float x, float y, float z, Vector3f min, Vector3f max, float r, float g, float b) {
      this.addAABB(x, y, z, min.x, min.y, min.z, max.x, max.y, max.z, r, g, b, 1.0F, false);
   }

   boolean isFull() {
      VBORenderer.Run run = this.currentRun();
      if (this.elements == null) {
         return false;
      } else if (run.mode == 4 && run.vertexCount % 3 == 0 && this.elements.position() + 3 * this.elementSize > this.elementSize * this.numElements) {
         return true;
      } else {
         return run.mode == 7 && run.vertexCount % 4 == 0 && this.elements.position() + 4 * this.elementSize > this.elementSize * this.numElements
            ? true
            : this.elements.position() >= this.elementSize * this.numElements;
      }
   }

   public void reserve(int numElements) {
      if (!this.hasRoomFor(numElements)) {
         this.flush();
      }
   }

   boolean hasRoomFor(int numElements) {
      return this.elements == null || this.elements.position() + this.elementSize * numElements <= this.elements.limit();
   }

   private VBOLinesShader initShader(String name, VBOLinesShader shader) {
      return shader == null ? new VBOLinesShader(name) : shader;
   }

   private VBOLinesShader getShaderForFormat() {
      if (this.format == this.formatPositionColor) {
         return this.shaderPositionColor = this.initShader("vboRenderer_PositionColor", this.shaderPositionColor);
      } else if (this.format == this.formatPositionColorUv) {
         return this.shaderPositionColorUv = this.initShader("vboRenderer_PositionColorUV", this.shaderPositionColorUv);
      } else if (this.format == this.formatPositionColorUvDepth) {
         return this.shaderPositionColorUvDepth = this.initShader("vboRenderer_PositionColorUVDepth", this.shaderPositionColorUvDepth);
      } else if (this.format == this.formatPositionNormalColor) {
         return this.shaderPositionNormalColor = this.initShader("vboRenderer_PositionNormalColor", this.shaderPositionNormalColor);
      } else {
         return this.format == this.formatPositionNormalColorUv
            ? (this.shaderPositionNormalColorUv = this.initShader("vboRenderer_PositionNormalColorUV", this.shaderPositionNormalColorUv))
            : null;
      }
   }

   public void flush() {
      if (this.runInProgress == null) {
         this.commandsReady.adopt(this.commands);
      } else if (this.runInProgress.vertexCount > 0) {
         this.commands.cmdRenderRun();
         this.commandsReady.adopt(this.commands);
         this.commands.cmdStartRun();
      } else {
         boolean bClearElements = true;
      }

      if (this.elements != null && this.elements.position() != 0) {
         this.elements.flip();
         this.indices.flip();
         GL13.glActiveTexture(33984);
         this.vbo.bind();
         this.vbo.bufferData(this.elements);
         this.ibo.bind();
         this.ibo.bufferData(this.indices);
         GL11.glDisableClientState(32884);
         GL11.glDisableClientState(32886);
         GL11.glDisableClientState(32888);
         GL11.glEnable(2848);
         this.currentShaderProgram = null;
         this.formatUsedByVertexAttribArray = null;
         this.commandsReady.invoke();
         boolean bClearElements = true;
         if (this.runInProgress != null) {
            if (this.runInProgress.mode != 8) {
               this.runInProgress.startVertex = 0;
               this.runInProgress.startIndex = 0;
               this.runInProgress.vertexCount = 0;
            } else {
               int vertex = this.runInProgress.startVertex + this.runInProgress.vertexCount - 2;

               for (int i = 0; i < this.elementSize * 2; i++) {
                  this.elements.put(i, this.elements.get(vertex * this.elementSize + i));
               }

               this.indices.putShort(0, (short)0);
               this.indices.putShort(2, (short)1);
               this.elements.limit(this.elements.capacity());
               this.indices.limit(this.indices.capacity());
               this.elements.position(this.elementSize * 2);
               this.indices.position(4);
               this.runInProgress.startVertex = 0;
               this.runInProgress.startIndex = 0;
               this.runInProgress.vertexCount = 2;
               bClearElements = false;
            }

            this.setFormat(this.runInProgress.format);
         }

         this.vbo.bindNone();
         this.ibo.bindNone();
         if (bClearElements) {
            this.elements.clear();
            this.indices.clear();
         }

         this.commandsReady.clear();
         this.runPool.releaseAll(this.runs);
         this.runs.clear();
         if (this.runInProgress == null && this.currentShaderProgram != null) {
            this.currentShaderProgram.End();
            this.currentShaderProgram = null;
         }

         GL11.glEnable(2929);
         GL11.glDisable(2848);

         for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
         }

         this.formatUsedByVertexAttribArray = null;
         GL13.glActiveTexture(33984);
         SpriteRenderer.ringBuffer.restoreVbos = true;
         SpriteRenderer.ringBuffer.restoreBoundTextures = true;
      } else {
         this.commandsReady.invoke();
         this.commandsReady.clear();
      }
      pzopt.OccludedOutline.framebuffer(zombie.core.textures.TextureFBO.lastID); // pzopt: restore world capture after the VBO batch
   }

   private void useShaderProgram(ShaderProgram shaderProgram) {
      if (shaderProgram == this.currentShaderProgram) {
         VertexBufferObject.setModelViewProjection(this.currentShaderProgram);
      } else {
         this.currentShaderProgram = shaderProgram;
         if (shaderProgram == null) {
            ShaderHelper.glUseProgramObjectARB(0);
         } else {
            shaderProgram.Start();
            VertexBufferObject.setModelViewProjection(this.currentShaderProgram);
         }
      }
   }

   private void setVertexAttribArrays(VertexFormat format) {
      this.setFormat(format);
      this.formatUsedByVertexAttribArray = format;

      for (int i = 0; i < format.getNumElements(); i++) {
         VertexElement ve = format.getElement(i);
         GL20.glEnableVertexAttribArray(i);
         boolean bNormalized = ve.type == VertexType.ColorArray;
         GL20.glVertexAttribPointer(i, ve.byteSize / 4, 5126, bNormalized, this.elementSize, ve.byteOffset);
      }

      for (int i = format.getNumElements(); i < 5; i++) {
         GL20.glDisableVertexAttribArray(i);
      }
   }

   private void startRun(VBORenderer.Run run) {
      if (run.textureId == null) {
         GL11.glDisable(3553);
      } else {
         GL11.glEnable(3553);
         run.textureId.bind();
         if (run.clampS != 0) {
            GL11.glTexParameteri(3553, 10242, run.clampS);
         }

         if (run.clampT != 0) {
            GL11.glTexParameteri(3553, 10243, run.clampT);
         }

         if (run.minFilter != 0) {
            GL11.glTexParameteri(3553, 10241, run.minFilter);
         }

         if (run.magFilter != 0) {
            GL11.glTexParameteri(3553, 10240, run.magFilter);
         }
      }

      if (this.forceDepthTest != Boolean.TRUE && !run.depthTest) {
         GL11.glDisable(2929);
      } else {
         GL11.glEnable(2929);
      }

      pzopt.OccludedOutline.vboMaterial(this.forceDepthTest == Boolean.TRUE || run.depthTest); // pzopt: screen-space overlays are not opaque world geometry
      this.useShaderProgram(run.shaderProgram);
      if (run.shaderProgram != null) {
         run.shaderProgram.setValue("userDepth", this.forceUserDepth == null ? run.userDepth : this.forceUserDepth);
      }

      GL11.glLineWidth(PZMath.min(run.lineWidth, 1.0F));
      if (run.format != this.formatUsedByVertexAttribArray) {
         this.setVertexAttribArrays(run.format);
      }
   }

   private void renderRun(VBORenderer.Run run) {
      if (run.vertexCount != 0) {
         GL12.glDrawRangeElements(run.mode, run.startVertex, run.startVertex + run.vertexCount, run.vertexCount, 5123, run.startIndex * 2L);
      }
   }

   void startNextRun() {
      VBORenderer.Run run = this.runs.isEmpty() ? this.runInProgress : this.runs.get(0);
      if (run != null) {
         if (run.vertexCount != 0) {
            this.startRun(run);
         }
      }
   }

   void renderNextRun() {
      if (this.runs.isEmpty()) {
         if (this.runInProgress != null) {
            this.renderRun(this.runInProgress);
         }
      } else {
         VBORenderer.Run run = this.runs.remove(0);
         this.renderRun(run);
         this.runPool.release(run);
      }
   }

   public void setDepthTest(boolean enable) {
      this.currentRun().depthTest = enable;
   }

   public void setDepthTestForAllRuns(Boolean enable) {
      this.forceDepthTest = enable;
   }

   public void setUserDepthForAllRuns(Float depth) {
      this.forceUserDepth = depth;
   }

   public void setUserDepth(float depth) {
      this.currentRun().userDepth = depth;
   }

   public void setLineWidth(float width) {
      this.currentRun().lineWidth = width;
   }

   public void setMode(int mode) {
      this.currentRun().mode = mode;
   }

   public void setShaderProgram(ShaderProgram shaderProgram) {
      this.currentRun().shaderProgram = shaderProgram;
   }

   public void setTextureID(TextureID textureID) {
      this.currentRun().textureId = textureID;
   }

   public void setMinMagFilters(int minFilter, int magFilter) {
      this.currentRun().minFilter = minFilter;
      this.currentRun().magFilter = magFilter;
   }

   public void setClampST(int s, int t) {
      this.currentRun().clampS = s;
      this.currentRun().clampT = t;
   }

   private void checkVertexBufferAlignment(int elementSize) {
      if (this.elements != null) {
         if (this.elements.position() % elementSize != 0) {
            int position = this.elements.position() + elementSize - this.elements.position() % elementSize;
            if (position >= this.elements.limit()) {
               this.flush();
            }
         }
      }
   }

   public void startRun(VertexFormat format) {
      if (this.elements != null) {
         this.checkVertexBufferAlignment(format.getStride());
      }

      this.setFormat(format);
      if (this.runInProgress != null) {
         this.runPool.release(this.runInProgress);
         this.runInProgress = null;
         throw new RuntimeException("forgot to call endRun()");
      }

      VBORenderer.Run run = ((VBORenderer.Run)this.runPool.alloc()).init();
      run.format = format;
      if (this.elements != null) {
         if (this.elements.position() % this.elementSize != 0) {
            int position = this.elements.position() + this.elementSize - this.elements.position() % this.elementSize;
            this.elements.position(position);
         }

         run.startVertex = this.elements.position() / this.elementSize;
         run.startIndex = this.indices.position() / 2;
      }

      VBOLinesShader shader = this.getShaderForFormat();
      run.shaderProgram = shader == null ? null : shader.getProgram();
      this.commands.cmdStartRun();
      this.runInProgress = run;
   }

   public void endRun() {
      if (this.runInProgress == null) {
         throw new RuntimeException("forgot to call startRun()");
      }

      this.runInProgress.ended = true;
      this.runs.add(this.runInProgress);
      this.commands.cmdRenderRun();
      this.commandsReady.adopt(this.commands);
      this.runInProgress = null;
   }

   public void cmdPushAndLoadMatrix(int mode, Matrix4f m) {
      this.commands.cmdPushAndLoadMatrix(mode, m);
   }

   public void cmdPushAndMultMatrix(int mode, Matrix4f m) {
      this.commands.cmdPushAndMultMatrix(mode, m);
   }

   public void cmdPopMatrix(int mode) {
      this.commands.cmdPopMatrix(mode);
   }

   public void cmdShader1f(String loc, float f1) {
      Uniform uniform = this.currentRun().shaderProgram.getUniform(loc, 5126);
      if (uniform != null) {
         this.commands.cmdShader1f(uniform.loc, f1);
      }
   }

   public void cmdShader2f(String loc, float f1, float f2) {
      Uniform uniform = this.currentRun().shaderProgram.getUniform(loc, 35664);
      if (uniform != null) {
         this.commands.cmdShader2f(uniform.loc, f1, f2);
      }
   }

   public void cmdShader2f(int loc, float f1, float f2) {
      this.commands.cmdShader2f(loc, f1, f2);
   }

   public void cmdShader3f(String loc, float f1, float f2, float f3) {
      Uniform uniform = this.currentRun().shaderProgram.getUniform(loc, 35664);
      if (uniform != null) {
         this.commands.cmdShader3f(uniform.loc, f1, f2, f3);
      }
   }

   public void cmdShader1f(int loc, float f1) {
      this.commands.cmdShader1f(loc, f1);
   }

   public void cmdShader4f(String loc, float f1, float f2, float f3, float f4) {
      Uniform uniform = this.currentRun().shaderProgram.getUniform(loc, 35666);
      if (uniform != null) {
         this.commands.cmdShader4f(uniform.loc, f1, f2, f3, f4);
      }
   }

   public void cmdShader4f(int loc, float f1, float f2, float f3, float f4) {
      this.commands.cmdShader4f(loc, f1, f2, f3, f4);
   }

   public void cmdUseProgram(ShaderProgram shaderProgram) {
      this.commands.cmdUseProgram(shaderProgram);
   }

   float cos(float f) {
      return (float)Math.cos(f);
   }

   float sin(float f) {
      return (float)Math.sin(f);
   }

   void normal3f(float x, float y, float z) {
      float mag = (float)Math.sqrt(x * x + y * y + z * z);
      if (mag > 1.0E-5F) {
         x /= mag;
         y /= mag;
         z /= mag;
      }

      this.setNormal(x, y, z);
   }

   public void addBox(float width, float height, float length, float r, float g, float b, float a, ShaderProgram shaderProgram) {
      this.startRun(this.formatPositionNormalColor);
      if (shaderProgram != null) {
         this.setShaderProgram(shaderProgram);
      }

      this.setMode(7);
      float x0 = width / 2.0F;
      float y0 = height / 2.0F;
      float z0 = length / 2.0F;
      float x1 = -width / 2.0F;
      float y1 = -height / 2.0F;
      float z1 = -length / 2.0F;
      this.reserve(4);
      this.addElement(x0, y0, z0, r, g, b, a);
      this.normal3f(0.0F, 0.0F, 1.0F);
      this.addElement(x1, y0, z0, r, g, b, a);
      this.normal3f(0.0F, 0.0F, 1.0F);
      this.addElement(x1, y1, z0, r, g, b, a);
      this.normal3f(0.0F, 0.0F, 1.0F);
      this.addElement(x0, y1, z0, r, g, b, a);
      this.normal3f(0.0F, 0.0F, 1.0F);
      this.reserve(4);
      this.addElement(x0, y0, z1, r, g, b, a);
      this.normal3f(0.0F, 0.0F, -1.0F);
      this.addElement(x1, y0, z1, r, g, b, a);
      this.normal3f(0.0F, 0.0F, -1.0F);
      this.addElement(x1, y1, z1, r, g, b, a);
      this.normal3f(0.0F, 0.0F, -1.0F);
      this.addElement(x0, y1, z1, r, g, b, a);
      this.normal3f(0.0F, 0.0F, -1.0F);
      this.reserve(4);
      this.addElement(x0, y0, z0, r, g, b, a);
      this.normal3f(1.0F, 0.0F, 0.0F);
      this.addElement(x0, y1, z0, r, g, b, a);
      this.normal3f(1.0F, 0.0F, 0.0F);
      this.addElement(x0, y1, z1, r, g, b, a);
      this.normal3f(1.0F, 0.0F, 0.0F);
      this.addElement(x0, y0, z1, r, g, b, a);
      this.normal3f(1.0F, 0.0F, 0.0F);
      this.reserve(4);
      this.addElement(x1, y0, z0, r, g, b, a);
      this.normal3f(-1.0F, 0.0F, 0.0F);
      this.addElement(x1, y1, z0, r, g, b, a);
      this.normal3f(-1.0F, 0.0F, 0.0F);
      this.addElement(x1, y1, z1, r, g, b, a);
      this.normal3f(-1.0F, 0.0F, 0.0F);
      this.addElement(x1, y0, z1, r, g, b, a);
      this.normal3f(-1.0F, 0.0F, 0.0F);
      boolean bBottom = false;
      this.endRun();
   }

   public void addCylinder_Fill(float baseRadius, float topRadius, float height, int slices, int stacks, float r1, float g1, float b1, float a1) {
      this.addCylinder(100012, 100020, baseRadius, topRadius, height, slices, stacks, r1, g1, b1, a1, null);
   }

   public void addCylinder_Line(float baseRadius, float topRadius, float height, int slices, int stacks, float r1, float g1, float b1, float a1) {
      this.addCylinder(100011, 100020, baseRadius, topRadius, height, slices, stacks, r1, g1, b1, a1, null);
   }

   public void addCylinder_Fill(
      float baseRadius, float topRadius, float height, int slices, int stacks, float r1, float g1, float b1, float a1, ShaderProgram shaderProgram
   ) {
      this.addCylinder(100012, 100020, baseRadius, topRadius, height, slices, stacks, r1, g1, b1, a1, shaderProgram);
   }

   public void addCylinder_Line(
      float baseRadius, float topRadius, float height, int slices, int stacks, float r1, float g1, float b1, float a1, ShaderProgram shaderProgram
   ) {
      this.addCylinder(100011, 100020, baseRadius, topRadius, height, slices, stacks, r1, g1, b1, a1, shaderProgram);
   }

   public void addCylinder(
      int drawStyle,
      int orientation,
      float baseRadius,
      float topRadius,
      float height,
      int slices,
      int stacks,
      float r1,
      float g1,
      float b1,
      float a1,
      ShaderProgram shaderProgram
   ) {
      float nsign;
      if (orientation == 100021) {
         nsign = -1.0F;
      } else {
         nsign = 1.0F;
      }

      float da = (float) (Math.PI * 2) / slices;
      float dr = (topRadius - baseRadius) / stacks;
      float dz = height / stacks;
      float nz = (baseRadius - topRadius) / height;
      if (drawStyle == 100010) {
         this.startRun(this.formatPositionColor);
         this.setMode(0);

         for (int i = 0; i < slices; i++) {
            float z = 0.0F;
            float r = baseRadius;

            for (int j = 0; j <= stacks; j++) {
               float x = this.cos(i * da);
               float y = this.sin(i * da);
               this.addElement();
               this.normal3f(x * nsign, y * nsign, nz * nsign);
               this.setVertex(x * r, y * r, z);
               z += dz;
               r += dr;
            }
         }

         this.endRun();
      } else if (drawStyle != 100011 && drawStyle != 100013) {
         if (drawStyle == 100012) {
            float ds = 1.0F / slices;
            float dt = 1.0F / stacks;
            float t = 0.0F;
            float z = 0.0F;
            float r = baseRadius;

            for (int j = 0; j < stacks; j++) {
               float s = 0.0F;
               this.startRun(this.formatPositionNormalColor);
               if (shaderProgram != null) {
                  this.setShaderProgram(shaderProgram);
               }

               this.setMode(8);

               for (int i = 0; i <= slices; i++) {
                  float x;
                  float y;
                  if (i == slices) {
                     x = this.sin(0.0F);
                     y = this.cos(0.0F);
                  } else {
                     x = this.sin(i * da);
                     y = this.cos(i * da);
                  }

                  this.reserve(2);
                  if (nsign == 1.0F) {
                     this.addElement();
                     this.normal3f(x * nsign, y * nsign, nz * nsign);
                     this.setUV1(s, t);
                     this.setVertex(x * r, y * r, z);
                     this.setColor(r1, g1, b1, a1);
                     this.addElement();
                     this.normal3f(x * nsign, y * nsign, nz * nsign);
                     this.setUV1(s, t + dt);
                     this.setVertex(x * (r + dr), y * (r + dr), z + dz);
                     this.setColor(r1, g1, b1, a1);
                  } else {
                     this.addElement();
                     this.normal3f(x * nsign, y * nsign, nz * nsign);
                     this.setUV1(s, t);
                     this.setVertex(x * r, y * r, z);
                     this.setColor(r1, g1, b1, a1);
                     this.addElement();
                     this.normal3f(x * nsign, y * nsign, nz * nsign);
                     this.setUV1(s, t + dt);
                     this.setVertex(x * (r + dr), y * (r + dr), z + dz);
                     this.setColor(r1, g1, b1, a1);
                  }

                  s += ds;
               }

               this.endRun();
               r += dr;
               t += dt;
               z += dz;
            }
         }
      } else {
         if (drawStyle == 100011) {
            float z = 0.0F;
            float r = baseRadius;

            for (int j = 0; j <= stacks; j++) {
               this.startRun(this.formatPositionNormalColor);
               this.setMode(2);

               for (int i = 0; i < slices; i++) {
                  float x = this.cos(i * da);
                  float y = this.sin(i * da);
                  this.addElement();
                  this.normal3f(x * nsign, y * nsign, nz * nsign);
                  this.setVertex(x * r, y * r, z);
                  this.setColor(r1, g1, b1, a1);
               }

               this.endRun();
               z += dz;
               r += dr;
            }
         } else if (baseRadius != 0.0) {
            this.startRun(this.formatPositionNormalColor);
            this.setMode(2);

            for (int i = 0; i < slices; i++) {
               float x = this.cos(i * da);
               float y = this.sin(i * da);
               this.addElement();
               this.normal3f(x * nsign, y * nsign, nz * nsign);
               this.setVertex(x * baseRadius, y * baseRadius, 0.0F);
               this.setColor(r1, g1, b1, a1);
            }

            this.endRun();
            this.startRun(this.formatPositionNormalColor);
            this.setMode(2);

            for (int var43 = 0; var43 < slices; var43++) {
               float x = this.cos(var43 * da);
               float y = this.sin(var43 * da);
               this.addElement();
               this.normal3f(x * nsign, y * nsign, nz * nsign);
               this.setVertex(x * topRadius, y * topRadius, height);
               this.setColor(r1, g1, b1, a1);
            }

            this.endRun();
         }

         this.startRun(this.formatPositionNormalColor);
         this.setMode(1);

         for (int i = 0; i < slices; i++) {
            float x = this.cos(i * da);
            float y = this.sin(i * da);
            this.addElement();
            this.setNormal(x * nsign, y * nsign, nz * nsign);
            this.setVertex(x * baseRadius, y * baseRadius, 0.0F);
            this.setColor(r1, g1, b1, a1);
            this.addElement();
            this.setNormal(x * nsign, y * nsign, nz * nsign);
            this.setVertex(x * topRadius, y * topRadius, height);
            this.setColor(r1, g1, b1, a1);
         }

         this.endRun();
      }
   }

   public void addDisk_Fill(float innerRadius, float outerRadius, int slices, int loops, TextureID textureID, float red, float green, float blue, float alpha) {
      this.addDisk(100012, 100020, innerRadius, outerRadius, slices, loops, textureID, red, green, blue, alpha);
   }

   public void addDisk(
      int drawStyle,
      int orientation,
      float innerRadius,
      float outerRadius,
      int slices,
      int loops,
      TextureID textureID,
      float red,
      float green,
      float blue,
      float alpha
   ) {
      float da = (float) (Math.PI * 2) / slices;
      float dr = (outerRadius - innerRadius) / loops;
      float z = 0.0F;
      switch (drawStyle) {
         case 100012:
            float dtc = 2.0F * outerRadius;
            float r1 = innerRadius;

            for (int l = 0; l < loops; l++) {
               this.startRun(this.formatPositionColorUv);
               this.setTextureID(textureID);
               float r2 = r1 + dr;
               if (orientation == 100020) {
                  this.setMode(8);

                  for (int s = 0; s <= slices; s++) {
                     float a;
                     if (s == slices) {
                        a = 0.0F;
                     } else {
                        a = s * da;
                     }

                     float sa = this.sin(a);
                     float ca = this.cos(a);
                     this.addElement(r2 * sa, r2 * ca, 0.0F, 0.5F + sa * r2 / dtc, 0.5F + ca * r2 / dtc, red, green, blue, alpha);
                     this.addElement(r1 * sa, r1 * ca, 0.0F, 0.5F + sa * r1 / dtc, 0.5F + ca * r1 / dtc, red, green, blue, alpha);
                  }
               } else {
                  this.setMode(8);

                  for (int s = slices; s >= 0; s--) {
                     float a;
                     if (s == slices) {
                        a = 0.0F;
                     } else {
                        a = s * da;
                     }

                     float sa = this.sin(a);
                     float ca = this.cos(a);
                     this.addElement(r2 * sa, r2 * ca, 0.0F, 0.5F - sa * r2 / dtc, 0.5F + ca * r2 / dtc, red, green, blue, alpha);
                     this.addElement(r1 * sa, r1 * ca, 0.0F, 0.5F - sa * r1 / dtc, 0.5F + ca * r1 / dtc, red, green, blue, alpha);
                  }
               }

               r1 = r2;
               this.endRun();
            }
      }
   }

   public void addTorus(double r, double c, int rSeg, int cSeg, float r1, float g1, float b1, Ray cameraRay) {
      GL11.glFrontFace(2304);
      double pi = Math.PI;
      double tau = Math.PI * 2;
      Vector3f spoke = BaseVehicle.allocVector3f();

      for (int i = 0; i < rSeg; i++) {
         this.startRun(this.formatPositionColor);
         this.setMode(8);

         for (int j = 0; j <= cSeg; j++) {
            this.reserve(2);

            for (int k = 0; k <= 1; k++) {
               double s = (i + k) % rSeg + 0.5;
               double t = j % (cSeg + 1);
               double x = (c + r * Math.cos(s * (Math.PI * 2) / rSeg)) * Math.cos(t * (Math.PI * 2) / cSeg);
               double y = (c + r * Math.cos(s * (Math.PI * 2) / rSeg)) * Math.sin(t * (Math.PI * 2) / cSeg);
               double z = r * Math.sin(s * (Math.PI * 2) / rSeg);
               spoke.set(x, y, z).normalize();
               float dot = spoke.dot(cameraRay.direction);
               this.addElement();
               if (dot < 0.1F) {
                  this.setColor(r1, g1, b1, 1.0F);
               } else {
                  this.setColor(r1 / 2.0F, g1 / 2.0F, b1 / 2.0F, 0.25F);
               }

               this.setVertex(2.0F * (float)x, 2.0F * (float)y, 2.0F * (float)z);
            }
         }

         this.endRun();
      }

      this.flush();
      BaseVehicle.releaseVector3f(spoke);
      GL11.glFrontFace(2305);
   }

   private static final class Run {
      boolean ended;
      VertexFormat format;
      int startVertex;
      int startIndex;
      int vertexCount;
      int mode = 1;
      float lineWidth = 1.0F;
      ShaderProgram shaderProgram;
      TextureID textureId;
      int clampS;
      int clampT;
      int minFilter;
      int magFilter;
      boolean depthTest;
      float userDepth;

      VBORenderer.Run init() {
         this.ended = false;
         this.format = null;
         this.startVertex = 0;
         this.startIndex = 0;
         this.vertexCount = 0;
         this.mode = 1;
         this.lineWidth = 1.0F;
         this.shaderProgram = null;
         this.textureId = null;
         this.clampS = 0;
         this.clampT = 0;
         this.minFilter = 0;
         this.magFilter = 0;
         this.depthTest = false;
         this.userDepth = 0.0F;
         return this;
      }
   }
}
