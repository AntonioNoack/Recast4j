package org.recast4j.detour.extras.jumplink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Vector3f;
import org.recast4j.detour.MeshTile;
import org.recast4j.detour.NavMesh;
import org.recast4j.detour.NavMeshBuilder;
import org.recast4j.detour.NavMeshDataCreateParams;
import org.recast4j.detour.NavMeshQuery;
import org.recast4j.detour.Poly;
import org.recast4j.detour.QueryFilter;
import org.recast4j.detour.Result;
import org.recast4j.detour.Tupple2;
import org.recast4j.recast.RecastBuilder.RecastBuilderResult;

class NavMeshGroundSampler extends AbstractGroundSampler {

    private final QueryFilter filter = new NoOpFilter();

    private static class NoOpFilter implements QueryFilter {

        @Override
        public boolean passFilter(long ref, MeshTile tile, Poly poly) {
            return true;
        }

        @Override
        public float getCost(Vector3f pa, Vector3f pb, long prevRef, MeshTile prevTile, Poly prevPoly, long curRef,
                             MeshTile curTile, Poly curPoly, long nextRef, MeshTile nextTile, Poly nextPoly) {
            return 0;
        }

    }

    @Override
    public void sample(JumpLinkBuilderConfig acfg, RecastBuilderResult result, EdgeSampler es) {
        NavMeshQuery navMeshQuery = createNavMesh(result, acfg.agentRadius, acfg.agentHeight, acfg.agentClimb);
        sampleGround(acfg, es, (pt, h) -> getNavMeshHeight(navMeshQuery, pt, acfg.cellSize, h));
    }

    private NavMeshQuery createNavMesh(RecastBuilderResult r, float agentRadius, float agentHeight, float agentClimb) {
        NavMeshDataCreateParams params = new NavMeshDataCreateParams();
        params.verts = r.getMesh().verts;
        params.vertCount = r.getMesh().nverts;
        params.polys = r.getMesh().polys;
        params.polyAreas = r.getMesh().areas;
        params.polyFlags = r.getMesh().flags;
        params.polyCount = r.getMesh().npolys;
        params.nvp = r.getMesh().nvp;
        params.detailMeshes = r.getMeshDetail().meshes;
        params.detailVerts = r.getMeshDetail().verts;
        params.detailVertsCount = r.getMeshDetail().nverts;
        params.detailTris = r.getMeshDetail().tris;
        params.detailTriCount = r.getMeshDetail().ntris;
        params.walkableRadius = agentRadius;
        params.walkableHeight = agentHeight;
        params.walkableClimb = agentClimb;
        params.bmin = r.getMesh().bmin;
        params.bmax = r.getMesh().bmax;
        params.cs = r.getMesh().cs;
        params.ch = r.getMesh().ch;
        params.buildBvTree = true;
        return new NavMeshQuery(new NavMesh(NavMeshBuilder.createNavMeshData(params), params.nvp, 0));
    }

    private Tupple2<Boolean, Float> getNavMeshHeight(NavMeshQuery navMeshQuery, Vector3f pt, float cs,
            float heightRange) {
        Vector3f halfExtents = new Vector3f(cs, heightRange, cs);
        float maxHeight = pt.y + heightRange;
        AtomicBoolean found = new AtomicBoolean();
        AtomicReference<Float> minHeight = new AtomicReference<>(pt.y);
        navMeshQuery.queryPolygons(pt, halfExtents, filter, (tile, poly, ref) -> {
            Result<Float> h = navMeshQuery.getPolyHeight(ref, pt);
            if (h.succeeded()) {
                float y = h.result;
                if (y > minHeight.get() && y < maxHeight) {
                    minHeight.set(y);
                    found.set(true);
                }
            }
        });
        if (found.get()) {
            return new Tupple2<>(true, minHeight.get());
        }
        return new Tupple2<>(false, pt.y);
    }

}
