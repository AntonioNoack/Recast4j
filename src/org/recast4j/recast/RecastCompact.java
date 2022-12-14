/*
recast4j copyright (c) 2021 Piotr Piastucki piotr@jtilia.org

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

import static org.joml.Math.clamp;
import static org.recast4j.recast.RecastConstants.RC_NOT_CONNECTED;
import static org.recast4j.recast.RecastConstants.RC_NULL_AREA;

public class RecastCompact {

    private final static int MAX_LAYERS = RC_NOT_CONNECTED - 1;
    private final static int MAX_HEIGHT = RecastConstants.SPAN_MAX_HEIGHT;

    /**
     * This is just the beginning of the process of fully building a compact heightfield.
     * Various filters may be applied, then the distance field and regions built.
     * E.g: rcBuildDistanceField and rcBuildRegions
     */
    public static CompactHeightfield buildCompactHeightfield(Telemetry ctx, int walkableHeight, int walkableClimb,
                                                             Heightfield hf) {

        if (ctx != null) ctx.startTimer("BUILD_COMPACTHEIGHTFIELD");

        CompactHeightfield chf = new CompactHeightfield();
        int w = hf.width;
        int h = hf.height;
        int spanCount = getHeightFieldSpanCount(hf);

        // Fill in header.
        chf.width = w;
        chf.height = h;
        chf.borderSize = hf.borderSize;
        chf.spanCount = spanCount;
        chf.walkableHeight = walkableHeight;
        chf.walkableClimb = walkableClimb;
        chf.maxRegions = 0;
        chf.bmin.set(hf.bmin);
        chf.bmax.set(hf.bmax);
        chf.bmax.y += walkableHeight * hf.cellHeight;
        chf.cellSize = hf.cellSize;
        chf.cellHeight = hf.cellHeight;
        chf.cells = new CompactCell[w * h];
        chf.spans = new CompactSpan[spanCount];
        chf.areas = new int[spanCount];
        for (int i = 0; i < chf.cells.length; i++) {
            chf.cells[i] = new CompactCell();
        }
        for (int i = 0; i < chf.spans.length; i++) {
            chf.spans[i] = new CompactSpan();
        }
        // Fill in cells and spans.
        int idx = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                Span s = hf.spans[x + y * w];
                // If there are no spans at this cell, just leave the data to index=0, count=0.
                if (s == null)
                    continue;
                CompactCell c = chf.cells[x + y * w];
                c.index = idx;
                c.count = 0;
                while (s != null) {
                    if (s.area != RC_NULL_AREA) {
                        int bot = s.max;
                        int top = s.next != null ? s.next.min : MAX_HEIGHT;
                        chf.spans[idx].y = clamp(bot, 0, MAX_HEIGHT);
                        chf.spans[idx].h = clamp(top - bot, 0, MAX_HEIGHT);
                        chf.areas[idx] = s.area;
                        idx++;
                        c.count++;
                    }
                    s = s.next;
                }
            }
        }

        // Find neighbour connections.
        int tooHighNeighbour = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                CompactCell c = chf.cells[x + y * w];
                for (int i = c.index, ni = c.index + c.count; i < ni; ++i) {
                    CompactSpan s = chf.spans[i];

                    for (int dir = 0; dir < 4; ++dir) {
                        RecastCommon.setCon(s, dir, RC_NOT_CONNECTED);
                        int nx = x + RecastCommon.getDirOffsetX(dir);
                        int ny = y + RecastCommon.getDirOffsetY(dir);
                        // First check that the neighbour cell is in bounds.
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h)
                            continue;

                        // Iterate over all neighbour spans and check if any of the is
                        // accessible from current cell.
                        CompactCell nc = chf.cells[nx + ny * w];
                        for (int k = nc.index, nk = nc.index + nc.count; k < nk; ++k) {
                            CompactSpan ns = chf.spans[k];
                            int bot = Math.max(s.y, ns.y);
                            int top = Math.min(s.y + s.h, ns.y + ns.h);

                            // Check that the gap between the spans is walkable,
                            // and that the climb height between the gaps is not too high.
                            if ((top - bot) >= walkableHeight && Math.abs(ns.y - s.y) <= walkableClimb) {
                                // Mark direction as walkable.
                                int lidx = k - nc.index;
                                if (lidx < 0 || lidx > MAX_LAYERS) {
                                    tooHighNeighbour = Math.max(tooHighNeighbour, lidx);
                                    continue;
                                }
                                RecastCommon.setCon(s, dir, lidx);
                                break;
                            }
                        }

                    }
                }
            }
        }

        if (tooHighNeighbour > MAX_LAYERS) {
            throw new RuntimeException("rcBuildCompactHeightfield: Heightfield has too many layers " + tooHighNeighbour
                    + " (max: " + MAX_LAYERS + ")");
        }
        if (ctx != null) ctx.stopTimer("BUILD_COMPACTHEIGHTFIELD");
        return chf;
    }

    private static int getHeightFieldSpanCount(Heightfield hf) {
        int w = hf.width;
        int h = hf.height;
        int spanCount = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                for (Span s = hf.spans[x + y * w]; s != null; s = s.next) {
                    if (s.area != RC_NULL_AREA)
                        spanCount++;
                }
            }
        }
        return spanCount;
    }

}
