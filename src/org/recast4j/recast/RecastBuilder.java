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
import org.recast4j.recast.RecastConstants.PartitionType;
import org.recast4j.recast.geom.ConvexVolumeProvider;
import org.recast4j.recast.geom.InputGeomProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class RecastBuilder {

    public interface RecastBuilderProgressListener {
        void onProgress(int completed, int total);
    }

    private final RecastBuilderProgressListener progressListener;

    public RecastBuilder() {
        progressListener = null;
    }

    @SuppressWarnings("unused")
    public RecastBuilder(RecastBuilderProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    public static class RecastBuilderResult {

        public final int tileX;
        public final int tileZ;
        public final CompactHeightfield compactHeightField;
        public final ContourSet countourSet;
        public final PolyMesh mesh;
        public final PolyMeshDetail meshDetail;
        public final Heightfield solidHeightField;
        public final Telemetry telemetry;

        public RecastBuilderResult(int tileX, int tileZ, Heightfield solidHeightField, CompactHeightfield compactHeightField, ContourSet countourSet, PolyMesh mesh,
                                   PolyMeshDetail meshDetail, Telemetry ctx) {
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.solidHeightField = solidHeightField;
            this.compactHeightField = compactHeightField;
            this.countourSet = countourSet;
            this.mesh = mesh;
            this.meshDetail = meshDetail;
            telemetry = ctx;
        }

    }

    @SuppressWarnings("unused")
    public List<RecastBuilderResult> buildTiles(InputGeomProvider geom, RecastConfig cfg, Executor executor) {
        Vector3f bmin = geom.getMeshBoundsMin();
        Vector3f bmax = geom.getMeshBoundsMax();
        int tw = Recast.calcTileCountX(bmin, bmax, cfg.cellSize, cfg.tileSizeX);
        int th = Recast.calcTileCountY(bmin, bmax, cfg.cellSize, cfg.tileSizeZ);
        if (executor != null) {
            return buildMultiThread(geom, cfg, bmin, bmax, tw, th, executor);
        } else {
            return buildSingleThread(geom, cfg, bmin, bmax, tw, th);
        }
    }

    private List<RecastBuilderResult> buildSingleThread(InputGeomProvider geom, RecastConfig cfg, Vector3f bmin, Vector3f bmax,
                                                        int tw, int th) {
        List<RecastBuilderResult> result = new ArrayList<>(tw * th);
        AtomicInteger counter = new AtomicInteger();
        for (int y = 0; y < th; ++y) {
            for (int x = 0; x < tw; ++x) {
                result.add(buildTile(geom, cfg, bmin, bmax, x, y, counter, tw * th));
            }
        }
        return result;
    }

    private List<RecastBuilderResult> buildMultiThread(InputGeomProvider geom, RecastConfig cfg, Vector3f bmin, Vector3f bmax,
                                                       int tw, int th, Executor executor) {
        List<RecastBuilderResult> result = new ArrayList<>(tw * th);
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(tw * th);
        for (int x = 0; x < tw; ++x) {
            for (int y = 0; y < th; ++y) {
                final int tx = x;
                final int ty = y;
                executor.execute(() -> {
                    try {
                        RecastBuilderResult tile = buildTile(geom, cfg, bmin, bmax, tx, ty, counter, tw * th);
                        synchronized (result) {
                            result.add(tile);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    latch.countDown();
                });
            }
        }
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
        return result;
    }

    private RecastBuilderResult buildTile(InputGeomProvider geom, RecastConfig cfg, Vector3f bmin, Vector3f bmax, final int tx,
                                          final int ty, AtomicInteger counter, int total) {
        RecastBuilderResult result = build(geom, new RecastBuilderConfig(cfg, bmin, bmax, tx, ty));
        if (progressListener != null) {
            progressListener.onProgress(counter.incrementAndGet(), total);
        }
        return result;
    }

    public RecastBuilderResult build(InputGeomProvider geom, RecastBuilderConfig builderCfg) {

        RecastConfig cfg = builderCfg.cfg;
        Telemetry ctx = new Telemetry();
        //
        // Step 1. Rasterize input polygon soup.
        //
        Heightfield solid = RecastVoxelization.buildSolidHeightfield(geom, builderCfg, ctx);
        return build(builderCfg.tileX, builderCfg.tileZ, geom, cfg, solid, ctx);
    }

    public RecastBuilderResult build(int tileX, int tileZ, ConvexVolumeProvider geom, RecastConfig cfg, Heightfield solid,
                                     Telemetry ctx) {
        filterHeightfield(solid, cfg, ctx);
        CompactHeightfield chf = buildCompactHeightfield(geom, cfg, ctx, solid);

        // Partition the heightfield so that we can use simple algorithm later
        // to triangulate the walkable areas.
        // There are 3 martitioning methods, each with some pros and cons:
        // 1) Watershed partitioning
        // - the classic Recast partitioning
        // - creates the nicest tessellation
        // - usually slowest
        // - partitions the heightfield into nice regions without holes or
        // overlaps
        // - the are some corner cases where this method creates produces holes
        // and overlaps
        // - holes may appear when a small obstacles is close to large open area
        // (triangulation can handle this)
        // - overlaps may occur if you have narrow spiral corridors (i.e
        // stairs), this make triangulation to fail
        // * generally the best choice if you precompute the nacmesh, use this
        // if you have large open areas
        // 2) Monotone partioning
        // - fastest
        // - partitions the heightfield into regions without holes and overlaps
        // (guaranteed)
        // - creates long thin polygons, which sometimes causes paths with
        // detours
        // * use this if you want fast navmesh generation
        // 3) Layer partitoining
        // - quite fast
        // - partitions the heighfield into non-overlapping regions
        // - relies on the triangulation code to cope with holes (thus slower
        // than monotone partitioning)
        // - produces better triangles than monotone partitioning
        // - does not have the corner cases of watershed partitioning
        // - can be slow and create a bit ugly tessellation (still better than
        // monotone)
        // if you have large open areas with small obstacles (not a problem if
        // you use tiles)
        // * good choice to use for tiled navmesh with medium and small sized
        // tiles

        if (cfg.partitionType == PartitionType.WATERSHED) {
            // Prepare for region partitioning, by calculating distance field
            // along the walkable surface.
            RecastRegion.buildDistanceField(ctx, chf);
            // Partition the walkable surface into simple regions without holes.
            RecastRegion.buildRegions(ctx, chf, cfg.minRegionArea, cfg.mergeRegionArea);
        } else if (cfg.partitionType == PartitionType.MONOTONE) {
            // Partition the walkable surface into simple regions without holes.
            // Monotone partitioning does not need distancefield.
            RecastRegion.buildRegionsMonotone(ctx, chf, cfg.minRegionArea, cfg.mergeRegionArea);
        } else {
            // Partition the walkable surface into simple regions without holes.
            RecastRegion.buildLayerRegions(ctx, chf, cfg.minRegionArea);
        }

        //
        // Step 5. Trace and simplify region contours.
        //

        // Create contours.
        ContourSet cset = RecastContour.buildContours(ctx, chf, cfg.maxSimplificationError, cfg.maxEdgeLen,
                RecastConstants.RC_CONTOUR_TESS_WALL_EDGES);

        //
        // Step 6. Build polygons mesh from contours.
        //

        PolyMesh pmesh = RecastMesh.buildPolyMesh(ctx, cset, cfg.maxVerticesPerPoly);

        //
        // Step 7. Create detail mesh which allows to access approximate height
        // on each polygon.
        //
        PolyMeshDetail dmesh = cfg.buildMeshDetail
                ? RecastMeshDetail.buildPolyMeshDetail(ctx, pmesh, chf, cfg.detailSampleDist, cfg.detailSampleMaxError)
                : null;
        return new RecastBuilderResult(tileX, tileZ, solid, chf, cset, pmesh, dmesh, ctx);
    }

    /*
     * Step 2. Filter walkable surfaces.
     */
    private void filterHeightfield(Heightfield solid, RecastConfig cfg, Telemetry ctx) {
        // Once all geometry is rasterized, we do initial pass of filtering to
        // remove unwanted overhangs caused by the conservative rasterization
        // as well as filter spans where the character cannot possibly stand.
        if (cfg.filterLowHangingObstacles) {
            RecastFilter.filterLowHangingWalkableObstacles(ctx, cfg.walkableClimb, solid);
        }
        if (cfg.filterLedgeSpans) {
            RecastFilter.filterLedgeSpans(ctx, cfg.walkableHeight, cfg.walkableClimb, solid);
        }
        if (cfg.filterWalkableLowHeightSpans) {
            RecastFilter.filterWalkableLowHeightSpans(ctx, cfg.walkableHeight, solid);
        }
    }

    /** Step 3. Partition walkable surface to simple regions. */
    private CompactHeightfield buildCompactHeightfield(ConvexVolumeProvider volumeProvider, RecastConfig cfg, Telemetry ctx,
                                                       Heightfield solid) {
        // Compact the heightfield so that it is faster to handle from now on.
        // This will result more cache coherent data as well as the neighbours
        // between walkable cells will be calculated.
        CompactHeightfield chf = RecastCompact.buildCompactHeightfield(ctx, cfg.walkableHeight, cfg.walkableClimb, solid);

        // Erode the walkable area by agent radius.
        RecastArea.erodeWalkableArea(ctx, cfg.walkableRadius, chf);
        // (Optional) Mark areas.
        if (volumeProvider != null) {
            for (ConvexVolume vol : volumeProvider.convexVolumes()) {
                RecastArea.markConvexPolyArea(ctx, vol.vertices, vol.minH, vol.maxH, vol.areaMod, chf);
            }
        }
        return chf;
    }

    @SuppressWarnings("unused")
    public HeightfieldLayer[] buildLayers(InputGeomProvider geom, RecastBuilderConfig builderCfg) {
        Telemetry ctx = new Telemetry();
        Heightfield solid = RecastVoxelization.buildSolidHeightfield(geom, builderCfg, ctx);
        filterHeightfield(solid, builderCfg.cfg, ctx);
        CompactHeightfield chf = buildCompactHeightfield(geom, builderCfg.cfg, ctx, solid);
        return RecastLayers.buildHeightfieldLayers(ctx, chf, builderCfg.cfg.walkableHeight);
    }

}
