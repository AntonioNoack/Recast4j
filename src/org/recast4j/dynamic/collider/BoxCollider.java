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

package org.recast4j.dynamic.collider;

import org.joml.Vector3f;
import org.recast4j.recast.Heightfield;
import org.recast4j.recast.RecastFilledVolumeRasterization;
import org.recast4j.recast.Telemetry;

@SuppressWarnings("unused")
public class BoxCollider extends AbstractCollider {

    private final Vector3f center;
    private final float[][] halfEdges;

    @SuppressWarnings("unused")
    public BoxCollider(Vector3f center, float[][] halfEdges, int area, float flagMergeThreshold) {
        super(area, flagMergeThreshold, bounds(center, halfEdges));
        this.center = center;
        this.halfEdges = halfEdges;
    }

    private static float[] bounds(Vector3f center, float[][] halfEdges) {
        float[] bounds = new float[] { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
        for (int i = 0; i < 8; ++i) {
            float s0 = (i & 1) != 0 ? 1f : -1f;
            float s1 = (i & 2) != 0 ? 1f : -1f;
            float s2 = (i & 4) != 0 ? 1f : -1f;
            float vx = center.x + s0 * halfEdges[0][0] + s1 * halfEdges[1][0] + s2 * halfEdges[2][0];
            float vy = center.y + s0 * halfEdges[0][1] + s1 * halfEdges[1][1] + s2 * halfEdges[2][1];
            float vz = center.z + s0 * halfEdges[0][2] + s1 * halfEdges[1][2] + s2 * halfEdges[2][2];
            bounds[0] = Math.min(bounds[0], vx);
            bounds[1] = Math.min(bounds[1], vy);
            bounds[2] = Math.min(bounds[2], vz);
            bounds[3] = Math.max(bounds[3], vx);
            bounds[4] = Math.max(bounds[4], vy);
            bounds[5] = Math.max(bounds[5], vz);
        }
        return bounds;
    }

    @Override
    public void rasterize(Heightfield hf, Telemetry telemetry) {
        RecastFilledVolumeRasterization.rasterizeBox(hf, center, halfEdges, area, (int) Math.floor(flagMergeThreshold / hf.cellHeight), telemetry);
    }

}
