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

package org.recast4j.dynamic;

import java.util.Optional;
import java.util.function.BiFunction;

import org.joml.Vector3f;
import org.recast4j.recast.Heightfield;
import org.recast4j.recast.Span;

/**
 * Voxel raycast based on the algorithm described in "A Fast Voxel Traversal Algorithm for Ray Tracing" by John Amanatides and Andrew Woo
 */
public class VoxelQuery {

    private final Vector3f origin;
    private final float tileWidth, tileDepth;
    private final BiFunction<Integer, Integer, Optional<Heightfield>> heightfieldProvider;

    public VoxelQuery(Vector3f origin, float tileWidth, float tileDepth,
                      BiFunction<Integer, Integer, Optional<Heightfield>> heightfieldProvider) {
        this.origin = origin;
        this.tileWidth = tileWidth;
        this.tileDepth = tileDepth;
        this.heightfieldProvider = heightfieldProvider;
    }

    /**
     * Perform raycast using voxels heightfields.
     * @return Optional with hit parameter (t) or empty if no hit found
     */
    @SuppressWarnings("unused")
    public Optional<Float> raycast(Vector3f start, Vector3f end) {
        return traverseTiles(start, end);
    }

    private Optional<Float> traverseTiles(Vector3f start, Vector3f end) {
        float relStartX = start.x - origin.x;
        float relStartZ = start.z - origin.z;
        int sx = (int) Math.floor(relStartX / tileWidth);
        int sz = (int) Math.floor(relStartZ / tileDepth);
        int ex = (int) Math.floor((end.x - origin.x) / tileWidth);
        int ez = (int) Math.floor((end.z - origin.z) / tileDepth);
        int dx = ex - sx;
        int dz = ez - sz;
        int stepX = dx < 0 ? -1 : 1;
        int stepZ = dz < 0 ? -1 : 1;
        float xRem = (tileWidth + (relStartX % tileWidth)) % tileWidth;
        float zRem = (tileDepth + (relStartZ % tileDepth)) % tileDepth;
        float tx = end.x - start.x;
        float tz = end.z - start.z;
        float xOffest = Math.abs(tx < 0 ? xRem : tileWidth - xRem);
        float zOffest = Math.abs(tz < 0 ? zRem : tileDepth - zRem);
        tx = Math.abs(tx);
        tz = Math.abs(tz);
        float tMaxX = xOffest / tx;
        float tMaxZ = zOffest / tz;
        float tDeltaX = tileWidth / tx;
        float tDeltaZ = tileDepth / tz;
        float t = 0;
        while (true) {
            Optional<Float> hit = traversHeightfield(sx, sz, start, end, t, Math.min(1, Math.min(tMaxX, tMaxZ)));
            if (hit.isPresent()) {
                return hit;
            }
            if ((dx > 0 ? sx >= ex : sx <= ex) && (dz > 0 ? sz >= ez : sz <= ez)) {
                break;
            }
            if (tMaxX < tMaxZ) {
                t = tMaxX;
                tMaxX += tDeltaX;
                sx += stepX;
            } else {
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                sz += stepZ;
            }
        }
        return Optional.empty();
    }

    private Optional<Float> traversHeightfield(int x, int z, Vector3f start, Vector3f end, float tMin, float tMax) {
        Optional<Heightfield> ohf = heightfieldProvider.apply(x, z);
        if (ohf.isPresent()) {
            Heightfield hf = ohf.get();
            float tx = end.x - start.x;
            float ty = end.y - start.y;
            float tz = end.z - start.z;
            Vector3f entry = new Vector3f(start.x + tMin * tx, start.y + tMin * ty, start.z + tMin * tz);
            Vector3f exit = new Vector3f(start.x + tMax * tx, start.y + tMax * ty, start.z + tMax * tz);
            float relStartX = entry.x - hf.bmin.x;
            float relStartZ = entry.z - hf.bmin.z;
            int sx = (int) Math.floor(relStartX / hf.cellSize);
            int sz = (int) Math.floor(relStartZ / hf.cellSize);
            int ex = (int) Math.floor((exit.x - hf.bmin.x) / hf.cellSize);
            int ez = (int) Math.floor((exit.z - hf.bmin.z) / hf.cellSize);
            int dx = ex - sx;
            int dz = ez - sz;
            int stepX = dx < 0 ? -1 : 1;
            int stepZ = dz < 0 ? -1 : 1;
            float xRem = (hf.cellSize + (relStartX % hf.cellSize)) % hf.cellSize;
            float zRem = (hf.cellSize + (relStartZ % hf.cellSize)) % hf.cellSize;
            float xOffest = Math.abs(tx < 0 ? xRem : hf.cellSize - xRem);
            float zOffest = Math.abs(tz < 0 ? zRem : hf.cellSize - zRem);
            tx = Math.abs(tx);
            tz = Math.abs(tz);
            float tMaxX = xOffest / tx;
            float tMaxZ = zOffest / tz;
            float tDeltaX = hf.cellSize / tx;
            float tDeltaZ = hf.cellSize / tz;
            float t = 0;
            while (true) {
                if (sx >= 0 && sx < hf.width && sz >= 0 && sz < hf.height) {
                    float y1 = start.y + ty * (tMin + t) - hf.bmin.y;
                    float y2 = start.y + ty * (tMin + Math.min(tMaxX, tMaxZ)) - hf.bmin.y;
                    float minY = Math.min(y1, y2) / hf.cellHeight;
                    float maxY = Math.max(y1, y2) / hf.cellHeight;
                    Span span = hf.spans[sx + sz * hf.width];
                    while (span != null) {
                        if (span.min <= minY && span.max >= maxY) {
                            return Optional.of(Math.min(1, tMin + t));
                        }
                        span = span.next;
                    }
                }
                if ((dx > 0 ? sx >= ex : sx <= ex) && (dz > 0 ? sz >= ez : sz <= ez)) {
                    break;
                }
                if (tMaxX < tMaxZ) {
                    t = tMaxX;
                    tMaxX += tDeltaX;
                    sx += stepX;
                } else {
                    t = tMaxZ;
                    tMaxZ += tDeltaZ;
                    sz += stepZ;
                }
            }
        }
        return Optional.empty();
    }

}
