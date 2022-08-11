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
public class CapsuleCollider extends AbstractCollider {

    private final Vector3f start;
    private final Vector3f end;
    private final float radius;

    @SuppressWarnings("unused")
    public CapsuleCollider(Vector3f start, Vector3f end, float radius, int area, float flagMergeThreshold) {
        super(area, flagMergeThreshold, bounds(start, end, radius));
        this.start = start;
        this.end = end;
        this.radius = radius;
    }

    @Override
    public void rasterize(Heightfield hf, Telemetry telemetry) {
        RecastFilledVolumeRasterization.rasterizeCapsule(hf, start, end, radius, area, (int) Math.floor(flagMergeThreshold / hf.cellHeight),
                telemetry);
    }

    private static float[] bounds(Vector3f start, Vector3f end, float radius) {
        return new float[] { Math.min(start.x, end.x) - radius, Math.min(start.y, end.y) - radius,
                Math.min(start.z, end.z) - radius, Math.max(start.x, end.x) + radius, Math.max(start.y, end.y) + radius,
                Math.max(start.z, end.z) + radius };
    }

}
