package zombie.ui;

import java.util.ArrayList;
import java.util.Vector;
import pzopt.SubframeInput;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaUtil;
import zombie.IndieGL;
import zombie.UsedFromLua;
import zombie.Lua.LuaManager;
import zombie.core.BoxedStaticValues;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.debug.DebugOptions;
import zombie.entity.components.fluids.FluidContainer;
import zombie.input.Mouse;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoObject.OutlineShader;
import zombie.scripting.objects.Item;
import zombie.util.list.PZArrayUtil;

@UsedFromLua
public class UIElement implements UIElementInterface {
   static final Color tempcol = new Color(0, 0, 0, 0);
   static final ArrayList<UIElement> toAdd = new ArrayList<>(0);
   static Texture white;
   static int stencilLevel;
   public boolean capture;
   public boolean ignoreLossControl;
   public String clickedValue;
   public final ArrayList<UIElement> controls = new ArrayList<>();
   public boolean defaultDraw = true;
   public boolean followGameWorld;
   private int renderThisPlayerOnly = -1;
   public float height = 256.0F;
   public UIElement parent;
   public boolean visible = true;
   public float width = 256.0F;
   public double x;
   public double y;
   public KahluaTable table;
   public boolean alwaysBack;
   public boolean scrollChildren;
   public boolean scrollWithParent = true;
   private boolean renderClippedChildren = true;
   public boolean anchorTop = true;
   public boolean anchorLeft = true;
   public boolean anchorRight;
   public boolean anchorBottom;
   public int playerContext = -1;
   public boolean alwaysOnTop;
   public int maxDrawHeight = -1;
   Double yScroll = 0.0;
   Double xScroll = 0.0;
   int scrollHeight;
   double lastheight = -1.0;
   double lastwidth = -1.0;
   boolean resizeDirty;
   boolean enabled = true;
   private final ArrayList<UIElement> toTop = new ArrayList<>(0);
   private boolean consumeMouseEvents = true;
   private long leftDownTime;
   private boolean clicked;
   private double clickX;
   private double clickY;
   private String uiname = "";
   private boolean wantKeyEvents;
   private boolean wantExtraMouseEvents;
   private boolean forceCursorVisible;
   private static Texture circleTexture;
   private boolean shaderStarted;

   public UIElement() {
   }

   public UIElement(KahluaTable table) {
      this.table = table;
   }

   public Double getMaxDrawHeight() {
      return BoxedStaticValues.toDouble(this.maxDrawHeight);
   }

   public void setMaxDrawHeight(double height) {
      this.maxDrawHeight = (int)height;
   }

   public void clearMaxDrawHeight() {
      this.maxDrawHeight = -1;
   }

   public Double getXScroll() {
      return this.xScroll;
   }

   public void setXScroll(double x) {
      if (this.xScroll == null || this.xScroll != x) {
         this.xScroll = x;
         this.invokeOnScrollPosChanged();
      }
   }

   public Double getYScroll() {
      return this.yScroll;
   }

   public void setYScroll(double y) {
      if (this.yScroll == null || this.yScroll != y) {
         this.yScroll = BoxedStaticValues.toDouble(y);
         this.invokeOnScrollPosChanged();
      }
   }

   private void invokeOnScrollPosChanged() {
      if (UIManager.tableget(this.table, "onScrollPosChanged") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(), UIManager.tableget(this.table, "onScrollPosChanged"), this.table, this.getXScroll(), this.getYScroll()
            );
      }
   }

   public void setAlwaysOnTop(boolean b) {
      this.alwaysOnTop = b;
   }

   public boolean isAlwaysOnTop() {
      return this.alwaysOnTop;
   }

   public void backMost() {
      this.alwaysBack = true;
   }

   public boolean isBackMost() {
      return this.alwaysBack;
   }

   public void AddChild(UIElement el) {
      this.getControls().add(el);
      el.setParent(this);
   }

   public void RemoveChild(UIElement el) {
      this.getControls().remove(el);
      el.setParent(null);
   }

   public Double getScrollHeight() {
      return BoxedStaticValues.toDouble(this.scrollHeight);
   }

   public void setScrollHeight(double h) {
      this.scrollHeight = (int)h;
   }

   public boolean isConsumeMouseEvents() {
      return this.consumeMouseEvents;
   }

   public void setConsumeMouseEvents(boolean bConsume) {
      this.consumeMouseEvents = bConsume;
   }

   public void ClearChildren() {
      this.getControls().clear();
   }

   public void ButtonClicked(String name) {
      this.setClickedValue(name);
   }

   public void DrawText(UIFont font, String text, double x, double y, double zoom, double r, double g, double b, double alpha) {
      TextManager.instance.DrawString(font, x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, (float)zoom, text, r, g, b, alpha);
   }

   public void DrawText(String text, double x, double y, double r, double g, double b, double alpha) {
      TextManager.instance.DrawString(x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void DrawText(String text, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      TextManager.instance.DrawString(x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void DrawText(UIFont font, String text, double x, double y, double r, double g, double b, double alpha) {
      if (text != null) {
         int top = (int)(y + this.getAbsoluteY() + this.yScroll);
         if (top + 100 >= 0 && top <= 4096) {
            TextManager.instance.DrawString(font, x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
         }
      }
   }

   public void DrawTextUntrimmed(UIFont font, String text, double x, double y, double r, double g, double b, double alpha) {
      if (text != null) {
         TextManager.instance.DrawStringUntrimmed(font, x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
      }
   }

   public void DrawTextCentre(String text, double x, double y, double r, double g, double b, double alpha) {
      TextManager.instance.DrawStringCentre(x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void DrawTextCentre(UIFont font, String text, double x, double y, double r, double g, double b, double alpha) {
      TextManager.instance.DrawStringCentre(font, x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void DrawTextRight(String text, double x, double y, double r, double g, double b, double alpha) {
      TextManager.instance.DrawStringRight(x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void DrawTextRight(UIFont font, String text, double x, double y, double r, double g, double b, double alpha) {
      TextManager.instance.DrawStringRight(font, x + this.getAbsoluteX() + this.xScroll, y + this.getAbsoluteY() + this.yScroll, text, r, g, b, alpha);
   }

   public void drawTextWithBackground(
      UIFont font,
      String text,
      double x,
      double y,
      double r,
      double g,
      double b,
      double a,
      double padX,
      double padY,
      double bgR,
      double bgG,
      double bgB,
      double bgA
   ) {
      int fontHgt = TextManager.instance.getFontHeight(font);
      int textWidth = TextManager.instance.MeasureStringX(font, text);
      this.DrawTextureScaledColor(null, x - padX, y - padY, textWidth + padX * 2.0, fontHgt + padY * 2.0, bgR, bgG, bgB, bgA);
      this.DrawText(font, text, x, y, r, g, b, a);
   }

   public void DrawTextureAngle(Texture tex, double centerX, double centerY, double angle, double r, double g, double b, double a) {
      if (this.isVisible()) {
         float dx = tex.getWidth() / 2;
         float dy = tex.getHeight() / 2;
         double radian = Math.toRadians(180.0 + angle);
         double xCos = Math.cos(radian) * dx;
         double xSin = Math.sin(radian) * dx;
         double yCos = Math.cos(radian) * dy;
         double ySin = Math.sin(radian) * dy;
         double x1 = xCos - ySin;
         double y1 = yCos + xSin;
         double x2 = -xCos - ySin;
         double y2 = yCos - xSin;
         double x3 = -xCos + ySin;
         double y3 = -yCos - xSin;
         double x4 = xCos + ySin;
         double y4 = -yCos + xSin;
         x1 += this.getAbsoluteX() + centerX;
         y1 += this.getAbsoluteY() + centerY;
         x2 += this.getAbsoluteX() + centerX;
         y2 += this.getAbsoluteY() + centerY;
         x3 += this.getAbsoluteX() + centerX;
         y3 += this.getAbsoluteY() + centerY;
         x4 += this.getAbsoluteX() + centerX;
         y4 += this.getAbsoluteY() + centerY;
         SpriteRenderer.instance
            .render(
               tex,
               x1,
               y1,
               x2,
               y2,
               x3,
               y3,
               x4,
               y4,
               (float)r,
               (float)g,
               (float)b,
               (float)a,
               (float)r,
               (float)g,
               (float)b,
               (float)a,
               (float)r,
               (float)g,
               (float)b,
               (float)a,
               (float)r,
               (float)g,
               (float)b,
               (float)a,
               null
            );
      }
   }

   public void DrawTextureAngle(Texture tex, double centerX, double centerY, double angle) {
      this.DrawTextureAngle(tex, centerX, centerY, angle, 1.0, 1.0, 1.0, 1.0);
   }

   public void DrawTexture(
      Texture tex, double tlx, double tly, double trx, double try2, double brx, double bry, double blx, double bly, double r, double g, double b, double a
   ) {
      SpriteRenderer.instance
         .render(
            tex,
            tlx,
            tly,
            trx,
            try2,
            brx,
            bry,
            blx,
            bly,
            (float)r,
            (float)g,
            (float)b,
            (float)a,
            (float)r,
            (float)g,
            (float)b,
            (float)a,
            (float)r,
            (float)g,
            (float)b,
            (float)a,
            (float)r,
            (float)g,
            (float)b,
            (float)a,
            null
         );
   }

   public void DrawTexture(Texture tex, double x, double y, double alpha) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         double getHeight = 0.0;
         double width = 0.0;
         double height = 0.0;
         if (tex != null) {
            dx += tex.offsetX;
            dy += tex.offsetY;
            getHeight = tex.getHeight();
            width = tex.getWidth();
            height = tex.getHeight();
         }

         int top = (int)(dy + this.yScroll);
         if (!(top + getHeight < 0.0) && top <= 4096) {
            SpriteRenderer.instance
               .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, 1.0F, 1.0F, 1.0F, (float)alpha, null);
         }
      }
   }

   public void DrawTextureCol(Texture tex, double x, double y, Color col) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         int wid = 0;
         int hei = 0;
         if (tex != null) {
            dx += tex.offsetX;
            dy += tex.offsetY;
            wid = tex.getWidth();
            hei = tex.getHeight();
         }

         int top = (int)(dy + this.yScroll);
         if (top + hei >= 0 && top <= 4096) {
            SpriteRenderer.instance.renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), wid, hei, col.r, col.g, col.b, col.a, null);
         }
      }
   }

   public void DrawTextureScaled(Texture tex, double x, double y, double width, double height, double alpha) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         SpriteRenderer.instance
            .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, 1.0F, 1.0F, 1.0F, (float)alpha, null);
      }
   }

   public void DrawTextureScaledUniform(Texture tex, double x, double y, double scale, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         dx += tex.offsetX * scale;
         dy += tex.offsetY * scale;
         SpriteRenderer.instance
            .renderi(
               tex,
               (int)(dx + this.xScroll),
               (int)(dy + this.yScroll),
               (int)(tex.getWidth() * scale),
               (int)(tex.getHeight() * scale),
               (float)r,
               (float)g,
               (float)b,
               (float)alpha,
               null
            );
      }
   }

   public void DrawTextureScaledAspect(Texture tex, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         if (tex.getWidth() > 0 && tex.getHeight() > 0 && width > 0.0 && height > 0.0) {
            double ratio = Math.min(width / tex.getWidthOrig(), height / tex.getHeightOrig());
            double oldWidth = width;
            double oldHeight = height;
            width = tex.getWidth() * ratio;
            height = tex.getHeight() * ratio;
            dx -= (width - oldWidth) / 2.0;
            dy -= (height - oldHeight) / 2.0;
         }

         SpriteRenderer.instance
            .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, (float)r, (float)g, (float)b, (float)alpha, null);
      }
   }

   public void DrawTextureScaledAspect2(Texture tex, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         if (tex.getWidth() > 0 && tex.getHeight() > 0 && width > 0.0 && height > 0.0) {
            double ratio = Math.min(width / tex.getWidth(), height / tex.getHeight());
            double oldWidth = width;
            double oldHeight = height;
            width = tex.getWidth() * ratio;
            height = tex.getHeight() * ratio;
            dx -= (width - oldWidth) / 2.0;
            dy -= (height - oldHeight) / 2.0;
         }

         SpriteRenderer.instance
            .render(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, (float)r, (float)g, (float)b, (float)alpha, null);
      }
   }

   public void DrawTextureScaledAspect3(Texture tex, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         if (tex.getWidth() > 0 && tex.getHeight() > 0 && width > 0.0 && height > 0.0) {
            double ratio = Math.max(width / tex.getWidthOrig(), height / tex.getHeightOrig());
            double oldWidth = width;
            double oldHeight = height;
            width = tex.getWidth() * ratio;
            height = tex.getHeight() * ratio;
            dx -= (width - oldWidth) / 2.0;
            dy -= (height - oldHeight) / 2.0;
         }

         SpriteRenderer.instance
            .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, (float)r, (float)g, (float)b, (float)alpha, null);
      }
   }

   public void DrawTextureScaledCol(Texture tex, double x, double y, double width, double height, double r, double g, double b, double a) {
      if (tex != null) {
         boolean dx = false;
      }

      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         int top = (int)(dy + this.yScroll);
         if (!(top + height < 0.0) && top <= 4096) {
            SpriteRenderer.instance
               .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, (float)r, (float)g, (float)b, (float)a, null);
         }
      }
   }

   public void DrawTextureScaledCol(Texture tex, double x, double y, double width, double height, Color col) {
      if (tex != null) {
         boolean dx = false;
      }

      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         SpriteRenderer.instance.render(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, col.r, col.g, col.b, col.a, null);
      }
   }

   public void DrawTextureScaledColor(Texture tex, Double x, Double y, Double width, Double height, Double r, Double g, Double b, Double a) {
      this.DrawTextureScaledCol(tex, x, y, width, height, r, g, b, a);
   }

   public void DrawTextureColor(Texture tex, double x, double y, double r, double g, double b, double a) {
      tempcol.r = (float)r;
      tempcol.g = (float)g;
      tempcol.b = (float)b;
      tempcol.a = (float)a;
      this.DrawTextureCol(tex, x, y, tempcol);
   }

   public void DrawLine(Texture tex, double x1, double y1, double x2, double y2, float thickness, double r, double g, double b, double a) {
      if (this.isVisible()) {
         x1 += this.getAbsoluteX();
         y1 += this.getAbsoluteY();
         x2 += this.getAbsoluteX();
         y2 += this.getAbsoluteY();
         SpriteRenderer.instance
            .renderline(
               tex,
               (int)(x1 + this.xScroll),
               (int)(y1 + this.yScroll),
               (int)(x2 + this.xScroll),
               (int)(y2 + this.yScroll),
               (float)r,
               (float)g,
               (float)b,
               (float)a,
               thickness
            );
      }
   }

   public void DrawPolygon(
      Texture tex, double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4, double r, double g, double b, double a
   ) {
      double dx = this.getAbsoluteX() + this.xScroll;
      double dy = this.getAbsoluteY() + this.yScroll;
      x1 += dx;
      y1 += dy;
      x2 += dx;
      y2 += dy;
      x3 += dx;
      y3 += dy;
      x4 += dx;
      y4 += dy;
      SpriteRenderer.instance
         .renderPoly(tex, (float)x1, (float)y1, (float)x2, (float)y2, (float)x3, (float)y3, (float)x4, (float)y4, (float)r, (float)g, (float)b, (float)a);
   }

   public void DrawItemIcon(InventoryItem item, double x, double y, double alpha, double width, double height) {
      if (this.isVisible() && item != null) {
         Texture tex = item.getTex();
         double red = item.getR();
         double green = item.getG();
         double blue = item.getB();
         if (item.getTextureColorMask() != null) {
            red = 1.0;
            green = 1.0;
            blue = 1.0;
         }

         if (tex != null) {
            FluidContainer fluidContainer = item.getFluidContainer();
            if (fluidContainer == null && item.getWorldItem() != null) {
               fluidContainer = item.getWorldItem().getFluidContainer();
            }

            if (fluidContainer != null && item.getTextureFluidMask() != null) {
               Texture texMask = item.getTextureFluidMask();
               Color c = fluidContainer.getColor();
               this.DrawTextureIcon(tex, x, y, width, height, red, green, blue, alpha);
               double ratio = fluidContainer.getAmount() / fluidContainer.getCapacity();
               this.DrawTextureIconMask(texMask, ratio, x, y, width, height, c.r, c.g, c.b, alpha);
            } else {
               this.DrawTextureScaledAspect(tex, x, y, width, height, red, green, blue, alpha);
            }

            if (item.getTextureColorMask() != null) {
               Texture texMask = item.getTextureColorMask();
               this.DrawTextureIconMask(texMask, 1.0, x, y, width, height, item.getR(), item.getG(), item.getB(), alpha);
            }
         }
      }
   }

   public void DrawScriptItemIcon(Item scriptItem, double x, double y, double alpha, double width, double height) {
      if (this.isVisible() && scriptItem != null) {
         Texture tex = scriptItem.getNormalTexture();
         if (tex != null) {
            this.DrawTextureScaledAspect(tex, x, y, width, height, scriptItem.getR(), scriptItem.getG(), scriptItem.getB(), alpha);
         }
      }
   }

   public void DrawTextureIcon(Texture tex, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         if (tex.getWidth() > 0 && tex.getHeight() > 0 && width > 0.0 && height > 0.0) {
            double ratio = Math.min(width / tex.getWidthOrig(), height / tex.getHeightOrig());
            double offX = (int)(tex.offsetX * ratio);
            double offY = (int)(tex.offsetY * ratio);
            width = (int)(tex.getWidth() * ratio);
            height = (int)(tex.getHeight() * ratio);
            dx += offX;
            dy += offY;
         }

         SpriteRenderer.instance
            .renderi(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), (int)width, (int)height, (float)r, (float)g, (float)b, (float)alpha, null);
      }
   }

   public void DrawTextureIconMask(Texture tex, double yRatio, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         if (tex.getWidth() > 0 && tex.getHeight() > 0 && width > 0.0 && height > 0.0) {
            double ratio = Math.min(width / tex.getWidthOrig(), height / tex.getHeightOrig());
            double offX = (int)(tex.offsetX * ratio);
            double offY = (int)(tex.offsetY * ratio);
            width = (int)(tex.getWidth() * ratio);
            height = (int)(tex.getHeight() * ratio);
            dx += offX;
            dy += offY;
         }

         yRatio = PZMath.max(0.15F, (float)yRatio);
         double h = (int)(height * yRatio);
         dy += height - h;
         height = h;
         h = (int)(tex.getHeight() * yRatio);
         double subX = 0.0;
         double subY = tex.getHeight() - h;
         double subW = tex.getWidth();
         double subH = h;
         dx += this.xScroll;
         dy += this.yScroll;
         dx = (int)dx;
         dy = (int)dy;
         if (!(dy + height < 0.0) && !(dy > 4096.0)) {
            float fsubX = PZMath.clamp(0.0F, 0.0F, tex.getWidth());
            float fsubY = PZMath.clamp((float)subY, 0.0F, tex.getHeight());
            float fsubW = PZMath.clamp((float)(fsubX + subW), 0.0F, tex.getWidth()) - fsubX;
            float fsubH = PZMath.clamp((float)(fsubY + subH), 0.0F, tex.getHeight()) - fsubY;
            float u0 = fsubX / tex.getWidth();
            float v0 = fsubY / tex.getHeight();
            float u1 = (fsubX + fsubW) / tex.getWidth();
            float v1 = (fsubY + fsubH) / tex.getHeight();
            float uSpan = tex.getXEnd() - tex.getXStart();
            float vSpan = tex.getYEnd() - tex.getYStart();
            u0 = tex.getXStart() + u0 * uSpan;
            u1 = tex.getXStart() + u1 * uSpan;
            v0 = tex.getYStart() + v0 * vSpan;
            v1 = tex.getYStart() + v1 * vSpan;
            SpriteRenderer.instance
               .render(tex, (float)dx, (float)dy, (float)width, (float)height, (float)r, (float)g, (float)b, (float)alpha, u0, v0, u1, v0, u1, v1, u0, v1);
         }
      }
   }

   public void DrawTexturePercentage(Texture tex, double yRatio, double x, double y, double width, double height, double r, double g, double b, double alpha) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         width = (int)(width * yRatio);
         double subX = 0.0;
         double subY = 0.0;
         double subW = tex.getWidth() * yRatio;
         double subH = tex.getHeight();
         dx += this.xScroll;
         dy += this.yScroll;
         dx = (int)dx;
         dy = (int)dy;
         if (!(dy + height < 0.0) && !(dy > 4096.0)) {
            float fsubX = PZMath.clamp(0.0F, 0.0F, tex.getWidth());
            float fsubY = PZMath.clamp(0.0F, 0.0F, tex.getHeight());
            float fsubW = PZMath.clamp((float)(fsubX + subW), 0.0F, tex.getWidth()) - fsubX;
            float fsubH = PZMath.clamp((float)(fsubY + subH), 0.0F, tex.getHeight()) - fsubY;
            float u0 = fsubX / tex.getWidth();
            float v0 = fsubY / tex.getHeight();
            float u1 = (fsubX + fsubW) / tex.getWidth();
            float v1 = (fsubY + fsubH) / tex.getHeight();
            float uSpan = tex.getXEnd() - tex.getXStart();
            float vSpan = tex.getYEnd() - tex.getYStart();
            u0 = tex.getXStart() + u0 * uSpan;
            u1 = tex.getXStart() + u1 * uSpan;
            v0 = tex.getYStart() + v0 * vSpan;
            v1 = tex.getYStart() + v1 * vSpan;
            SpriteRenderer.instance
               .render(tex, (float)dx, (float)dy, (float)width, (float)height, (float)r, (float)g, (float)b, (float)alpha, u0, v0, u1, v0, u1, v1, u0, v1);
         }
      }
   }

   public void DrawTexturePercentageBottomUp(
      Texture tex, double yRatio, double x, double y, double width, double height, double r, double g, double b, double alpha
   ) {
      if (this.isVisible() && tex != null) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         double h = (int)(height * yRatio);
         dy += height - h;
         height = h;
         h = (int)(tex.getHeight() * yRatio);
         double subX = 0.0;
         double subY = tex.getHeight() - h;
         double subW = tex.getWidth();
         double subH = h;
         dx += this.xScroll;
         dy += this.yScroll;
         dx = (int)dx;
         dy = (int)dy;
         if (!(dy + height < 0.0) && !(dy > 4096.0)) {
            float fsubX = PZMath.clamp(0.0F, 0.0F, tex.getWidth());
            float fsubY = PZMath.clamp((float)subY, 0.0F, tex.getHeight());
            float fsubW = PZMath.clamp((float)(fsubX + subW), 0.0F, tex.getWidth()) - fsubX;
            float fsubH = PZMath.clamp((float)(fsubY + subH), 0.0F, tex.getHeight()) - fsubY;
            float u0 = fsubX / tex.getWidth();
            float v0 = fsubY / tex.getHeight();
            float u1 = (fsubX + fsubW) / tex.getWidth();
            float v1 = (fsubY + fsubH) / tex.getHeight();
            float uSpan = tex.getXEnd() - tex.getXStart();
            float vSpan = tex.getYEnd() - tex.getYStart();
            u0 = tex.getXStart() + u0 * uSpan;
            u1 = tex.getXStart() + u1 * uSpan;
            v0 = tex.getYStart() + v0 * vSpan;
            v1 = tex.getYStart() + v1 * vSpan;
            SpriteRenderer.instance
               .render(tex, (float)dx, (float)dy, (float)width, (float)height, (float)r, (float)g, (float)b, (float)alpha, u0, v0, u1, v0, u1, v1, u0, v1);
         }
      }
   }

   public void DrawSubTextureRGBA(
      Texture tex, double subX, double subY, double subW, double subH, double x, double y, double w, double h, double r, double g, double b, double a
   ) {
      if (tex != null && this.isVisible() && !(subW <= 0.0) && !(subH <= 0.0) && !(w <= 0.0) && !(h <= 0.0)) {
         double drawX = x + this.getAbsoluteX() + this.xScroll;
         double drawY = y + this.getAbsoluteY() + this.yScroll;
         drawX += tex.offsetX;
         drawY += tex.offsetY;
         if (!(drawY + h < 0.0) && !(drawY > 4096.0)) {
            float fsubX = PZMath.clamp((float)subX, 0.0F, tex.getWidth());
            float fsubY = PZMath.clamp((float)subY, 0.0F, tex.getHeight());
            float fsubW = PZMath.clamp((float)(fsubX + subW), 0.0F, tex.getWidth()) - fsubX;
            float fsubH = PZMath.clamp((float)(fsubY + subH), 0.0F, tex.getHeight()) - fsubY;
            float u0 = fsubX / tex.getWidth();
            float v0 = fsubY / tex.getHeight();
            float u1 = (fsubX + fsubW) / tex.getWidth();
            float v1 = (fsubY + fsubH) / tex.getHeight();
            float uSpan = tex.getXEnd() - tex.getXStart();
            float vSpan = tex.getYEnd() - tex.getYStart();
            u0 = tex.getXStart() + u0 * uSpan;
            u1 = tex.getXStart() + u1 * uSpan;
            v0 = tex.getYStart() + v0 * vSpan;
            v1 = tex.getYStart() + v1 * vSpan;
            SpriteRenderer.instance
               .render(tex, (float)drawX, (float)drawY, (float)w, (float)h, (float)r, (float)g, (float)b, (float)a, u0, v0, u1, v0, u1, v1, u0, v1);
         }
      }
   }

   public void DrawTextureTiled(Texture tex, double x, double y, double w, double h, double r, double g, double b, double a) {
      if (tex != null && this.isVisible() && !(w <= 0.0) && !(h <= 0.0)) {
         for (double y1 = y; y1 < y + h; y1 += tex.getHeight()) {
            for (double x1 = x; x1 < x + w; x1 += tex.getWidth()) {
               double width1 = tex.getWidth();
               double height1 = tex.getHeight();
               if (x1 + width1 > x + w) {
                  width1 = x + w - x1;
               }

               if (y1 + tex.getHeight() > y + h) {
                  height1 = y + h - y1;
               }

               this.DrawSubTextureRGBA(tex, 0.0, 0.0, width1, height1, x1, y1, width1, height1, r, g, b, a);
            }
         }
      }
   }

   public void DrawTextureTiledX(Texture tex, double x, double y, double w, double h, double r, double g, double b, double a) {
      if (tex != null && this.isVisible() && !(w <= 0.0) && !(h <= 0.0)) {
         for (double x1 = x; x1 < x + w; x1 += tex.getWidth()) {
            double width1 = tex.getWidth();
            double height1 = tex.getHeight();
            if (x1 + width1 > x + w) {
               width1 = x + w - x1;
            }

            this.DrawSubTextureRGBA(tex, 0.0, 0.0, width1, height1, x1, y, width1, height1, r, g, b, a);
         }
      }
   }

   public void DrawTextureTiledY(Texture tex, double x, double y, double w, double h, double r, double g, double b, double a) {
      if (tex != null && this.isVisible() && !(w <= 0.0) && !(h <= 0.0)) {
         for (double y1 = y; y1 < y + h; y1 += tex.getHeight()) {
            double width1 = tex.getWidth();
            double height1 = tex.getHeight();
            if (y1 + tex.getHeight() > y + h) {
               height1 = y + h - y1;
            }

            this.DrawSubTextureRGBA(tex, 0.0, 0.0, width1, height1, x, y1, width1, height1, r, g, b, a);
         }
      }
   }

   public void DrawTextureTiledYOffset(Texture tex, double x, double y, double w, double h, double r, double g, double b, double a) {
      if (tex != null && this.isVisible() && !(w <= 0.0) && !(h <= 0.0)) {
         double subY = y % tex.getHeight();

         for (double y1 = y; y1 < y + h; subY = 0.0) {
            double width1 = w;
            double height1 = tex.getHeight() - subY;
            if (height1 == 0.0) {
               subY = 0.0;
               height1 = tex.getHeight();
            }

            if (y1 + height1 > y + h) {
               height1 = y + h - y1;
            }

            this.DrawSubTextureRGBA(tex, 0.0, subY, width1, height1, x, y1, width1, height1, r, g, b, a);
            y1 += height1;
         }
      }
   }

   public void DrawTextureIgnoreOffset(Texture tex, double x, double y, int width, int height, Color col) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         SpriteRenderer.instance.render(tex, (int)(dx + this.xScroll), (int)(dy + this.yScroll), width, height, col.r, col.g, col.b, col.a, null);
      }
   }

   public void DrawTexture_FlippedX(Texture tex, double x, double y, int width, int height, Color col) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         SpriteRenderer.instance.renderflipped(tex, (float)(dx + this.xScroll), (float)(dy + this.yScroll), width, height, col.r, col.g, col.b, col.a, null);
      }
   }

   public void DrawTexture_FlippedXIgnoreOffset(Texture tex, double x, double y, int width, int height, Color col) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         SpriteRenderer.instance.renderflipped(tex, (float)(dx + this.xScroll), (float)(dy + this.yScroll), width, height, col.r, col.g, col.b, col.a, null);
      }
   }

   public void DrawUVSliceTexture(
      Texture tex, double x, double y, double width, double height, Color col, double xStart, double yStart, double xEnd, double yEnd
   ) {
      if (this.isVisible()) {
         double dx = x + this.getAbsoluteX();
         double dy = y + this.getAbsoluteY();
         dx += tex.offsetX;
         dy += tex.offsetY;
         Texture.lr = col.r;
         Texture.lg = col.g;
         Texture.lb = col.b;
         Texture.la = col.a;
         double xS = tex.getXStart();
         double yS = tex.getYStart();
         double xE = tex.getXEnd();
         double yE = tex.getYEnd();
         double xTot = xE - xS;
         double yTot = yE - yS;
         double texXTot = xEnd - xStart;
         double texYTot = yEnd - yStart;
         double xDel = texXTot / 1.0;
         double yDel = texYTot / 1.0;
         xS += xStart * xTot;
         yS += yStart * yTot;
         xE -= (1.0 - xEnd) * xTot;
         yE -= (1.0 - yEnd) * yTot;
         xS = (int)(xS * 1000.0) / 1000.0F;
         xE = (int)(xE * 1000.0) / 1000.0F;
         yS = (int)(yS * 1000.0) / 1000.0F;
         yE = (int)(yE * 1000.0) / 1000.0F;
         double x2 = dx + width;
         double y2 = dy + height;
         dx += xStart * width;
         dy += yStart * height;
         x2 -= (1.0 - xEnd) * width;
         y2 -= (1.0 - yEnd) * height;
         SpriteRenderer.instance
            .render(
               tex,
               (float)dx + this.getXScroll().intValue(),
               (float)dy + this.getYScroll().intValue(),
               (float)(x2 - dx),
               (float)(y2 - dy),
               col.r,
               col.g,
               col.b,
               col.a,
               (float)xS,
               (float)yS,
               (float)xE,
               (float)yS,
               (float)xE,
               (float)yE,
               (float)xS,
               (float)yE
            );
      }
   }

   public Boolean getScrollChildren() {
      return this.scrollChildren ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setScrollChildren(boolean bScroll) {
      this.scrollChildren = bScroll;
   }

   public Boolean getScrollWithParent() {
      return this.scrollWithParent ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setScrollWithParent(boolean bScroll) {
      this.scrollWithParent = bScroll;
   }

   public void setRenderClippedChildren(boolean b) {
      this.renderClippedChildren = b;
   }

   public Double getAbsoluteX() {
      if (this.getParent() != null) {
         return this.getParent().getScrollChildren() && this.getScrollWithParent()
            ? BoxedStaticValues.toDouble(this.getParent().getAbsoluteX() + this.getX().intValue() + this.getParent().getXScroll().intValue())
            : BoxedStaticValues.toDouble(this.getParent().getAbsoluteX() + this.getX().intValue());
      } else {
         return BoxedStaticValues.toDouble(this.getX().intValue());
      }
   }

   public Double getAbsoluteY() {
      if (this.getParent() != null) {
         return this.getParent().getScrollChildren() && this.getScrollWithParent()
            ? BoxedStaticValues.toDouble(this.getParent().getAbsoluteY() + this.getY().intValue() + this.getParent().getYScroll().intValue())
            : BoxedStaticValues.toDouble(this.getParent().getAbsoluteY() + this.getY().intValue());
      } else {
         return BoxedStaticValues.toDouble(this.getY().intValue());
      }
   }

   public String getClickedValue() {
      return this.clickedValue;
   }

   public void setClickedValue(String clickedValue) {
      this.clickedValue = clickedValue;
   }

   public void bringToTop() {
      UIManager.pushToTop(this);
      if (this.parent != null) {
         this.parent.addBringToTop(this);
      }
   }

   void onRightMouseUpOutside(double x, double y) {
      if (this.getTable() != null && UIManager.tableget(this.table, "onRightMouseUpOutside") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onRightMouseUpOutside"),
               this.table,
               BoxedStaticValues.toDouble(x - this.xScroll),
               BoxedStaticValues.toDouble(y - this.yScroll)
            );
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         ui.onRightMouseUpOutside(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue());
      }
   }

   void onRightMouseDownOutside(double x, double y) {
      if (this.getTable() != null && UIManager.tableget(this.table, "onRightMouseDownOutside") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onRightMouseDownOutside"),
               this.table,
               BoxedStaticValues.toDouble(x - this.xScroll),
               BoxedStaticValues.toDouble(y - this.yScroll)
            );
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         ui.onRightMouseDownOutside(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue());
      }
   }

   public void onMouseUpOutside(double x, double y) {
      if (this.getTable() != null && UIManager.tableget(this.table, "onMouseUpOutside") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onMouseUpOutside"),
               this.table,
               BoxedStaticValues.toDouble(x - this.xScroll),
               BoxedStaticValues.toDouble(y - this.yScroll)
            );
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         ui.onMouseUpOutside(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue());
      }
   }

   void onMouseDownOutside(double x, double y) {
      if (this.getTable() != null && UIManager.tableget(this.table, "onMouseDownOutside") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onMouseDownOutside"),
               this.table,
               BoxedStaticValues.toDouble(x - this.xScroll),
               BoxedStaticValues.toDouble(y - this.yScroll)
            );
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         ui.onMouseDownOutside(x - ui.getX().intValue(), y - ui.getY().intValue());
      }
   }

   void cancelMouseInteraction() {
      this.clicked = false;
      this.leftDownTime = 0L;
      boolean hadTransientState = clearTransientState("pressed");
      hadTransientState |= clearTransientState("moving");
      hadTransientState |= clearTransientState("resizing");
      hadTransientState |= clearTransientState("dragging");
      if (hadTransientState && this != UIManager.modal) {
         this.capture = false;
      }
      for (int i = 0; i < this.controls.size(); i++) {
         this.controls.get(i).cancelMouseInteraction();
      }
   }

   private boolean clearTransientState(String field) {
      if (this.table != null && this.table.rawget(field) == Boolean.TRUE) {
         this.table.rawset(field, Boolean.FALSE);
         return true;
      }
      return false;
   }
   public Boolean onMouseDown(double x, double y) {
      if (this.clicked
         && UIManager.isDoubleClick((int)this.clickX, (int)this.clickY, (int)x, (int)y, this.leftDownTime)
         && this.getTable() != null
         && this.getTable().rawget("onMouseDoubleClick") != null) {
         this.clicked = false;
         return this.onMouseDoubleClick(x, y) ? Boolean.TRUE : Boolean.FALSE;
      }

      this.clicked = true;
      this.clickX = x;
      this.clickY = y;
      this.leftDownTime = SubframeInput.inEvent() ? SubframeInput.eventMillis() : System.currentTimeMillis();
      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (!this.visible) {
         return Boolean.FALSE;
      }

      if (this.getTable() != null && UIManager.tableget(this.table, "onFocus") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onFocus"),
               this.table,
               BoxedStaticValues.toDouble(x - this.xScroll),
               BoxedStaticValues.toDouble(y - this.yScroll)
            );
      }

      boolean bForceOutside = false;

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (!bForceOutside
            && (
               x > ui.getXScrolled(this)
                     && y > ui.getYScrolled(this)
                     && x < ui.getXScrolled(this) + ui.getWidth()
                     && y < ui.getYScrolled(this) + ui.getHeight()
                  || ui.isCapture()
            )) {
            if (ui.onMouseDown(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue())) {
               bForceOutside = true;
            }
         } else if (ui.getTable() != null && UIManager.tableget(ui.getTable(), "onMouseDownOutside") != null) {
            LuaManager.caller
               .protectedCallVoid(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(ui.getTable(), "onMouseDownOutside"),
                  ui.getTable(),
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
         }
      }

      if (this.getTable() != null) {
         if (bForceOutside) {
            if (UIManager.tableget(this.table, "onMouseDownOutside") != null) {
               Boolean o = LuaManager.caller
                  .protectedCallBoolean(
                     UIManager.getDefaultThread(),
                     UIManager.tableget(this.table, "onMouseDownOutside"),
                     this.table,
                     BoxedStaticValues.toDouble(x - this.xScroll),
                     BoxedStaticValues.toDouble(y - this.yScroll)
                  );
               if (o == null) {
                  return Boolean.TRUE;
               }

               if (o) {
                  return Boolean.TRUE;
               }
            }
         } else if (UIManager.tableget(this.table, "onMouseDown") != null) {
            Boolean o = LuaManager.caller
               .protectedCallBoolean(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(this.table, "onMouseDown"),
                  this.table,
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
            if (o == null) {
               return this.consumeMouseEvents ? Boolean.TRUE : Boolean.FALSE;
            }

            if (o) {
               return Boolean.TRUE;
            }
         }
      }

      return bForceOutside;
   }

   private Boolean onMouseDoubleClick(double x2, double y2) {
      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= this.y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= this.y) {
         return Boolean.FALSE;
      }

      if (!this.visible) {
         return Boolean.FALSE;
      }

      if (UIManager.tableget(this.table, "onMouseDoubleClick") != null) {
         Boolean o = LuaManager.caller
            .protectedCallBoolean(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onMouseDoubleClick"),
               this.table,
               BoxedStaticValues.toDouble(x2 - this.xScroll),
               BoxedStaticValues.toDouble(y2 - this.yScroll)
            );
         if (o == null) {
            return Boolean.TRUE;
         }

         if (o) {
            return Boolean.TRUE;
         }
      }

      return Boolean.TRUE;
   }

   public Boolean onConsumeMouseWheel(double del, double x, double y) {
      if (!this.isIgnoreLossControl() && TutorialManager.instance.stealControl) {
         return false;
      } else {
         if (!this.isVisible()) { // pzopt: decompiler fix, the jar returns onMouseWheel's Boolean as is (no unbox / rebox)
            return false; // pzopt: decompiler fix
         } // pzopt: decompiler fix
         return this.onMouseWheel(del); // pzopt: decompiler fix
      }
   }

   public Boolean onMouseWheel(double del) {
      int mx = Mouse.getXA();
      int my = Mouse.getYA();

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (ui.isVisible()
            && (
               mx >= ui.getAbsoluteX() && my >= ui.getAbsoluteY() && mx < ui.getAbsoluteX() + ui.getWidth() && my < ui.getAbsoluteY() + ui.getHeight()
                  || ui.isCapture()
            )
            && ui.onMouseWheel(del)) {
            return this.consumeMouseEvents ? Boolean.TRUE : Boolean.FALSE;
         }
      }

      if (this.getTable() != null && UIManager.tableget(this.table, "onMouseWheel") != null) {
         Boolean o = LuaManager.caller
            .protectedCallBoolean(UIManager.getDefaultThread(), UIManager.tableget(this.table, "onMouseWheel"), this.table, BoxedStaticValues.toDouble(del));
         if (o == Boolean.TRUE) {
            return Boolean.TRUE;
         }
      }

      return Boolean.FALSE;
   }

   public Boolean onConsumeMouseMove(double dx, double dy, double x, double y) {
      return this.onMouseMove(dx, dy);
   }

   public Boolean onMouseMove(double dx, double dy) {
      int mx = Mouse.getXA();
      int my = Mouse.getYA();
      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= this.y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= my - this.getAbsoluteY()) {
         return Boolean.FALSE;
      }

      if (!this.visible) {
         return Boolean.FALSE;
      }

      if (this.getTable() != null && UIManager.tableget(this.table, "onMouseMove") != null) {
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onMouseMove"),
               this.table,
               BoxedStaticValues.toDouble(dx),
               BoxedStaticValues.toDouble(dy)
            );
      }

      boolean consumedMove = false;

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (ui.isVisible()) {
            if ((
                  !(mx >= ui.getAbsoluteX())
                     || !(my >= ui.getAbsoluteY())
                     || !(mx < ui.getAbsoluteX() + ui.getWidth())
                     || !(my < ui.getAbsoluteY() + ui.getHeight())
               )
               && !ui.isCapture()) {
               ui.onMouseMoveOutside(dx, dy);
            } else if ((!consumedMove || ui.isCapture()) && ui.onMouseMove(dx, dy)) {
               consumedMove = true;
            }
         }
      }

      return !consumedMove && !this.consumeMouseEvents ? Boolean.FALSE : Boolean.TRUE;
   }

   @Override
   public String toString() {
      return this.getTable() != null ? KahluaUtil.tostring(this.getTable(), UIManager.getDefaultThread()) : super.toString();
   }

   public void onExtendMouseMoveOutside(double dx, double dy, double x, double y) {
      this.onMouseMoveOutside(dx, dy);
   }

   public void onMouseMoveOutside(double dx, double dy) {
      if (this.getTable() != null && UIManager.tableget(this.table, "onMouseMoveOutside") != null
         && !pzopt.LuaFast.skipEmpty(UIManager.tableget(this.table, "onMouseMoveOutside"))) { // pzopt: luaSkipEmpty
         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(),
               UIManager.tableget(this.table, "onMouseMoveOutside"),
               this.table,
               BoxedStaticValues.toDouble(dx),
               BoxedStaticValues.toDouble(dy)
            );
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (ui.isVisible()) {
            ui.onMouseMoveOutside(dx, dy);
         }
      }
   }

   public Boolean onMouseUp(double x, double y) {
      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (!this.visible) {
         return Boolean.FALSE;
      }

      boolean bForceOutside = false;

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (!bForceOutside
            && (
               x >= ui.getXScrolled(this)
                     && y >= ui.getYScrolled(this)
                     && x < ui.getXScrolled(this) + ui.getWidth()
                     && y < ui.getYScrolled(this) + ui.getHeight()
                  || ui.isCapture()
            )) {
            if (ui.onMouseUp(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue())) {
               bForceOutside = true;
            }
         } else {
            ui.onMouseUpOutside(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue());
         }

         i = PZMath.min(i, this.getControls().size());
      }

      if (this.getTable() != null) {
         if (bForceOutside) {
            if (UIManager.tableget(this.table, "onMouseUpOutside") != null) {
               Boolean o = LuaManager.caller
                  .protectedCallBoolean(
                     UIManager.getDefaultThread(),
                     UIManager.tableget(this.table, "onMouseUpOutside"),
                     this.table,
                     BoxedStaticValues.toDouble(x - this.xScroll),
                     BoxedStaticValues.toDouble(y - this.yScroll)
                  );
               if (o == null) {
                  return Boolean.TRUE;
               }

               if (o) {
                  return Boolean.TRUE;
               }
            }
         } else if (UIManager.tableget(this.table, "onMouseUp") != null) {
            Boolean o = LuaManager.caller
               .protectedCallBoolean(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(this.table, "onMouseUp"),
                  this.table,
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
            if (o == null) {
               return this.consumeMouseEvents ? Boolean.TRUE : Boolean.FALSE;
            }

            if (o) {
               return Boolean.TRUE;
            }
         }
      }

      return bForceOutside ? Boolean.TRUE : Boolean.FALSE;
   }

   public void onMouseButtonDown(int btn, double x, double y) {
      if (this.wantExtraMouseEvents && this.getTable() != null && UIManager.tableget(this.getTable(), "onMouseButtonDown") != null) {
         for (int i = this.getControls().size() - 1; i >= 0; i--) {
            if (this.getControls().get(i).isMouseOver()) {
               return;
            }
         }

         LuaManager.caller
            .protectedCallVoid(
               UIManager.getDefaultThread(), UIManager.tableget(this.getTable(), "onMouseButtonDown"), this.getTable(), BoxedStaticValues.toDouble(btn)
            );
      }
   }

   public boolean onConsumeMouseButtonDown(int btn, double x, double y) {
      if (btn == 0) {
         return this.onMouseDown(x, y);
      }

      if (btn == 1) {
         return this.onRightMouseDown(x, y);
      }

      this.onMouseButtonDown(btn, x, y);
      return false;
   }

   public void onMouseButtonDownOutside(int btn, double x, double y) {
      if (btn == 0) {
         this.onMouseDownOutside(x, y);
      } else if (btn == 1) {
         this.onRightMouseDownOutside(x, y);
      }
   }

   public boolean onConsumeMouseButtonUp(int btn, double x, double y) {
      if (btn == 0) {
         return this.onMouseUp(x, y);
      } else {
         return btn == 1 ? this.onRightMouseUp(x, y) : false;
      }
   }

   public void onMouseButtonUpOutside(int btn, double x, double y) {
      if (btn == 0) {
         this.onMouseUpOutside(x, y);
      } else if (btn == 1) {
         this.onRightMouseUpOutside(x, y);
      }
   }

   public void onresize() {
   }

   public void onResize() {
      if (this.parent != null && this.parent.resizeDirty) {
         double difx = this.parent.getWidth() - this.parent.lastwidth;
         double dify = this.parent.getHeight() - this.parent.lastheight;
         if (!this.anchorTop && this.anchorBottom) {
            this.setY(this.getY() + dify);
         }

         if (this.anchorTop && this.anchorBottom) {
            this.setHeight(this.getHeight() + dify);
         }

         if (!this.anchorLeft && this.anchorRight) {
            this.setX(this.getX() + difx);
         }

         if (this.anchorLeft && this.anchorRight) {
            this.setWidth(this.getWidth() + difx);
         }
      }

      if (this.getTable() != null && UIManager.tableget(this.table, "onResize") != null) {
         LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), UIManager.tableget(this.table, "onResize"), this.table, this.getWidth(), this.getHeight());
      }

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (ui == null) {
            this.getControls().remove(i);
         } else {
            ui.onResize();
         }
      }

      this.lastwidth = this.getWidth();
      this.lastheight = this.getHeight();
   }

   public Boolean onRightMouseDown(double x, double y) {
      if (!this.isVisible()) {
         return Boolean.FALSE;
      }

      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      boolean bForceOutside = false;

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (!bForceOutside
            && (
               x >= ui.getXScrolled(this)
                     && y >= ui.getYScrolled(this)
                     && x < ui.getXScrolled(this) + ui.getWidth()
                     && y < ui.getYScrolled(this) + ui.getHeight()
                  || ui.isCapture()
            )) {
            if (ui.onRightMouseDown(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue())) {
               bForceOutside = true;
            }
         } else if (ui.getTable() != null && UIManager.tableget(ui.getTable(), "onRightMouseDownOutside") != null) {
            LuaManager.caller
               .protectedCallVoid(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(ui.getTable(), "onRightMouseDownOutside"),
                  ui.getTable(),
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
         }
      }

      if (this.getTable() != null) {
         if (bForceOutside) {
            if (UIManager.tableget(this.table, "onRightMouseDownOutside") != null) {
               Boolean o = LuaManager.caller
                  .protectedCallBoolean(
                     UIManager.getDefaultThread(),
                     UIManager.tableget(this.table, "onRightMouseDownOutside"),
                     this.table,
                     BoxedStaticValues.toDouble(x - this.xScroll),
                     BoxedStaticValues.toDouble(y - this.yScroll)
                  );
               if (o == null) {
                  return Boolean.TRUE;
               }

               if (o) {
                  return Boolean.TRUE;
               }
            }
         } else if (UIManager.tableget(this.table, "onRightMouseDown") != null) {
            Boolean o = LuaManager.caller
               .protectedCallBoolean(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(this.table, "onRightMouseDown"),
                  this.table,
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
            if (o == null) {
               return Boolean.TRUE;
            }

            if (o) {
               return Boolean.TRUE;
            }
         }
      }

      return bForceOutside ? Boolean.TRUE : Boolean.FALSE;
   }

   public Boolean onRightMouseUp(double x, double y) {
      if (!this.isVisible()) {
         return Boolean.FALSE;
      }

      if (this.parent != null && this.parent.maxDrawHeight != -1 && this.parent.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      if (this.maxDrawHeight != -1 && this.maxDrawHeight <= y) {
         return Boolean.FALSE;
      }

      boolean bForceOutside = false;

      for (int i = this.getControls().size() - 1; i >= 0; i--) {
         UIElement ui = this.getControls().get(i);
         if (!bForceOutside
            && (
               x >= ui.getXScrolled(this)
                     && y >= ui.getYScrolled(this)
                     && x < ui.getXScrolled(this) + ui.getWidth()
                     && y < ui.getYScrolled(this) + ui.getHeight()
                  || ui.isCapture()
            )) {
            if (ui.onRightMouseUp(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue())) {
               bForceOutside = true;
            }
         } else {
            ui.onRightMouseUpOutside(x - ui.getXScrolled(this).intValue(), y - ui.getYScrolled(this).intValue());
         }
      }

      if (this.getTable() != null) {
         if (bForceOutside) {
            if (UIManager.tableget(this.table, "onRightMouseUpOutside") != null) {
               Boolean o = LuaManager.caller
                  .protectedCallBoolean(
                     UIManager.getDefaultThread(),
                     UIManager.tableget(this.table, "onRightMouseUpOutside"),
                     this.table,
                     BoxedStaticValues.toDouble(x - this.xScroll),
                     BoxedStaticValues.toDouble(y - this.yScroll)
                  );
               if (o == null) {
                  return Boolean.TRUE;
               }

               if (o) {
                  return Boolean.TRUE;
               }
            }
         } else if (UIManager.tableget(this.table, "onRightMouseUp") != null) {
            Boolean o = LuaManager.caller
               .protectedCallBoolean(
                  UIManager.getDefaultThread(),
                  UIManager.tableget(this.table, "onRightMouseUp"),
                  this.table,
                  BoxedStaticValues.toDouble(x - this.xScroll),
                  BoxedStaticValues.toDouble(y - this.yScroll)
               );
            if (o == null) {
               return Boolean.TRUE;
            }

            if (o) {
               return Boolean.TRUE;
            }
         }
      }

      return bForceOutside ? Boolean.TRUE : Boolean.FALSE;
   }

   public void RemoveControl(UIElement el) {
      this.getControls().remove(el);
      el.setParent(null);
   }

   public void render() {
      if (this.enabled) {
         if (this.isVisible()) {
            if (this.parent == null || this.parent.maxDrawHeight == -1 || !(this.parent.maxDrawHeight <= this.y)) {
               if (this.parent != null && !this.parent.renderClippedChildren) {
                  Double parentAbsY = this.parent.getAbsoluteY();
                  double absY = this.getAbsoluteY();
                  if (absY + this.getHeight() <= parentAbsY || absY >= parentAbsY + this.getParent().getHeight()) {
                     return;
                  }
               }

               if (this.parent != null && pzopt.UiRetained.replayChild(this)) { // pzopt: uiRetainedChildren, its recorded commands instead of its Lua and subtree
                  return; // pzopt: uiRetainedChildren
               } // pzopt: uiRetainedChildren
               int pzoptFrom = this.parent != null ? pzopt.UiRetained.childBegin() : -1; // pzopt: uiRetainedChildren
               long pzoptStart = pzoptFrom >= 0 ? System.nanoTime() : 0L; // pzopt: uiRetainedChildren

               if (this.getTable() != null) {
                  Object prerender = UIManager.tableget(this.table, "prerender");
                  if (prerender != null && !pzopt.LuaFast.skipEmpty(prerender)) { // pzopt: luaSkipEmpty, an empty Lua function is not called
                     long pzoptT0 = pzopt.UiProfile.callBegin(); // pzopt: uiProfile, Lua calls by element type
                     try {
                        LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), prerender, this.table);
                     } catch (Exception e) {
                        boolean r = false;
                     }
                     pzopt.UiProfile.callEnd(this, 0, pzoptT0); // pzopt: uiProfile
                  }
               }

               for (int i = 0; i < this.getControls().size(); i++) {
                  this.getControls().get(i).render();
               }

               if (this.getTable() != null) {
                  Object render = UIManager.tableget(this.table, "render");
                  if (render != null && !pzopt.LuaFast.skipEmpty(render)) { // pzopt: luaSkipEmpty
                     long pzoptT0 = pzopt.UiProfile.callBegin(); // pzopt: uiProfile
                     LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), render, this.table);
                     pzopt.UiProfile.callEnd(this, 1, pzoptT0); // pzopt: uiProfile
                  }
               }

               if (Core.debug && DebugOptions.instance.uiRenderOutline.getValue()) {
                  if (this.table != null && "ISScrollingListBox".equals(UIManager.tableget(this.table, "Type"))) {
                     this.repaintStencilRect(0.0, 0.0, (int)this.width, (int)this.height);
                  }

                  Double x = -this.getXScroll();
                  Double y = -this.getYScroll();
                  double r = 1.0;
                  if (this.isMouseOver()) {
                     r = 0.0;
                  }

                  double height = this.maxDrawHeight == -1 ? this.height : this.maxDrawHeight;
                  this.DrawTextureScaledColor(null, x, y, 1.0, height, r, 1.0, 1.0, 0.5);
                  this.DrawTextureScaledColor(null, x + 1.0, y, this.width - 2.0, 1.0, r, 1.0, 1.0, 0.5);
                  this.DrawTextureScaledColor(null, x + this.width - 1.0, y, 1.0, height, r, 1.0, 1.0, 0.5);
                  this.DrawTextureScaledColor(null, x + 1.0, y + height - 1.0, this.width - 2.0, 1.0, r, 1.0, 1.0, 0.5);
               }

               if (pzoptFrom >= 0) { // pzopt: uiRetainedChildren, record what this child drew
                  pzopt.UiRetained.freshEnd(this, pzoptFrom, System.nanoTime() - pzoptStart); // pzopt: uiRetainedChildren
               } // pzopt: uiRetainedChildren
            }
         }
      }
   }

   public void update() {
      if (this.enabled) {
         for (int i = 0; i < this.controls.size(); i++) {
            if (this.toTop.contains(this.controls.get(i))) {
               UIElement rem = this.controls.remove(i);
               i--;
               toAdd.add(rem);
            }
         }

         PZArrayUtil.addAll(this.controls, toAdd);
         toAdd.clear();
         this.toTop.clear();
         if (UIManager.doTick && this.getTable() != null && UIManager.tableget(this.table, "update") != null) {
            Object pzoptUpdate = UIManager.tableget(this.table, "update"); // pzopt: luaSkipEmpty
            if (!pzopt.LuaFast.skipEmpty(pzoptUpdate)) { // pzopt: luaSkipEmpty
               long pzoptT0 = pzopt.UiProfile.callBegin(); // pzopt: uiProfile
               LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), pzoptUpdate, this.table);
               pzopt.UiProfile.callEnd(this, 2, pzoptT0); // pzopt: uiProfile
            } // pzopt: luaSkipEmpty
         }

         if (this.resizeDirty) {
            this.onResize();
            this.lastwidth = this.width;
            this.lastheight = this.height;
            this.resizeDirty = false;
         }

         for (int i = 0; i < this.getControls().size(); i++) {
            this.getControls().get(i).update();
         }
      }
   }

   public void BringToTop(UIElement el) {
      this.getControls().remove(el);
      this.getControls().add(el);
   }

   public Boolean isCapture() {
      return this.capture ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setCapture(boolean capture) {
      this.capture = capture;
   }

   public boolean isModalVisible() {
      if (!this.isReallyVisible()) {
         return false;
      }

      if (this.isCapture()) {
         return true;
      }

      for (int i = 0; i < this.getControls().size(); i++) {
         UIElement child = this.getControls().get(i);
         if (child.isModalVisible()) {
            return true;
         }
      }

      return false;
   }

   public Boolean isIgnoreLossControl() {
      return this.ignoreLossControl ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setIgnoreLossControl(boolean ignoreLossControl) {
      this.ignoreLossControl = ignoreLossControl;
   }

   public ArrayList<UIElement> getControls() {
      return this.controls;
   }

   public void setControls(Vector<UIElement> controls) {
      this.setControls(controls);
   }

   public Boolean isDefaultDraw() {
      return this.defaultDraw ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setDefaultDraw(boolean defaultDraw) {
      this.defaultDraw = defaultDraw;
   }

   public Boolean isFollowGameWorld() {
      return this.followGameWorld ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setFollowGameWorld(boolean followGameWorld) {
      this.followGameWorld = followGameWorld;
   }

   public int getRenderThisPlayerOnly() {
      return this.renderThisPlayerOnly;
   }

   public void setRenderThisPlayerOnly(int playerIndex) {
      this.renderThisPlayerOnly = playerIndex;
   }

   public Double getHeight() {
      return BoxedStaticValues.toDouble(this.height);
   }

   public void setHeight(double height) {
      if (this.height != height) {
         this.resizeDirty = true;
      }

      this.lastheight = this.height;
      this.height = (float)height;
   }

   public UIElement getParent() {
      return this.parent;
   }

   public void setParent(UIElement parent) {
      this.parent = parent;
   }

   public Boolean isVisible() {
      return this.visible ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setVisible(boolean visible) {
      this.visible = visible;
   }

   public boolean isReallyVisible() {
      if (!this.isVisible()) {
         return false;
      } else if (this.getParent() != null) {
         return !this.getParent().getControls().contains(this) ? false : this.getParent().isReallyVisible();
      } else {
         return UIManager.getUI().contains(this);
      }
   }

   public Double getWidth() {
      return BoxedStaticValues.toDouble(this.width);
   }

   public void setWidth(double width) {
      if (this.width != width) {
         this.resizeDirty = true;
      }

      this.lastwidth = this.width;
      this.width = (float)width;
   }

   public Double getX() {
      return BoxedStaticValues.toDouble(this.x);
   }

   public void setX(double x) {
      this.x = (float)x;
   }

   public Double getXScrolled(UIElement parent) {
      return parent != null && parent.scrollChildren && this.scrollWithParent
         ? BoxedStaticValues.toDouble(this.x + parent.getXScroll())
         : BoxedStaticValues.toDouble(this.x);
   }

   public Double getYScrolled(UIElement parent) {
      return parent != null && parent.scrollChildren && this.scrollWithParent
         ? BoxedStaticValues.toDouble(this.y + parent.getYScroll())
         : BoxedStaticValues.toDouble(this.y);
   }

   public boolean isEnabled() {
      return this.enabled;
   }

   public void setEnabled(boolean en) {
      this.enabled = en;
   }

   public Double getY() {
      return BoxedStaticValues.toDouble(this.y);
   }

   public void setY(double y) {
      this.y = (float)y;
   }

   public boolean isOverElement(double mx, double my) {
      return mx >= this.x && my >= this.y && mx < this.x + this.width && my < this.y + this.height;
   }

   public void suspendStencil() {
      IndieGL.disableStencilTest();
      IndieGL.disableAlphaTest();
   }

   public void resumeStencil() {
      IndieGL.enableStencilTest();
      IndieGL.enableAlphaTest();
   }

   public void setStencilRect(double x, double y, double width, double height) {
      x += this.getAbsoluteX();
      y += this.getAbsoluteY();
      IndieGL.glStencilMask(255);
      IndieGL.enableStencilTest();
      IndieGL.enableAlphaTest();
      IndieGL.glStencilFunc(514, stencilLevel, 255);
      stencilLevel++;
      IndieGL.glStencilOp(7680, 7680, 7682);
      IndieGL.glColorMask(false, false, false, false);
      SpriteRenderer.instance.renderi(null, (int)x, (int)y, (int)width, (int)height, 1.0F, 0.0F, 0.0F, 1.0F, null);
      IndieGL.glColorMask(true, true, true, true);
      IndieGL.glStencilOp(7680, 7680, 7680);
      IndieGL.glStencilFunc(514, stencilLevel, 255);
   }

   public void setStencilCircle(double x, double y, double width, double height) {
      x += this.getAbsoluteX();
      y += this.getAbsoluteY();
      IndieGL.glStencilMask(255);
      IndieGL.enableStencilTest();
      IndieGL.enableAlphaTest();
      IndieGL.glStencilFunc(514, stencilLevel, 255);
      stencilLevel++;
      IndieGL.glStencilOp(7680, 7680, 7682);
      IndieGL.glColorMask(false, false, false, false);
      if (circleTexture == null) {
         circleTexture = Texture.getSharedTexture("media/ui/circle.png");
      }

      SpriteRenderer.instance.renderi(circleTexture, (int)x, (int)y, (int)width, (int)height, 1.0F, 0.0F, 0.0F, 1.0F, null);
      IndieGL.glColorMask(true, true, true, true);
      IndieGL.glStencilOp(7680, 7680, 7680);
      IndieGL.glStencilFunc(514, stencilLevel, 255);
   }

   public void clearStencilRect() {
      if (stencilLevel > 0) {
         stencilLevel--;
      }

      if (stencilLevel > 0) {
         IndieGL.glStencilFunc(514, stencilLevel, 255);
      } else {
         IndieGL.glAlphaFunc(519, 0.0F);
         IndieGL.disableStencilTest();
         IndieGL.disableAlphaTest();
         IndieGL.glStencilFunc(519, 255, 255);
         IndieGL.glStencilOp(7680, 7680, 7680);
         IndieGL.glClear(1280);
      }
   }

   public void repaintStencilRect(double x, double y, double width, double height) {
      if (stencilLevel > 0) {
         x += this.getAbsoluteX();
         y += this.getAbsoluteY();
         IndieGL.glStencilFunc(519, stencilLevel, 255);
         IndieGL.glStencilOp(7680, 7680, 7681);
         IndieGL.glColorMask(false, false, false, false);
         SpriteRenderer.instance.renderi(null, (int)x, (int)y, (int)width, (int)height, 1.0F, 0.0F, 0.0F, 1.0F, null);
         IndieGL.glColorMask(true, true, true, true);
         IndieGL.glStencilOp(7680, 7680, 7680);
         IndieGL.glStencilFunc(514, stencilLevel, 255);
      }
   }

   public KahluaTable getTable() {
      return this.table;
   }

   public void setTable(KahluaTable table) {
      this.table = table;
   }

   public void setHeightSilent(double height) {
      this.lastheight = this.height;
      this.height = (float)height;
   }

   public void setWidthSilent(double width) {
      this.lastwidth = this.width;
      this.width = (float)width;
   }

   public void setHeightOnly(double height) {
      this.height = (float)height;
   }

   public void setWidthOnly(double width) {
      this.width = (float)width;
   }

   public boolean isAnchorTop() {
      return this.anchorTop;
   }

   public void setAnchorTop(boolean anchorTop) {
      this.anchorTop = anchorTop;
      this.lastwidth = this.width;
      this.lastheight = this.height;
   }

   public void ignoreWidthChange() {
      this.lastwidth = this.width;
   }

   public void ignoreHeightChange() {
      this.lastheight = this.height;
   }

   public Boolean isAnchorLeft() {
      return this.anchorLeft ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setAnchorLeft(boolean anchorLeft) {
      this.anchorLeft = anchorLeft;
      this.lastwidth = this.width;
      this.lastheight = this.height;
   }

   public Boolean isAnchorRight() {
      return this.anchorRight ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setAnchorRight(boolean anchorRight) {
      this.anchorRight = anchorRight;
      this.lastwidth = this.width;
      this.lastheight = this.height;
   }

   public Boolean isAnchorBottom() {
      return this.anchorBottom ? Boolean.TRUE : Boolean.FALSE;
   }

   public void setAnchorBottom(boolean anchorBottom) {
      this.anchorBottom = anchorBottom;
      this.lastwidth = this.width;
      this.lastheight = this.height;
   }

   private void addBringToTop(UIElement aThis) {
      this.toTop.add(aThis);
   }

   public int getPlayerContext() {
      return this.playerContext;
   }

   public void setPlayerContext(int nPlayer) {
      this.playerContext = nPlayer;
   }

   public String getUIName() {
      if (this.uiname.isEmpty()) {
         if (this instanceof TextBox text) {
            return "Text: " + text.text;
         } else {
            if (this.table != null) {
               Object rg = this.table.rawget("name");
               Object type = this.table.rawget("Type");
               if (type != null) {
                  if (rg != null) {
                     String name = rg.toString();
                     return String.valueOf(type) + ": " + name; // pzopt: decompiler fix, String.valueOf as in the jar
                  }

                  return type.toString();
               }
            }

            return "UI: " + String.valueOf(this.hashCode()); // pzopt: decompiler fix
         }
      } else {
         return this.uiname;
      }
   }

   public void setUIName(String name) {
      this.uiname = name != null ? name : "";
   }

   public Double clampToParentX(double x) {
      if (this.getParent() == null) {
         return BoxedStaticValues.toDouble(x);
      }

      double parentX1 = this.getParent().clampToParentX(this.getParent().getAbsoluteX());
      double parentX2 = this.getParent().clampToParentX(parentX1 + this.getParent().getWidth().intValue());
      if (x < parentX1) {
         x = parentX1;
      }

      if (x > parentX2) {
         x = parentX2;
      }

      return BoxedStaticValues.toDouble(x);
   }

   public Double clampToParentY(double y) {
      if (this.getParent() == null) {
         return BoxedStaticValues.toDouble(y);
      }

      double parentY1 = this.getParent().clampToParentY(this.getParent().getAbsoluteY());
      double parentY2 = this.getParent().clampToParentY(parentY1 + this.getParent().getHeight().intValue());
      if (y < parentY1) {
         y = parentY1;
      }

      if (y > parentY2) {
         y = parentY2;
      }

      return BoxedStaticValues.toDouble(y);
   }

   public Boolean isPointOver(double screenX, double screenY) {
      if (!this.isVisible()) {
         return Boolean.FALSE;
      }

      int height = this.getHeight().intValue();
      if (this.maxDrawHeight != -1) {
         height = Math.min(height, this.maxDrawHeight);
      }

      double localX = screenX - this.getAbsoluteX();
      double localY = screenY - this.getAbsoluteY();
      if (localX < 0.0 || localX >= this.getWidth() || localY < 0.0 || localY >= height) {
         return Boolean.FALSE;
      }

      if (this.parent == null) {
         ArrayList<UIElementInterface> uis = UIManager.getUI();

         for (int i = uis.size() - 1; i >= 0; i--) {
            UIElementInterface other = uis.get(i);
            if (other == this) {
               break;
            }

            if (other.isPointOver(screenX, screenY)) {
               return Boolean.FALSE;
            }
         }

         return Boolean.TRUE;
      } else {
         for (int i = this.parent.controls.size() - 1; i >= 0; i--) {
            UIElement other = this.parent.controls.get(i);
            if (other == this) {
               break;
            }

            if (other.isVisible() && other.consumeMouseEvents) {
               height = other.getHeight().intValue();
               if (other.maxDrawHeight != -1) {
                  height = Math.min(height, other.maxDrawHeight);
               }

               localX = screenX - other.getAbsoluteX();
               localY = screenY - other.getAbsoluteY();
               if (localX >= 0.0 && localX < other.getWidth() && localY >= 0.0 && localY < height) {
                  return Boolean.FALSE;
               }
            }
         }

         return this.parent.isPointOver(screenX, screenY) ? Boolean.TRUE : Boolean.FALSE;
      }
   }

   public Boolean isMouseOver() {
      return this.isPointOver(Mouse.getXA(), Mouse.getYA()) ? Boolean.TRUE : Boolean.FALSE;
   }

   protected Object tryGetTableValue(String key) {
      return this.getTable() == null ? null : UIManager.tableget(this.table, key);
   }

   public void setWantKeyEvents(boolean want) {
      this.wantKeyEvents = want;
   }

   public boolean isWantKeyEvents() {
      return this.wantKeyEvents;
   }

   public void setWantExtraMouseEvents(boolean want) {
      this.wantExtraMouseEvents = want;
   }

   public boolean isWantExtraMouseEvents() {
      return this.wantExtraMouseEvents;
   }

   public boolean isKeyConsumed(int key) {
      Object functionObj = this.tryGetTableValue("isKeyConsumed");
      if (functionObj == null) {
         return false;
      }

      Boolean result = LuaManager.caller.pcallBoolean(UIManager.getDefaultThread(), functionObj, this.getTable(), BoxedStaticValues.toDouble(key));
      return result == null ? Boolean.FALSE.booleanValue() : result.booleanValue(); // pzopt: decompiler fix, unboxed per branch as in the jar
   }

   public boolean onConsumeKeyPress(int key) {
      this.onKeyPress(key);
      return this.isKeyConsumed(key);
   }

   public void onKeyPress(int key) {
      Object functionObj = this.tryGetTableValue("onKeyPress");
      if (functionObj != null) {
         LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), functionObj, this.getTable(), BoxedStaticValues.toDouble(key));
      }
   }

   public boolean onConsumeKeyRepeat(int key) {
      this.onKeyRepeat(key);
      return this.isKeyConsumed(key);
   }

   public void onKeyRepeat(int key) {
      Object functionObj = this.tryGetTableValue("onKeyRepeat");
      if (functionObj != null) {
         LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), functionObj, this.getTable(), BoxedStaticValues.toDouble(key));
      }
   }

   public boolean onConsumeKeyRelease(int key) {
      this.onKeyRelease(key);
      return this.isKeyConsumed(key);
   }

   public void onKeyRelease(int key) {
      Object functionObj = this.tryGetTableValue("onKeyRelease");
      if (functionObj != null) {
         LuaManager.caller.pcallvoid(UIManager.getDefaultThread(), functionObj, this.getTable(), BoxedStaticValues.toDouble(key));
      }
   }

   public boolean isForceCursorVisible() {
      return this.forceCursorVisible;
   }

   public void setForceCursorVisible(boolean force) {
      this.forceCursorVisible = force;
   }

   public void StartOutline(Texture tex, float outlineThickness, float r, float g, float b, float a) {
      if (OutlineShader.instance.StartShader()) {
         this.shaderStarted = true;
         OutlineShader.instance.setOutlineColor(r, g, b, a);
         if (tex != null) {
            OutlineShader.instance.setStepSize(outlineThickness, tex.getWidthOrig(), tex.getHeightOrig());
         }
      }
   }

   public void EndOutline() {
      if (this.shaderStarted) {
         IndieGL.EndShader();
      }
   }
}
