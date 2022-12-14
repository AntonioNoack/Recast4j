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
package org.recast4j.recast;

import org.joml.Vector3f;
import org.recast4j.Vectors;

import static org.joml.Math.clamp;
import static org.recast4j.recast.RecastConstants.SPAN_MAX_HEIGHT;

public class RecastRasterization {

    private static boolean overlapBounds(Vector3f amin, Vector3f amax, Vector3f bmin, Vector3f bmax) {
        return !(amin.x > bmax.x) && !(amax.x < bmin.x) &&
                !(amin.y > bmax.y) && !(amax.y < bmin.y) &&
                !(amin.z > bmax.z) && !(amax.z < bmin.z);
    }

    /**
     * The span addition can be set to favor flags. If the span is merged to another span and the new 'smax' is within
     * 'flagMergeThr' units from the existing span, the span flags are merged.
     *
     * @see Heightfield, Span.
     */
    public static void addSpan(Heightfield hf, int x, int y, int smin, int smax, int area, int flagMergeThr) {

        int idx = x + y * hf.width;

        Span s = new Span();
        s.min = smin;
        s.max = smax;
        s.area = area;
        s.next = null;

        // Empty cell, add the first span.
        if (hf.spans[idx] == null) {
            hf.spans[idx] = s;
            return;
        }
        Span prev = null;
        Span cur = hf.spans[idx];

        // Insert and merge spans.
        while (cur != null) {
            if (cur.min > s.max) {
                // Current span is further than the new span, break.
                break;
            } else if (cur.max < s.min) {
                // Current span is before the new span advance.
                prev = cur;
                cur = cur.next;
            } else {
                // Merge spans.
                if (cur.min < s.min)
                    s.min = cur.min;
                if (cur.max > s.max)
                    s.max = cur.max;

                // Merge flags.
                if (Math.abs(s.max - cur.max) <= flagMergeThr)
                    s.area = Math.max(s.area, cur.area);

                // Remove current span.
                Span next = cur.next;
                if (prev != null)
                    prev.next = next;
                else
                    hf.spans[idx] = next;
                cur = next;
            }
        }

        // Insert new span.
        if (prev != null) {
            s.next = prev.next;
            prev.next = s;
        } else {
            s.next = hf.spans[idx];
            hf.spans[idx] = s;
        }
    }

    // divides a convex polygons into two convex polygons on both sides of a line
    private static int[] dividePoly(float[] buf, int in, int nin, int out1, int out2, float x, int axis) {
        float[] d = new float[12];
        for (int i = 0; i < nin; ++i)
            d[i] = x - buf[in + i * 3 + axis];

        int m = 0, n = 0;
        for (int i = 0, j = nin - 1; i < nin; j = i, ++i) {
            boolean ina = d[j] >= 0;
            boolean inb = d[i] >= 0;
            if (ina != inb) {
                float s = d[j] / (d[j] - d[i]);
                buf[out1 + m * 3] = buf[in + j * 3] + (buf[in + i * 3] - buf[in + j * 3]) * s;
                buf[out1 + m * 3 + 1] = buf[in + j * 3 + 1] + (buf[in + i * 3 + 1] - buf[in + j * 3 + 1]) * s;
                buf[out1 + m * 3 + 2] = buf[in + j * 3 + 2] + (buf[in + i * 3 + 2] - buf[in + j * 3 + 2]) * s;
                Vectors.copy(buf, out2 + n * 3, buf, out1 + m * 3);
                m++;
                n++;
                // add the i'th point to the right polygon. Do NOT add points that are on the dividing line
                // since these were already added above
                if (d[i] > 0) {
                    Vectors.copy(buf, out1 + m * 3, buf, in + i * 3);
                    m++;
                } else if (d[i] < 0) {
                    Vectors.copy(buf, out2 + n * 3, buf, in + i * 3);
                    n++;
                }
            } else // same side
            {
                // add the i'th point to the right polygon. Addition is done even for points on the dividing line
                if (d[i] >= 0) {
                    Vectors.copy(buf, out1 + m * 3, buf, in + i * 3);
                    m++;
                    if (d[i] != 0)
                        continue;
                }
                Vectors.copy(buf, out2 + n * 3, buf, in + i * 3);
                n++;
            }
        }
        return new int[]{m, n};
    }

    private static void rasterizeTri(float[] vertices, int v0, int v1, int v2, int area, Heightfield hf, Vector3f bmin, Vector3f bmax,
                                     float cs, float ics, float ich, int flagMergeThr) {
        int w = hf.width;
        int h = hf.height;
        Vector3f tmin = new Vector3f(), tmax = new Vector3f();
        float by = bmax.y - bmin.y;

        // Calculate the bounding box of the triangle.
        Vectors.copy(tmin, vertices, v0 * 3);
        Vectors.copy(tmax, vertices, v0 * 3);
        Vectors.min(tmin, vertices, v1 * 3);
        Vectors.min(tmin, vertices, v2 * 3);
        Vectors.max(tmax, vertices, v1 * 3);
        Vectors.max(tmax, vertices, v2 * 3);

        // If the triangle does not touch the bbox of the heightfield, skip the triagle.
        if (!overlapBounds(bmin, bmax, tmin, tmax))
            return;

        // Calculate the footprint of the triangle on the grid's y-axis
        int y0 = (int) ((tmin.z - bmin.z) * ics);
        int y1 = (int) ((tmax.z - bmin.z) * ics);
        // use -1 rather than 0 to cut the polygon properly at the start of the tile
        y0 = clamp(y0, -1, h - 1);
        y1 = clamp(y1, 0, h - 1);

        // Clip the triangle into all grid cells it touches.
        float[] buf = new float[7 * 3 * 4];
        int in = 0;
        int inrow = 7 * 3;
        int p1 = inrow + 7 * 3;
        int p2 = p1 + 7 * 3;

        Vectors.copy(buf, 0, vertices, v0 * 3);
        Vectors.copy(buf, 3, vertices, v1 * 3);
        Vectors.copy(buf, 6, vertices, v2 * 3);
        int nvrow, nvIn = 3;

        for (int y = y0; y <= y1; ++y) {
            // Clip polygon to row. Store the remaining polygon as well
            float cz = bmin.z + y * cs;
            int[] nvrowin = dividePoly(buf, in, nvIn, inrow, p1, cz + cs, 2);
            nvrow = nvrowin[0];
            nvIn = nvrowin[1];
            {
                int temp = in;
                in = p1;
                p1 = temp;
            }
            if (nvrow < 3 || y < 0) {
                continue;
            }
            // find the horizontal bounds in the row
            float minX = buf[inrow], maxX = buf[inrow];
            for (int i = 1; i < nvrow; ++i) {
                float v = buf[inrow + i * 3];
                minX = Math.min(minX, v);
                maxX = Math.max(maxX, v);
            }
            int x0 = (int) ((minX - bmin.x) * ics);
            int x1 = (int) ((maxX - bmin.x) * ics);
            if (x1 < 0 || x0 >= w) {
                continue;
            }
            x0 = clamp(x0, -1, w - 1);
            x1 = clamp(x1, 0, w - 1);

            int nv, nv2 = nvrow;
            for (int x = x0; x <= x1; ++x) {
                // Clip polygon to column. store the remaining polygon as well
                float cx = bmin.x + x * cs;
                int[] nvnv2 = dividePoly(buf, inrow, nv2, p1, p2, cx + cs, 0);
                nv = nvnv2[0];
                nv2 = nvnv2[1];
                {
                    int temp = inrow;
                    inrow = p2;
                    p2 = temp;
                }
                if (nv < 3 || x < 0) {
                    continue;
                }

                // Calculate min and max of the span.
                float smin = buf[p1 + 1], smax = buf[p1 + 1];
                for (int i = 1; i < nv; ++i) {
                    smin = Math.min(smin, buf[p1 + i * 3 + 1]);
                    smax = Math.max(smax, buf[p1 + i * 3 + 1]);
                }
                smin -= bmin.y;
                smax -= bmin.y;
                // Skip the span if it is outside the heightfield bbox
                if (smax < 0f || smin > by)
                    continue;
                // Clamp the span to the heightfield bbox.
                if (smin < 0f || smax > by)
                    smax = by;

                // Snap the span to the heightfield height grid.
                int ismin = clamp((int) Math.floor(smin * ich), 0, SPAN_MAX_HEIGHT);
                int ismax = clamp((int) Math.ceil(smax * ich), ismin + 1, SPAN_MAX_HEIGHT);

                addSpan(hf, x, y, ismin, ismax, area, flagMergeThr);
            }
        }
    }

    /**
     * No spans will be added if the triangle does not overlap the heightfield grid.
     *
     * @see Heightfield
     */
    public static void rasterizeTriangle(Heightfield solid, float[] vertices, int v0, int v1, int v2, int area, int flagMergeThr,
                                         Telemetry ctx) {

        if (ctx != null) ctx.startTimer("RASTERIZE_TRIANGLES");

        float ics = 1f / solid.cellSize;
        float ich = 1f / solid.cellHeight;
        rasterizeTri(vertices, v0, v1, v2, area, solid, solid.bmin, solid.bmax, solid.cellSize, ics, ich, flagMergeThr);

        if (ctx != null) ctx.stopTimer("RASTERIZE_TRIANGLES");
    }

    /**
     * Spans will only be added for triangles that overlap the heightfield grid.
     *
     * @see Heightfield
     */
    public static void rasterizeTriangles(Heightfield solid, float[] vertices, int[] tris, int[] areas, int nt, int flagMergeThr,
                                          Telemetry ctx) {

        if (ctx != null) ctx.startTimer("RASTERIZE_TRIANGLES");

        float ics = 1f / solid.cellSize;
        float ich = 1f / solid.cellHeight;
        // Rasterize triangles.
        for (int i = 0; i < nt; ++i) {
            int v0 = tris[i * 3];
            int v1 = tris[i * 3 + 1];
            int v2 = tris[i * 3 + 2];
            // Rasterize.
            rasterizeTri(vertices, v0, v1, v2, areas[i], solid, solid.bmin, solid.bmax, solid.cellSize, ics, ich, flagMergeThr);
        }

        if (ctx != null) ctx.stopTimer("RASTERIZE_TRIANGLES");
    }

    /**
     * Spans will only be added for triangles that overlap the heightfield grid.
     *
     * @see Heightfield
     */
    @SuppressWarnings("unused")
    public static void rasterizeTriangles(Heightfield solid, float[] vertices, int[] areas, int nt, int flagMergeThr,
                                          Telemetry ctx) {
        if (ctx != null) ctx.startTimer("RASTERIZE_TRIANGLES");

        float ics = 1f / solid.cellSize;
        float ich = 1f / solid.cellHeight;
        // Rasterize triangles.
        for (int i = 0; i < nt; ++i) {
            int v0 = (i * 3);
            int v1 = (i * 3 + 1);
            int v2 = (i * 3 + 2);
            // Rasterize.
            rasterizeTri(vertices, v0, v1, v2, areas[i], solid, solid.bmin, solid.bmax, solid.cellSize, ics, ich, flagMergeThr);
        }
        if (ctx != null) ctx.stopTimer("RASTERIZE_TRIANGLES");
    }

}
