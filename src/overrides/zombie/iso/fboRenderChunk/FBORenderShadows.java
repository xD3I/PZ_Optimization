package zombie.iso.fboRenderChunk;

import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.VBORenderer;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw.GenericDrawer;
import zombie.debug.DebugOptions;
import zombie.debug.LineDrawer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.Vector2;
import zombie.popman.ObjectPool;

public final class FBORenderShadows {
   private static FBORenderShadows instance;
   private final ObjectPool<FBORenderShadows.Drawer> drawerPool = new ObjectPool(FBORenderShadows.Drawer::new, "FBORenderShadows.drawerPool");
   private final ObjectPool<FBORenderShadows.Shadow> shadowPool = new ObjectPool(FBORenderShadows.Shadow::new, "FBORenderShadows.shadowPool");
   private final ArrayList<FBORenderShadows.Shadow> shadows = new ArrayList<>();
   private final Vector3f tempVector3f1 = new Vector3f();
   private final Vector3f tempVector3f2 = new Vector3f();
   private Texture dropShadow;

   public static FBORenderShadows getInstance() {
      if (instance == null) {
         instance = new FBORenderShadows();
      }

      return instance;
   }

   private FBORenderShadows() {
   }

   public void clear() {
      this.shadowPool.releaseAll(this.shadows);
      this.shadows.clear();
      this.dropShadow = Texture.getSharedTexture("media/textures/NewShadow.png");
   }

   public void addShadow(float x, float y, float z, Vector3f forward, float w, float fm, float bm, float r, float g, float b, float a, boolean bAnimal) {
      if (this.dropShadow != null && this.dropShadow.isReady()) {
         if (!bAnimal) {
            w = Math.max(w, 0.65F);
            fm = Math.max(fm, 0.65F);
            bm = Math.max(bm, 0.65F);
         }

         Vector3f forwardN = this.tempVector3f1.set(forward);
         forwardN.normalize();
         Vector3f perp = forwardN.cross(0.0F, 0.0F, 1.0F, this.tempVector3f2);
         perp.x *= w;
         perp.y *= w;
         float fx = x + forward.x * fm;
         float fy = y + forward.y * fm;
         float bx = x - forward.x * bm;
         float by = y - forward.y * bm;
         float fx1 = fx - perp.x;
         float fx2 = fx + perp.x;
         float bx1 = bx - perp.x;
         float bx2 = bx + perp.x;
         float by1 = by - perp.y;
         float by2 = by + perp.y;
         float fy1 = fy - perp.y;
         float fy2 = fy + perp.y;
         float shadowAlpha = a;
         shadowAlpha *= (r + g + b) / 3.0F;
         shadowAlpha *= 0.66F;
         this.addShadow(x, y, z, fx1, fy1, fx2, fy2, bx2, by2, bx1, by1, r, g, b, shadowAlpha, this.dropShadow, false);
         if (DebugOptions.instance.isoSprite.dropShadowEdges.getValue()) {
            LineDrawer.addLine(fx1, fy1, z, fx2, fy2, z, 1, 1, 1, null);
            LineDrawer.addLine(fx2, fy2, z, bx2, by2, z, 1, 1, 1, null);
            LineDrawer.addLine(bx2, by2, z, bx1, by1, z, 1, 1, 1, null);
            LineDrawer.addLine(bx1, by1, z, fx1, fy1, z, 1, 1, 1, null);
         }
      }
   }

   public void addShadow(
      float ox,
      float oy,
      float oz,
      float x0,
      float y0,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      float r,
      float g,
      float b,
      float a,
      Texture texture,
      boolean bVehicle
   ) {
      a *= pzopt.CapsuleShadow.stockShadowScale(); // pzopt: sunShadows, the stock blob fades where the caster casts a real sun shadow (1 otherwise)
      if (!(a <= 0.0F)) {
         FBORenderShadows.Shadow shadow = (FBORenderShadows.Shadow)this.shadowPool.alloc();
         shadow.ox = ox;
         shadow.oy = oy;
         shadow.oz = oz;
         shadow.x0 = x0;
         shadow.y0 = y0;
         shadow.x1 = x1;
         shadow.y1 = y1;
         shadow.x2 = x2;
         shadow.y2 = y2;
         shadow.x3 = x3;
         shadow.y3 = y3;
         this.calculateSlopeAngles(shadow);
         float playerX = IsoCamera.frameState.camCharacterX;
         float playerY = IsoCamera.frameState.camCharacterY;
         float spriteShadowOffset = 5.0E-5F;
         float depthAdjust = ox + oy < playerX + playerY ? -1.4E-4F : -1.4999999E-4F;
         shadow.depth0 = IsoDepthHelper.getSquareDepthData(
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                  x0,
                  y0,
                  this.calculateApparentZ(shadow, x0, y0, oz)
               )
               .depthStart
            + depthAdjust;
         shadow.depth1 = IsoDepthHelper.getSquareDepthData(
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                  x1,
                  y1,
                  this.calculateApparentZ(shadow, x1, y1, oz)
               )
               .depthStart
            + depthAdjust;
         shadow.depth2 = IsoDepthHelper.getSquareDepthData(
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                  x2,
                  y2,
                  this.calculateApparentZ(shadow, x2, y2, oz)
               )
               .depthStart
            + depthAdjust;
         shadow.depth3 = IsoDepthHelper.getSquareDepthData(
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                  PZMath.fastfloor(IsoCamera.frameState.camCharacterY),
                  x3,
                  y3,
                  this.calculateApparentZ(shadow, x3, y3, oz)
               )
               .depthStart
            + depthAdjust;
         shadow.r = r;
         shadow.g = g;
         shadow.b = b;
         shadow.a = a;
         shadow.texture = texture;
         shadow.vehicle = bVehicle;
         this.shadows.add(shadow);
      }
   }

   void calculateSlopeAngles(FBORenderShadows.Shadow shadow) {
      shadow.slopeAngleX = shadow.slopeAngleY = 0.0F;
      IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(shadow.ox, shadow.oy, shadow.oz);
      if (square != null) {
         IsoDirections dir = square.getSlopedSurfaceDirection();
         if (dir != null) {
            float heightMin = square.getSlopedSurfaceHeightMin();
            float heightMax = square.getSlopedSurfaceHeightMax();
            switch (dir) {
               case N:
                  shadow.slopeAngleX = Vector2.getDirection(1.0F, (heightMax - heightMin) * 2.44949F);
                  break;
               case S:
                  shadow.slopeAngleX = Vector2.getDirection(1.0F, (heightMin - heightMax) * 2.44949F);
                  break;
               case W:
                  shadow.slopeAngleY = Vector2.getDirection(1.0F, (heightMin - heightMax) * 2.44949F);
                  break;
               case E:
                  shadow.slopeAngleY = Vector2.getDirection(1.0F, (heightMax - heightMin) * 2.44949F);
            }
         }
      }
   }

   float calculateApparentZ(FBORenderShadows.Shadow shadow, float x, float y, float z) {
      if (shadow.slopeAngleX == 0.0F && shadow.slopeAngleY == 0.0F) {
         return z;
      }

      IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(shadow.ox, shadow.oy, shadow.oz);
      float slopeHeightMin = square.getSlopedSurfaceHeightMin();
      float slopeHeightMax = square.getSlopedSurfaceHeightMax();
      float slopeDelta = slopeHeightMax - slopeHeightMin;
      float dx = (x - (square.x - 5)) % 10.0F;
      float dy = (y - (square.y - 5)) % 10.0F;

      float z2 = switch (square.getSlopedSurfaceDirection()) {
         case N -> PZMath.lerp(slopeHeightMin - 4.0F * slopeDelta, slopeHeightMax + 5.0F * slopeDelta, 1.0F - dy / 10.0F);
         case S -> PZMath.lerp(slopeHeightMin - 5.0F * slopeDelta, slopeHeightMax + 4.0F * slopeDelta, dy / 10.0F);
         case W -> PZMath.lerp(slopeHeightMin - 4.0F * slopeDelta, slopeHeightMax + 5.0F * slopeDelta, 1.0F - dx / 10.0F);
         case E -> PZMath.lerp(slopeHeightMin - 5.0F * slopeDelta, slopeHeightMax + 4.0F * slopeDelta, dx / 10.0F);
         default -> -1.0F;
      };
      return z2 == -1.0F ? z : PZMath.fastfloor(z) + z2;
   }

   public void renderMain(int z) {
      FBORenderShadows.Drawer drawer = (FBORenderShadows.Drawer)this.drawerPool.alloc();
      drawer.shadows.clear();

      for (int i = 0; i < this.shadows.size(); i++) {
         FBORenderShadows.Shadow shadow = this.shadows.get(i);
         if (PZMath.fastfloor(shadow.oz) == z) {
            drawer.shadows.add(shadow);
            this.shadows.remove(i--);
         }
      }

      if (drawer.shadows.isEmpty()) {
         this.drawerPool.release(drawer);
      } else {
         SpriteRenderer.instance.drawGeneric(drawer);
      }
   }

   public void endRender() {
      this.shadows.clear();
   }

   private void render(ArrayList<FBORenderShadows.Shadow> shadows) {
      SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
      PlayerCamera camera = renderState.playerCamera[renderState.playerIndex];
      float rcx = camera.rightClickX;
      float rcy = camera.rightClickY;
      float tox = camera.getTOffX();
      float toy = camera.getTOffY();
      float defx = camera.deferedX;
      float defy = camera.deferedY;
      float playerX = (Float)Core.getInstance().floatParamMap.get(0);
      float playerY = (Float)Core.getInstance().floatParamMap.get(1);
      float playerZ = (Float)Core.getInstance().floatParamMap.get(2);
      float cx = playerX - camera.XToIso(-tox - rcx, -toy - rcy, 0.0F);
      float cy = playerY - camera.YToIso(-tox - rcx, -toy - rcy, 0.0F);
      cx += defx;
      cy += defy;
      double screenWidth = camera.offscreenWidth / 1920.0F;
      double screenHeight = camera.offscreenHeight / 1920.0F;
      Matrix4f projection = Core.getInstance().projectionMatrixStack.alloc();
      projection.setOrtho(-((float)screenWidth) / 2.0F, (float)screenWidth / 2.0F, -((float)screenHeight) / 2.0F, (float)screenHeight / 2.0F, -10.0F, 10.0F);
      Core.getInstance().projectionMatrixStack.push(projection);
      VBORenderer vbor = VBORenderer.getInstance();
      GL11.glEnable(2929);
      GL11.glDepthFunc(515);
      GL11.glDepthMask(false);
      IndieGL.glDefaultBlendFuncA();

      for (int i = 0; i < shadows.size(); i++) {
         FBORenderShadows.Shadow shadow = shadows.get(i);
         float u0 = 0.0F;
         float v0 = 0.0F;
         float u1 = 1.0F;
         float v1 = 0.0F;
         float u2 = 1.0F;
         float v2 = 1.0F;
         float u3 = 0.0F;
         float v3 = 1.0F;
         Matrix4f modelView = Core.getInstance().modelViewMatrixStack.alloc();
         modelView.scaling(Core.scale);
         modelView.scale(1.0F);
         modelView.rotate((float) (Math.PI / 6), 1.0F, 0.0F, 0.0F);
         modelView.rotate((float) (Math.PI * 3.0 / 4.0), 0.0F, 1.0F, 0.0F);
         double difX = shadow.ox - cx;
         double difY = shadow.oy - cy;
         double difZ = (shadow.oz - playerZ) * 2.44949F;
         modelView.translate(-((float)difX), (float)difZ, -((float)difY));
         modelView.scale(-1.0F, 1.0F, -1.0F);
         modelView.translate(0.0F, -0.71999997F, 0.0F);
         if (shadow.slopeAngleX != 0.0F) {
            modelView.rotate(shadow.slopeAngleX, 1.0F, 0.0F, 0.0F);
         }

         if (shadow.slopeAngleY != 0.0F) {
            modelView.rotate(shadow.slopeAngleY, 0.0F, 0.0F, 1.0F);
         }

         vbor.cmdPushAndLoadMatrix(5888, modelView);
         Core.getInstance().modelViewMatrixStack.push(modelView);
         float depthBufferValue = VertexBufferObject.getDepthValueAt(0.0F, 0.0F, 0.0F);
         Core.getInstance().modelViewMatrixStack.pop();
         vbor.startRun(VBORenderer.getInstance().formatPositionColorUvDepth);
         vbor.setMode(7);
         vbor.setTextureID(shadow.texture.getTextureId());
         vbor.setDepthTest(true);
         vbor.addQuadDepth(
            shadow.x0 - shadow.ox,
            0.0F,
            shadow.y0 - shadow.oy,
            0.0F,
            0.0F,
            shadow.depth0,
            shadow.x1 - shadow.ox,
            0.0F,
            shadow.y1 - shadow.oy,
            1.0F,
            0.0F,
            shadow.depth1,
            shadow.x2 - shadow.ox,
            0.0F,
            shadow.y2 - shadow.oy,
            1.0F,
            1.0F,
            shadow.depth2,
            shadow.x3 - shadow.ox,
            0.0F,
            shadow.y3 - shadow.oy,
            0.0F,
            1.0F,
            shadow.depth3,
            shadow.r,
            shadow.g,
            shadow.b,
            shadow.a
         );
         vbor.endRun();
         vbor.cmdPopMatrix(5888);
      }

      vbor.flush();
      Core.getInstance().projectionMatrixStack.pop();
      GLStateRenderThread.restore();
   }

   static final class Drawer extends GenericDrawer {
      private final ArrayList<FBORenderShadows.Shadow> shadows = new ArrayList<>();

      public void render() {
         FBORenderShadows.getInstance().render(this.shadows);
      }

      public void postRender() {
         FBORenderShadows.instance.shadowPool.releaseAll(this.shadows);
         this.shadows.clear();
         FBORenderShadows.instance.drawerPool.release(this);
      }
   }

   static final class Shadow {
      float ox;
      float oy;
      float oz;
      float x0;
      float y0;
      float x1;
      float y1;
      float x2;
      float y2;
      float x3;
      float y3;
      float slopeAngleX;
      float slopeAngleY;
      float depth0;
      float depth1;
      float depth2;
      float depth3;
      float r;
      float g;
      float b;
      float a;
      Texture texture;
      boolean vehicle;
   }
}
