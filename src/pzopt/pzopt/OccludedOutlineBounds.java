package pzopt;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Conservative screen bounds for a union of transformed model boxes. Skinning with nonnegative,
 * normalized weights stays inside the union's bounding rectangle. Invalid/cross-plane transforms
 * use the whole viewport rather than cropping a limb. Coordinates are viewport-local render pixels.
 */
final class OccludedOutlineBounds {
  private static final int PADDING =
      6; // Maximum contour radius (4), plus rasterization/jitter margin.
  private final int width;
  private final int height;
  private final float jitterX;
  private final float jitterY;
  private final Vector4f scratch = new Vector4f();
  private float minX = Float.POSITIVE_INFINITY;
  private float minY = Float.POSITIVE_INFINITY;
  private float maxX = Float.NEGATIVE_INFINITY;
  private float maxY = Float.NEGATIVE_INFINITY;
  int x, y, w, h;

  OccludedOutlineBounds(int width, int height, float jitterX, float jitterY) {
    if (width <= 0 || height <= 0 || !Float.isFinite(jitterX) || !Float.isFinite(jitterY)) {
      throw new IllegalArgumentException("Invalid outline viewport");
    }
    this.width = width;
    this.height = height;
    this.jitterX = jitterX;
    this.jitterY = jitterY;
  }

  void point(Matrix4f matrix, float x, float y, float z) {
    Vector4f p = matrix.transform(scratch.set(x, y, z, 1));
    if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || !Float.isFinite(p.w) || p.w <= 0) {
      full();
      return;
    }
    float sx = (p.x / p.w + 1) * width / 2F + jitterX;
    float sy = (p.y / p.w + 1) * height / 2F + jitterY;
    if (!Float.isFinite(sx) || !Float.isFinite(sy)) {
      full();
      return;
    }
    minX = Math.min(minX, sx);
    minY = Math.min(minY, sy);
    maxX = Math.max(maxX, sx);
    maxY = Math.max(maxY, sy);
  }

  void box(Matrix4f matrix, Vector3f lo, Vector3f hi) {
    if (lo.x > hi.x || lo.y > hi.y || lo.z > hi.z) {
      full();
      return;
    }
    for (int i = 0; i < 8; i++) {
      point(
          matrix,
          (i & 1) == 0 ? lo.x : hi.x,
          (i & 2) == 0 ? lo.y : hi.y,
          (i & 4) == 0 ? lo.z : hi.z);
    }
  }

  void full() {
    minX = minY = 0;
    maxX = width;
    maxY = height;
  }

  void finish() {
    if (minX == Float.POSITIVE_INFINITY) {
      x = y = w = h = 0;
      return;
    }
    // Clamp before converting to int, including wholly offscreen/very large projected boxes.
    x = (int) Math.max(0, Math.min(width, Math.floor(minX) - PADDING));
    y = (int) Math.max(0, Math.min(height, Math.floor(minY) - PADDING));
    int right = (int) Math.max(0, Math.min(width, Math.ceil(maxX) + PADDING));
    int top = (int) Math.max(0, Math.min(height, Math.ceil(maxY) + PADDING));
    w = Math.max(0, right - x);
    h = Math.max(0, top - y);
  }

  boolean empty() {
    return w == 0 || h == 0;
  }
}
