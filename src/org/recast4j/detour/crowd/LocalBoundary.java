/*
Copyright (c) 2009-2010 Mikko Mononen memon@inside.org
recast4j copyright (c) 2015-2019 Piotr Piastucki piotr@jtilia.org

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
package org.recast4j.detour.crowd;

import kotlin.Pair;
import org.joml.Vector3f;
import org.recast4j.LongArrayList;
import org.recast4j.detour.*;

import java.util.ArrayList;
import java.util.List;

import static org.recast4j.Vectors.*;

public class LocalBoundary {

    private static final int MAX_LOCAL_SEGS = 8;

    static class Segment {
        Vector3f start = new Vector3f(), end = new Vector3f();
        float pruningDist;
    }

    public Vector3f center = new Vector3f();
    public List<Segment> segments = new ArrayList<>();
    LongArrayList polygons = new LongArrayList();

    protected LocalBoundary() {
        center.set(Float.MAX_VALUE);
    }

    protected void reset() {
        center.set(Float.MAX_VALUE);
        polygons.clear();
        segments.clear();
    }

    protected void addSegment(float dist, float[] s) {
        // Insert neighbour based on the distance.
        Segment seg = new Segment();
        copy(seg.start, s, 0);
        copy(seg.end, s, 3);
        seg.pruningDist = dist;
        if (segments.isEmpty()) {
            segments.add(seg);
        } else if (dist >= segments.get(segments.size() - 1).pruningDist) {
            if (segments.size() >= MAX_LOCAL_SEGS) {
                return;
            }
            segments.add(seg);
        } else {
            // Insert in-between.
            int i;
            for (i = 0; i < segments.size(); ++i) {
                if (dist <= segments.get(i).pruningDist) {
                    break;
                }
            }
            segments.add(i, seg);
        }
        while (segments.size() > MAX_LOCAL_SEGS) {
            segments.remove(segments.size() - 1);
        }
    }

    public void update(long ref, Vector3f pos, float collisionQueryRange, NavMeshQuery navquery, QueryFilter filter) {
        if (ref == 0) {
            reset();
            return;
        }
        center.set(pos);
        // First query non-overlapping polygons.
        Result<FindLocalNeighbourhoodResult> res = navquery.findLocalNeighbourhood(ref, pos, collisionQueryRange, filter);
        if (res.succeeded()) {
            polygons = res.result.refs;
            segments.clear();
            // Secondly, store all polygon edges.
            for (int i = 0, l0 = polygons.getSize(); i < l0; i++) {
                long poly = polygons.get(i);
                Result<GetPolyWallSegmentsResult> result = navquery.getPolyWallSegments(poly, false, filter);
                if (result.succeeded()) {
                    GetPolyWallSegmentsResult res2 = result.result;
                    for (int k = 0, l = res2.segmentRefs.getSize(); k < l; ++k) {
                        float[] s = res2.segmentVertices.get(k);
                        // Skip too distant segments.
                        Pair<Float, Float> distSeg = distancePtSegSqr2D(pos, s, 0, 3);
                        if (distSeg.getFirst() > sqr(collisionQueryRange)) {
                            continue;
                        }
                        addSegment(distSeg.getFirst(), s);
                    }
                }
            }
        }
    }

    public boolean isValid(NavMeshQuery navMeshQuery, QueryFilter filter) {
        if (polygons.isEmpty()) return false;
        // Check, that all polygons still pass query filter.
        for (int i = 0, l0 = polygons.getSize(); i < l0; i++) {
            long ref = polygons.get(i);
            if (!navMeshQuery.isValidPolyRef(ref, filter)) {
                return false;
            }
        }
        return true;
    }
}
