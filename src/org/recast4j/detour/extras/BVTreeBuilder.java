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

package org.recast4j.detour.extras;

import static jdk.nashorn.internal.objects.NativeMath.max;
import static jdk.nashorn.internal.objects.NativeMath.min;
import static org.recast4j.detour.DetourCommon.clamp;
import static org.recast4j.recast.RecastVectors.copy;

import org.joml.Vector3f;
import org.recast4j.detour.BVNode;
import org.recast4j.detour.MeshData;
import org.recast4j.detour.NavMeshBuilder;
import org.recast4j.detour.NavMeshBuilder.BVItem;

public class BVTreeBuilder {

    public void build(MeshData data) {
        data.bvTree = new BVNode[data.header.polyCount * 2];
        data.header.bvNodeCount = data.bvTree.length == 0 ? 0
                : createBVTree(data, data.bvTree, data.header.bvQuantFactor);
    }

    private static int createBVTree(MeshData data, BVNode[] nodes, float quantFactor) {
        BVItem[] items = new BVItem[data.header.polyCount];
        for (int i = 0; i < data.header.polyCount; i++) {
            BVItem it = new BVItem();
            items[i] = it;
            it.i = i;
            Vector3f bmin = new Vector3f();
            Vector3f bmax = new Vector3f();
            copy(bmin, data.verts, data.polys[i].verts[0] * 3);
            copy(bmax, data.verts, data.polys[i].verts[0] * 3);
            for (int j = 1; j < data.polys[i].vertCount; j++) {
                min(bmin, data.verts, data.polys[i].verts[j] * 3);
                max(bmax, data.verts, data.polys[i].verts[j] * 3);
            }
            it.bmin[0] = clamp((int) ((bmin.x - data.header.bmin.x) * quantFactor), 0, 0x7fffffff);
            it.bmin[1] = clamp((int) ((bmin.y - data.header.bmin.y) * quantFactor), 0, 0x7fffffff);
            it.bmin[2] = clamp((int) ((bmin.z - data.header.bmin.z) * quantFactor), 0, 0x7fffffff);
            it.bmax[0] = clamp((int) ((bmax.x - data.header.bmin.x) * quantFactor), 0, 0x7fffffff);
            it.bmax[1] = clamp((int) ((bmax.y - data.header.bmin.y) * quantFactor), 0, 0x7fffffff);
            it.bmax[2] = clamp((int) ((bmax.z - data.header.bmin.z) * quantFactor), 0, 0x7fffffff);
        }
        return NavMeshBuilder.subdivide(items, 0, data.header.polyCount, 0, nodes);
    }

}
