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
import org.recast4j.recast.RecastRegion.SweepSpan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.recast4j.Vectors.overlapRange;
import static org.recast4j.recast.RecastCommon.*;
import static org.recast4j.recast.RecastConstants.RC_NOT_CONNECTED;
import static org.recast4j.recast.RecastConstants.RC_NULL_AREA;

public class RecastLayers {

    static class LayerRegion {
        int id;
        int layerId;
        boolean base;
        int yMin, yMax;
        List<Integer> layers;
        List<Integer> neis;

        LayerRegion(int i) {
            id = i;
            yMin = 0xFFFF;
            layerId = 0xff;
            layers = new ArrayList<>();
            neis = new ArrayList<>();
        }

    }

    private static void addUnique(List<Integer> a, int v) {
        if (!a.contains(v)) {
            a.add(v);
        }
    }

    private static boolean contains(List<Integer> a, int v) {
        return a.contains(v);
    }

    public static HeightfieldLayer[] buildHeightfieldLayers(Telemetry ctx, CompactHeightfield chf, int walkableHeight) {

        if (ctx != null) ctx.startTimer("RC_TIMER_BUILD_LAYERS");
        int w = chf.width;
        int h = chf.height;
        int borderSize = chf.borderSize;
        int[] srcReg = new int[chf.spanCount];
        Arrays.fill(srcReg, 0xFF);
        int numSweeps = chf.width;// Math.max(chf.width, chf.height);
        SweepSpan[] sweeps = new SweepSpan[numSweeps];
        for (int i = 0; i < sweeps.length; i++) {
            sweeps[i] = new SweepSpan();
        }
        // Partition walkable area into monotone regions.
        int[] prevCount = new int[256];
        int regId = 0;
        // Sweep one line at a time.
        for (int y = borderSize; y < h - borderSize; ++y) {
            // Collect spans from this row.
            Arrays.fill(prevCount, 0, regId, 0);
            int sweepId = 0;

            for (int x = borderSize; x < w - borderSize; ++x) {
                CompactCell c = chf.cells[x + y * w];

                for (int i = c.index, ni = c.index + c.count; i < ni; ++i) {
                    CompactSpan s = chf.spans[i];
                    if (chf.areas[i] == RC_NULL_AREA)
                        continue;
                    int sid = 0xFF;
                    // -x

                    if (getCon(s, 0) != RC_NOT_CONNECTED) {
                        int ax = x + getDirOffsetX(0);
                        int ay = y + getDirOffsetY(0);
                        int ai = chf.cells[ax + ay * w].index + getCon(s, 0);
                        if (chf.areas[ai] != RC_NULL_AREA && srcReg[ai] != 0xff)
                            sid = srcReg[ai];
                    }

                    if (sid == 0xff) {
                        sid = sweepId++;
                        sweeps[sid].neighborId = 0xff;
                        sweeps[sid].numSamples = 0;
                    }

                    // -y
                    if (getCon(s, 3) != RC_NOT_CONNECTED) {
                        int ax = x + getDirOffsetX(3);
                        int ay = y + getDirOffsetY(3);
                        int ai = chf.cells[ax + ay * w].index + getCon(s, 3);
                        int nr = srcReg[ai];
                        if (nr != 0xff) {
                            // Set neighbour when first valid neighbour is
                            // encoutered.
                            if (sweeps[sid].numSamples == 0)
                                sweeps[sid].neighborId = nr;

                            if (sweeps[sid].neighborId == nr) {
                                // Update existing neighbour
                                sweeps[sid].numSamples++;
                                prevCount[nr]++;
                            } else {
                                // This is hit if there is nore than one
                                // neighbour.
                                // Invalidate the neighbour.
                                sweeps[sid].neighborId = 0xff;
                            }
                        }
                    }

                    srcReg[i] = sid;
                }
            }

            // Create unique ID.
            for (int i = 0; i < sweepId; ++i) {
                // If the neighbour is set and there is only one continuous
                // connection to it,
                // the sweep will be merged with the previous one, else new
                // region is created.
                if (sweeps[i].neighborId != 0xff && prevCount[sweeps[i].neighborId] == sweeps[i].numSamples) {
                    sweeps[i].regionId = sweeps[i].neighborId;
                } else {
                    if (regId == 255) {
                        throw new RuntimeException("rcBuildHeightfieldLayers: Region ID overflow.");
                    }
                    sweeps[i].regionId = regId++;
                }
            }

            // Remap local sweep ids to region ids.
            for (int x = borderSize; x < w - borderSize; ++x) {
                CompactCell c = chf.cells[x + y * w];
                for (int i = c.index, ni = c.index + c.count; i < ni; ++i) {
                    if (srcReg[i] != 0xff)
                        srcReg[i] = sweeps[srcReg[i]].regionId;
                }
            }
        }
        int nregs = regId;
        LayerRegion[] regions = new LayerRegion[nregs];

        // Construct regions
        for (int i = 0; i < nregs; ++i) {
            regions[i] = new LayerRegion(i);
        }

        // Find region neighbours and overlapping regions.
        List<Integer> lregs = new ArrayList<>();
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                CompactCell c = chf.cells[x + y * w];

                lregs.clear();

                for (int i = c.index, ni = c.index + c.count; i < ni; ++i) {
                    CompactSpan s = chf.spans[i];
                    int ri = srcReg[i];
                    if (ri == 0xff)
                        continue;

                    regions[ri].yMin = Math.min(regions[ri].yMin, s.y);
                    regions[ri].yMax = Math.max(regions[ri].yMax, s.y);

                    // Collect all region layers.
                    lregs.add(ri);

                    // Update neighbours
                    for (int dir = 0; dir < 4; ++dir) {
                        if (getCon(s, dir) != RC_NOT_CONNECTED) {
                            int ax = x + getDirOffsetX(dir);
                            int ay = y + getDirOffsetY(dir);
                            int ai = chf.cells[ax + ay * w].index + getCon(s, dir);
                            int rai = srcReg[ai];
                            if (rai != 0xff && rai != ri)
                                addUnique(regions[ri].neis, rai);
                        }
                    }

                }

                // Update overlapping regions.
                for (int i = 0; i < lregs.size() - 1; ++i) {
                    for (int j = i + 1; j < lregs.size(); ++j) {
                        if (lregs.get(i).intValue() != lregs.get(j).intValue()) {
                            LayerRegion ri = regions[lregs.get(i)];
                            LayerRegion rj = regions[lregs.get(j)];
                            addUnique(ri.layers, lregs.get(j));
                            addUnique(rj.layers, lregs.get(i));
                        }
                    }
                }

            }
        }

        // Create 2D layers from regions.
        int layerId = 0;

        List<Integer> stack = new ArrayList<>();

        for (int i = 0; i < nregs; ++i) {
            LayerRegion root = regions[i];
            // Skip already visited.
            if (root.layerId != 0xff)
                continue;

            // Start search.
            root.layerId = layerId;
            root.base = true;

            stack.add(i);

            while (!stack.isEmpty()) {
                // Pop front
                LayerRegion reg = regions[stack.remove(0)];

                for (int nei : reg.neis) {
                    LayerRegion region = regions[nei];
                    // Skip already visited.
                    if (region.layerId != 0xff)
                        continue;
                    // Skip if the neighbour is overlapping root region.
                    if (contains(root.layers, nei))
                        continue;
                    // Skip if the height range would become too large.
                    int yMin = Math.min(root.yMin, region.yMin);
                    int yMax = Math.max(root.yMax, region.yMax);
                    if ((yMax - yMin) >= 255)
                        continue;

                    // Deepen
                    stack.add(nei);

                    // Mark layer id
                    region.layerId = layerId;
                    // Merge current layers to root.
                    for (int layer : region.layers)
                        addUnique(root.layers, layer);
                    root.yMin = Math.min(root.yMin, region.yMin);
                    root.yMax = Math.max(root.yMax, region.yMax);
                }
            }

            layerId++;
        }

        // Merge non-overlapping regions that are close in height.
        int mergeHeight = walkableHeight * 4;

        for (int i = 0; i < nregs; ++i) {
            LayerRegion ri = regions[i];
            if (!ri.base)
                continue;

            int newId = ri.layerId;

            for (; ; ) {
                int oldId = 0xff;

                for (int j = 0; j < nregs; ++j) {
                    if (i == j)
                        continue;
                    LayerRegion rj = regions[j];
                    if (!rj.base)
                        continue;

                    // Skip if the regions are not close to each other.
                    if (!overlapRange(ri.yMin, ri.yMax + mergeHeight, rj.yMin, rj.yMax + mergeHeight))
                        continue;
                    // Skip if the height range would become too large.
                    int ymin = Math.min(ri.yMin, rj.yMin);
                    int ymax = Math.max(ri.yMax, rj.yMax);
                    if ((ymax - ymin) >= 255)
                        continue;

                    // Make sure that there is no overlap when merging 'ri' and
                    // 'rj'.
                    boolean overlap = false;
                    // Iterate over all regions which have the same layerId as
                    // 'rj'
                    for (int k = 0; k < nregs; ++k) {
                        if (regions[k].layerId != rj.layerId)
                            continue;
                        // Check if region 'k' is overlapping region 'ri'
                        // Index to 'regs' is the same as region id.
                        if (contains(ri.layers, k)) {
                            overlap = true;
                            break;
                        }
                    }
                    // Cannot merge of regions overlap.
                    if (overlap)
                        continue;

                    // Can merge i and j.
                    oldId = rj.layerId;
                    break;
                }

                // Could not find anything to merge with, stop.
                if (oldId == 0xff)
                    break;

                // Merge
                for (int j = 0; j < nregs; ++j) {
                    LayerRegion rj = regions[j];
                    if (rj.layerId == oldId) {
                        rj.base = false;
                        // Remap layerIds.
                        rj.layerId = newId;
                        // Add overlaid layers from 'rj' to 'ri'.
                        for (int layer : rj.layers)
                            addUnique(ri.layers, layer);
                        // Update height bounds.
                        ri.yMin = Math.min(ri.yMin, rj.yMin);
                        ri.yMax = Math.max(ri.yMax, rj.yMax);
                    }
                }
            }
        }

        // Compact layerIds
        int[] remap = new int[256];

        // Find number of unique layers.
        layerId = 0;
        for (int i = 0; i < nregs; ++i)
            remap[regions[i].layerId] = 1;
        for (int i = 0; i < 256; ++i) {
            if (remap[i] != 0)
                remap[i] = layerId++;
            else
                remap[i] = 0xff;
        }
        // Remap ids.
        for (int i = 0; i < nregs; ++i)
            regions[i].layerId = remap[regions[i].layerId];

        // No layers, return empty.
        if (layerId == 0) {
            // ctx.stopTimer(RC_TIMER_BUILD_LAYERS);
            return null;
        }

        // Create layers.
        // rcAssert(lset.layers == 0);

        int lw = w - borderSize * 2;
        int lh = h - borderSize * 2;

        // Build contracted bbox for layers.
        Vector3f bmin = new Vector3f(chf.bmin);
        Vector3f bmax = new Vector3f(chf.bmax);
        bmin.x += borderSize * chf.cellSize;
        bmin.z += borderSize * chf.cellSize;
        bmax.x -= borderSize * chf.cellSize;
        bmax.z -= borderSize * chf.cellSize;

        HeightfieldLayer[] lset = new HeightfieldLayer[layerId];
        for (int i = 0; i < lset.length; i++) {
            lset[i] = new HeightfieldLayer();
        }

        // Store layers.
        for (int curId = 0; curId < lset.length; ++curId) {

            HeightfieldLayer layer = lset[curId];

            int gridSize = lw * lh;

            layer.heights = new int[gridSize];
            Arrays.fill(layer.heights, 0xFF);
            layer.areas = new int[gridSize];
            layer.cons = new int[gridSize];

            // Find layer height bounds.
            int hmin = 0, hmax = 0;
            for (int j = 0; j < nregs; ++j) {
                if (regions[j].base && regions[j].layerId == curId) {
                    hmin = regions[j].yMin;
                    hmax = regions[j].yMax;
                }
            }

            layer.width = lw;
            layer.height = lh;
            layer.cellSize = chf.cellSize;
            layer.cellHeight = chf.cellHeight;

            // Adjust the bbox to fit the heightfield.
            layer.bmin.set(bmin);
            layer.bmax.set(bmax);
            layer.bmin.y = bmin.y + hmin * chf.cellHeight;
            layer.bmax.y = bmin.y + hmax * chf.cellHeight;
            layer.minH = hmin;
            layer.maxH = hmax;

            // Update usable data region.
            layer.minX = layer.width;
            layer.maxX = 0;
            layer.minZ = layer.height;
            layer.maxZ = 0;

            // Copy height and area from compact heightfield.
            for (int y = 0; y < lh; ++y) {
                for (int x = 0; x < lw; ++x) {
                    int cx = borderSize + x;
                    int cy = borderSize + y;
                    CompactCell c = chf.cells[cx + cy * w];
                    for (int j = c.index, nj = c.index + c.count; j < nj; ++j) {
                        CompactSpan s = chf.spans[j];
                        // Skip unassigned regions.
                        if (srcReg[j] == 0xff)
                            continue;
                        // Skip of does nto belong to current layer.
                        int lid = regions[srcReg[j]].layerId;
                        if (lid != curId)
                            continue;

                        // Update data bounds.
                        layer.minX = Math.min(layer.minX, x);
                        layer.maxX = Math.max(layer.maxX, x);
                        layer.minZ = Math.min(layer.minZ, y);
                        layer.maxZ = Math.max(layer.maxZ, y);

                        // Store height and area type.
                        int idx = x + y * lw;
                        layer.heights[idx] = (char) (s.y - hmin);
                        layer.areas[idx] = chf.areas[j];

                        // Check connection.
                        char portal = 0;
                        char con = 0;
                        for (int dir = 0; dir < 4; ++dir) {
                            if (getCon(s, dir) != RC_NOT_CONNECTED) {
                                int ax = cx + getDirOffsetX(dir);
                                int ay = cy + getDirOffsetY(dir);
                                int ai = chf.cells[ax + ay * w].index + getCon(s, dir);
                                int alid = srcReg[ai] != 0xff ? regions[srcReg[ai]].layerId : 0xff;
                                // Portal mask
                                if (chf.areas[ai] != RC_NULL_AREA && lid != alid) {
                                    portal |= (1 << dir);
                                    // Update height so that it matches on both
                                    // sides of the portal.
                                    CompactSpan as = chf.spans[ai];
                                    if (as.y > hmin)
                                        layer.heights[idx] = Math.max(layer.heights[idx], (char) (as.y - hmin));
                                }
                                // Valid connection mask
                                if (chf.areas[ai] != RC_NULL_AREA && lid == alid) {
                                    int nx = ax - borderSize;
                                    int ny = ay - borderSize;
                                    if (nx >= 0 && ny >= 0 && nx < lw && ny < lh)
                                        con |= (1 << dir);
                                }
                            }
                        }
                        layer.cons[idx] = (portal << 4) | con;
                    }
                }
            }

            if (layer.minX > layer.maxX)
                layer.minX = layer.maxX = 0;
            if (layer.minZ > layer.maxZ)
                layer.minZ = layer.maxZ = 0;
        }

        // ctx->stopTimer(RC_TIMER_BUILD_LAYERS);
        return lset;
    }
}
