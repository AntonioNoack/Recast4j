/*
Copyright (c) 2009-2010 Mikko Mononen memon@inside.org
recast4j Copyright (c) 2015-2019 Piotr Piastucki piotr@jtilia.org

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.
Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:
1. The origin of this software must not be misrepresented; you must not
 claim that you wrote the original software. If you use this software
 in a product, an acknowledgment in the product documentation would be
 appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be
 misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
*/
package org.recast4j;

import kotlin.Pair;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.recast4j.detour.BVNode;
import org.recast4j.detour.VectorPtr;

import java.util.Optional;

public class Vectors {

    public static void min(Vector3f a, float[] b, int i) {
        a.x = Math.min(a.x, b[i]);
        a.y = Math.min(a.y, b[i + 1]);
        a.z = Math.min(a.z, b[i + 2]);
    }

    public static void max(Vector3f a, float[] b, int i) {
        a.x = Math.max(a.x, b[i]);
        a.y = Math.max(a.y, b[i + 1]);
        a.z = Math.max(a.z, b[i + 2]);
    }

    public static void copy(float[] out, float[] in, int i) {
        copy(out, 0, in, i);
    }

    public static void copy(Vector3f out, float[] in, int i) {
        out.set(in[i], in[i + 1], in[i + 2]);
    }

    public static void copy(float[] out, float[] in) {
        copy(out, 0, in, 0);
    }

    public static void copy(float[] out, int n, float[] in, int m) {
        out[n] = in[m];
        out[n + 1] = in[m + 1];
        out[n + 2] = in[m + 2];
    }

    public static void add(float[] dst, float[] a, float[] vertices, int i) {
        dst[0] = a[0] + vertices[i];
        dst[1] = a[1] + vertices[i + 1];
        dst[2] = a[2] + vertices[i + 2];
    }

    public static void sub(Vector3f dst, float[] vertices, int i, int j) {
        dst.x = vertices[i] - vertices[j];
        dst.y = vertices[i + 1] - vertices[j + 1];
        dst.z = vertices[i + 2] - vertices[j + 2];
    }

    public static void sub(float[] dst, float[] i, float[] vertices, int j) {
        dst[0] = i[0] - vertices[j];
        dst[1] = i[1] - vertices[j + 1];
        dst[2] = i[2] - vertices[j + 2];
    }

    public static void cross(Vector3f dest, Vector3f v1, Vector3f v2) {
        v1.cross(v2, dest);
    }

    public static void normalize(float[] v) {
        float d = (float) (1f / Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
        v[0] *= d;
        v[1] *= d;
        v[2] *= d;
    }

    public static float dot(float[] v1, float[] v2) {
        return v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2];
    }

    static float EPS = 1e-4f;

    /**
     * a + b * s
     */
    public static Vector3f mad(Vector3f a, Vector3f b, float f) {
        return new Vector3f(b).mul(f).add(a);
    }

    /**
     * a += b * s
     */
    public static void mad2(Vector3f a, Vector3f b, float f) {
        a.add(b.x * f, b.y * f, b.z * f);
    }

    public static Vector3f lerp(float[] vertices, int v1, int v2, float t) {
        Vector3f dest = new Vector3f();
        dest.x = vertices[v1] + (vertices[v2] - vertices[v1]) * t;
        dest.y = vertices[v1 + 1] + (vertices[v2 + 1] - vertices[v1 + 1]) * t;
        dest.z = vertices[v1 + 2] + (vertices[v2 + 2] - vertices[v1 + 2]) * t;
        return dest;
    }

    public static Vector3f lerp(Vector3f v1, Vector3f v2, float t) {
        return new Vector3f(v1).lerp(v2, t);
    }

    public static Vector3f sub(VectorPtr v1, VectorPtr v2) {
        return new Vector3f(v1.get(0) - v2.get(0), v1.get(1) - v2.get(1), v1.get(2) - v2.get(2));
    }

    public static Vector3f sub(Vector3f v1, VectorPtr v2) {
        return new Vector3f(v1.get(0) - v2.get(0), v1.get(1) - v2.get(1), v1.get(2) - v2.get(2));
    }

    public static Vector3f sub(Vector3f v1, Vector3f v2) {
        return new Vector3f(v1).sub(v2);
    }

    public static Vector3f add(Vector3f v1, Vector3f v2) {
        return new Vector3f(v1).add(v2);
    }

    public static Vector3f copy(Vector3f in) {
        return new Vector3f(in);
    }

    public static void set(Vector3f out, float a, float b, float c) {
        out.set(a, b, c);
    }

    public static void copy(Vector3f out, Vector3f in) {
        out.set(in);
    }

    public static void copy(float[] out, int o, Vector3f in) {
        out[o] = in.x;
        out[o + 1] = in.y;
        out[o + 2] = in.z;
    }

    public static void copy(Vector3f out, int[] in, int i) {
        out.set(in[i], in[i + 1], in[i + 2]);
    }

    public static float sqr(float a) {
        return a * a;
    }

    /**
     * Derives the distance between the specified points on the xz-plane.
     */
    public static float dist2D(Vector3f v1, Vector3f v2) {
        float dx = v2.x - v1.x;
        float dz = v2.z - v1.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    public static float dist2DSqr(Vector3f v1, Vector3f v2) {
        float dx = v2.x - v1.x;
        float dz = v2.z - v1.z;
        return dx * dx + dz * dz;
    }

    public static float dist2DSqr(Vector3f p, float[] vertices, int i) {
        float dx = vertices[i] - p.x;
        float dz = vertices[i + 2] - p.z;
        return dx * dx + dz * dz;
    }

    private static final float EQUAL_THRESHOLD = sqr(1f / 16384f);

    /**
     * Performs a 'sloppy' co-location check of the specified points.
     *
     * @return True if the points are considered to be at the same location.
     */
    public static boolean vEqual(Vector3f p0, Vector3f p1) {
        return p0.distanceSquared(p1) < EQUAL_THRESHOLD;
    }

    /// Derives the dot product of two vectors on the xz-plane. (@p u . @p v)
    /// @param[in] u A vector [(x, y, z)]
    /// @param[in] v A vector [(x, y, z)]
    /// @return The dot product on the xz-plane.
    ///
    /// The vectors are projected onto the xz-plane, so the y-values are
    /// ignored.
    public static float dot2D(Vector3f u, Vector3f v) {
        return u.x * v.x + u.z * v.z;
    }

    static float dot2D(Vector3f u, float[] v, int vi) {
        return u.x * v[vi] + u.z * v[vi + 2];
    }

    /// @}
    /// @name Computational geometry helper functions.
    /// @{

    /// Derives the signed xz-plane area of the triangle ABC, or the
    /// relationship of line AB to point C.
    /// @param[in] a Vertex A. [(x, y, z)]
    /// @param[in] b Vertex B. [(x, y, z)]
    /// @param[in] c Vertex C. [(x, y, z)]
    /// @return The signed xz-plane area of the triangle.
    public static float triArea2D(float[] vertices, int a, int b, int c) {
        float abx = vertices[b] - vertices[a];
        float abz = vertices[b + 2] - vertices[a + 2];
        float acx = vertices[c] - vertices[a];
        float acz = vertices[c + 2] - vertices[a + 2];
        return acx * abz - abx * acz;
    }

    public static float triArea2D(Vector3f a, Vector3f b, Vector3f c) {
        float abx = b.x - a.x;
        float abz = b.z - a.z;
        float acx = c.x - a.x;
        float acz = c.z - a.z;
        return acx * abz - abx * acz;
    }

    /**
     * Determines if two axis-aligned bounding boxes overlap.
     */
    public static boolean overlapQuantBounds(Vector3i amin, Vector3i amax, BVNode n) {
        return amin.x <= n.maxX && amax.x >= n.minX &&
                amin.y <= n.maxY && amax.y >= n.minY &&
                amin.z <= n.maxZ && amax.z >= n.minZ;
    }

    /**
     * Determines if two axis-aligned bounding boxes overlap.
     */
    public static boolean overlapBounds(Vector3f amin, Vector3f amax, Vector3f bmin, Vector3f bmax) {
        return !(amin.x > bmax.x) && !(amax.x < bmin.x) &&
                !(amin.y > bmax.y) && !(amax.y < bmin.y) &&
                !(amin.z > bmax.z) && !(amax.z < bmin.z);
    }

    public static Pair<Float, Float> distancePtSegSqr2D(Vector3f pt, Vector3f p, Vector3f q) {
        float pqx = q.x - p.x;
        float pqz = q.z - p.z;
        float dx = pt.x - p.x;
        float dz = pt.z - p.z;
        float d = pqx * pqx + pqz * pqz;
        float t = pqx * dx + pqz * dz;
        if (d > 0) {
            t /= d;
        }
        if (t < 0) {
            t = 0;
        } else if (t > 1) {
            t = 1;
        }
        dx = p.x + t * pqx - pt.x;
        dz = p.z + t * pqz - pt.z;
        return new Pair<>(dx * dx + dz * dz, t);
    }

    public static float closestHeightPointTriangle(Vector3f p, Vector3f a, Vector3f b, Vector3f c) {
        Vector3f v0 = sub(c, a);
        Vector3f v1 = sub(b, a);
        Vector3f v2 = sub(p, a);

        // Compute scaled barycentric coordinates
        float denom = v0.x * v1.z - v0.z * v1.x;
        if (Math.abs(denom) < EPS) {
            return Float.NaN;
        }

        float u = v1.z * v2.x - v1.x * v2.z;
        float v = v0.x * v2.z - v0.z * v2.x;

        if (denom < 0) {
            denom = -denom;
            u = -u;
            v = -v;
        }

        // If point lies inside the triangle, return interpolated y-coord.
        if (u >= 0f && v >= 0f && (u + v) <= denom) {
            return a.y + (v0.y * u + v1.y * v) / denom;
        } else return Float.NaN;
    }

    /// @par
    ///
    /// All points are projected onto the xz-plane, so the y-values are ignored.
    public static boolean pointInPolygon(Vector3f pt, float[] vertices, int numVertices) {
        int i, j;
        boolean c = false;
        for (i = 0, j = numVertices - 1; i < numVertices; j = i++) {
            int vi = i * 3;
            int vj = j * 3;
            if (((vertices[vi + 2] > pt.z) != (vertices[vj + 2] > pt.z)) && (pt.x < (vertices[vj] - vertices[vi])
                    * (pt.z - vertices[vi + 2]) / (vertices[vj + 2] - vertices[vi + 2]) + vertices[vi])) {
                c = !c;
            }
        }
        return c;
    }

    public static boolean distancePtPolyEdgesSqr(Vector3f pt, float[] vertices, int numVertices, float[] ed, float[] et) {
        int i, j;
        boolean c = false;
        for (i = 0, j = numVertices - 1; i < numVertices; j = i++) {
            int vi = i * 3;
            int vj = j * 3;
            if (((vertices[vi + 2] > pt.z) != (vertices[vj + 2] > pt.z)) && (pt.x < (vertices[vj] - vertices[vi])
                    * (pt.z - vertices[vi + 2]) / (vertices[vj + 2] - vertices[vi + 2]) + vertices[vi])) {
                c = !c;
            }
            Pair<Float, Float> dist = distancePtSegSqr2D(pt, vertices, vj, vi);
            ed[j] = dist.getFirst();
            et[j] = dist.getSecond();
        }
        return c;
    }

    static float[] projectPoly(Vector3f axis, float[] polygons, int numPolygons) {
        float rmin, rmax;
        rmin = rmax = dot2D(axis, polygons, 0);
        for (int i = 1; i < numPolygons; ++i) {
            float d = dot2D(axis, polygons, i * 3);
            rmin = Math.min(rmin, d);
            rmax = Math.max(rmax, d);
        }
        return new float[]{rmin, rmax};
    }

    public static boolean overlapRange(float amin, float amax, float bmin, float bmax, float eps) {
        return !((amin + eps) > bmax) && !((amax - eps) < bmin);
    }

    public static boolean overlapRange(float amin, float amax, float bmin, float bmax) {
        return !(amin > bmax) && !(amax < bmin);
    }

    public static boolean overlapRange(int amin, int amax, int bmin, int bmax) {
        return amin <= bmax && amax >= bmin;
    }

    static float eps = 1e-4f;

    /**
     * All vertices are projected onto the xz-plane, so the y-values are ignored.
     */
    public static boolean overlapPolyPoly2D(float[] polya, int npolya, float[] polyb, int npolyb) {

        for (int i = 0, j = npolya - 1; i < npolya; j = i++) {
            int va = j * 3;
            int vb = i * 3;

            Vector3f n = new Vector3f(polya[vb + 2] - polya[va + 2], 0, -(polya[vb] - polya[va]));

            float[] aminmax = projectPoly(n, polya, npolya);
            float[] bminmax = projectPoly(n, polyb, npolyb);
            if (!overlapRange(aminmax[0], aminmax[1], bminmax[0], bminmax[1], eps)) {
                // Found separating axis
                return false;
            }
        }
        for (int i = 0, j = npolyb - 1; i < npolyb; j = i++) {
            int va = j * 3;
            int vb = i * 3;

            Vector3f n = new Vector3f(polyb[vb + 2] - polyb[va + 2], 0, -(polyb[vb] - polyb[va]));

            float[] aminmax = projectPoly(n, polya, npolya);
            float[] bminmax = projectPoly(n, polyb, npolyb);
            if (!overlapRange(aminmax[0], aminmax[1], bminmax[0], bminmax[1], eps)) {
                // Found separating axis
                return false;
            }
        }
        return true;
    }

    // Returns a random point in a convex polygon.
    // Adapted from Graphics Gems article.
    public static Vector3f randomPointInConvexPoly(float[] pts, int npts, float[] areas, float s, float t) {
        // Calc triangle araes
        float areasum = 0f;
        for (int i = 2; i < npts; i++) {
            areas[i] = triArea2D(pts, 0, (i - 1) * 3, i * 3);
            areasum += Math.max(0.001f, areas[i]);
        }
        // Find sub triangle weighted by area.
        float thr = s * areasum;
        float acc = 0f;
        float u = 1f;
        int tri = npts - 1;
        for (int i = 2; i < npts; i++) {
            float dacc = areas[i];
            if (thr >= acc && thr < (acc + dacc)) {
                u = (thr - acc) / dacc;
                tri = i;
                break;
            }
            acc += dacc;
        }

        float v = (float) Math.sqrt(t);

        float a = 1 - v;
        float b = (1 - u) * v;
        float c = u * v;
        int pa = 0;
        int pb = (tri - 1) * 3;
        int pc = tri * 3;

        return new Vector3f(a * pts[pa] + b * pts[pb] + c * pts[pc],
                a * pts[pa + 1] + b * pts[pb + 1] + c * pts[pc + 1],
                a * pts[pa + 2] + b * pts[pb + 2] + c * pts[pc + 2]);
    }

    public static int nextPow2(int v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v++;
        return v;
    }

    public static int ilog2(int v) {
        int r;
        int shift;
        r = (v > 0xffff ? 1 : 0) << 4;
        v >>= r;
        shift = (v > 0xff ? 1 : 0) << 3;
        v >>= shift;
        r |= shift;
        shift = (v > 0xf ? 1 : 0) << 2;
        v >>= shift;
        r |= shift;
        shift = (v > 0x3 ? 1 : 0) << 1;
        v >>= shift;
        r |= shift;
        r |= (v >> 1);
        return r;
    }

    public static class IntersectResult {
        public boolean intersects;
        public float tmin;
        public float tmax = 1f;
        public int segMin = -1;
        public int segMax = -1;
    }

    public static IntersectResult intersectSegmentPoly2D(Vector3f p0, Vector3f p1, float[] vertices, int nvertices) {

        IntersectResult result = new IntersectResult();
        float EPS = 0.00000001f;
        Vector3f dir = sub(p1, p0);

        for (int i = 0, j = nvertices - 1; i < nvertices; j = i++) {
            VectorPtr vpj = new VectorPtr(vertices, j * 3);
            Vector3f edge = sub(new VectorPtr(vertices, i * 3), vpj);
            Vector3f diff = sub(p0, vpj);
            float n = -crossXZ(edge, diff);
            float d = -crossXZ(dir, edge);
            if (Math.abs(d) < EPS) {
                // S is nearly parallel to this edge
                if (n < 0) {
                    return result;
                } else {
                    continue;
                }
            }
            float t = n / d;
            if (d < 0) {
                // segment S is entering across this edge
                if (t > result.tmin) {
                    result.tmin = t;
                    result.segMin = j;
                    // S enters after leaving polygon
                    if (result.tmin > result.tmax) {
                        return result;
                    }
                }
            } else {
                // segment S is leaving across this edge
                if (t < result.tmax) {
                    result.tmax = t;
                    result.segMax = j;
                    // S leaves before entering polygon
                    if (result.tmax < result.tmin) {
                        return result;
                    }
                }
            }
        }
        result.intersects = true;
        return result;
    }

    public static Pair<Float, Float> distancePtSegSqr2D(Vector3f pt, float[] vertices, int p, int q) {
        float pqx = vertices[q] - vertices[p];
        float pqz = vertices[q + 2] - vertices[p + 2];
        float dx = pt.x - vertices[p];
        float dz = pt.z - vertices[p + 2];
        float d = pqx * pqx + pqz * pqz;
        float t = pqx * dx + pqz * dz;
        if (d > 0) {
            t /= d;
        }
        if (t < 0) {
            t = 0;
        } else if (t > 1) {
            t = 1;
        }
        dx = vertices[p] + t * pqx - pt.x;
        dz = vertices[p + 2] + t * pqz - pt.z;
        return new Pair<>(dx * dx + dz * dz, t);
    }

    public static int oppositeTile(int side) {
        return (side + 4) & 0x7;
    }

    public static float crossXZ(Vector3f a, Vector3f b) {
        return a.x * b.z - a.z * b.x;
    }

    public static Optional<Pair<Float, Float>> intersectSegSeg2D(Vector3f ap, Vector3f aq, Vector3f bp, Vector3f bq) {
        Vector3f u = sub(aq, ap);
        Vector3f v = sub(bq, bp);
        Vector3f w = sub(ap, bp);
        float d = crossXZ(u, v);
        if (Math.abs(d) < 1e-6f) {
            return Optional.empty();
        }
        float s = crossXZ(v, w) / d;
        float t = crossXZ(u, w) / d;
        return Optional.of(new Pair<>(s, t));
    }

    /**
     * Checks that the specified vector's components are all finite.
     */
    public static boolean isFinite(Vector3f v) {
        return v.isFinite();
    }

    /**
     * Checks that the specified vector's xz components are finite.
     */
    public static boolean isFinite2D(Vector3f v) {
        return Float.isFinite(v.x) && Float.isFinite(v.z);
    }

    public static void add(Vector3f dest, Vector3f v1, Vector3f v2) {
        dest.set(v1).add(v2);
    }

}