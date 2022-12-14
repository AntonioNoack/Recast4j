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
package org.recast4j.detour;

import kotlin.Pair;
import kotlin.Triple;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.recast4j.LongArrayList;
import org.recast4j.Vectors;

import java.util.*;

import static org.joml.Math.clamp;
import static org.recast4j.Vectors.*;

public class NavMesh {

    static int DT_SALT_BITS = 16;
    static int DT_TILE_BITS = 28;
    static int DT_POLY_BITS = 20;
    public static final int DT_DETAIL_EDGE_BOUNDARY = 0x01;

    /**
     * A flag that indicates that an entity links to an external entity.
     * (E.g. A polygon edge is a portal that links to another polygon.)
     * */
    public static final int DT_EXT_LINK = 0x8000;

    /**
     * A value that indicates the entity does not link to anything.
     * */
    public static final int DT_NULL_LINK = 0xffffffff;

    /**
     * A flag that indicates that an off-mesh connection can be traversed in both directions. (Is bidirectional.)
     * */
    public static final int DT_OFFMESH_CON_BIDIR = 1;

    /**
     * The maximum number of user defined area ids.
     * */
    public static final int DT_MAX_AREAS = 64;

    /**
     * Limit raycasting during any angle pathfinding.
     * The limit is given as a multiple of the character radius
     * */
    static float DT_RAY_CAST_LIMIT_PROPORTIONS = 50f;

    /**
     * Current initialization params.
     */
    private final NavMeshParams m_params;

    /**
     * Origin of the tile (0,0)
     */
    private final Vector3f origin;
    /**
     * Dimensions of each tile.
     */
    float m_tileWidth, m_tileHeight;
    /**
     * Max number of tiles.
     */
    int m_maxTiles;
    /**
     * Tile hash lookup mask.
     */
    private final int tileLutMask;
    private final Map<Integer, List<MeshTile>> posLookup = new HashMap<>();
    private final LinkedList<MeshTile> availableTiles = new LinkedList<>();
    private final MeshTile[] m_tiles; /// < List of tiles.
    /**
     * The maximum number of vertices per navigation polygon.
     */
    public final int maxVerticesPerPoly;
    public int tileCount;

    /**
     * The maximum number of tiles supported by the navigation mesh.
     *
     * @return The maximum number of tiles supported by the navigation mesh.
     */
    public int getMaxTiles() {
        return m_maxTiles;
    }

    /**
     * Returns tile in the tile array.
     */
    public MeshTile getTile(int i) {
        return m_tiles[i];
    }

    /**
     * Gets the polygon reference for the tile's base polygon.
     *
     * @param tile The tile.
     * @return The polygon reference for the base polygon in the specified tile.
     */
    public long getPolyRefBase(MeshTile tile) {
        if (tile == null) {
            return 0;
        }
        int it = tile.index;
        return encodePolyId(tile.salt, it, 0);
    }

    /**
     * Derives a standard polygon reference.
     *
     * @param salt The tile's salt value.
     * @param it   The index of the tile.
     * @param ip   The index of the polygon within the tile.
     * @return encoded polygon reference
     * @note This function is generally meant for internal use only.
     */
    public static long encodePolyId(int salt, int it, int ip) {
        return (((long) salt) << (DT_POLY_BITS + DT_TILE_BITS)) | ((long) it << DT_POLY_BITS) | ip;
    }

    /// Decodes a standard polygon reference.
    /// @note This function is generally meant for internal use only.
    /// @param[in] ref The polygon reference to decode.
    /// @param[out] salt The tile's salt value.
    /// @param[out] it The index of the tile.
    /// @param[out] ip The index of the polygon within the tile.
    /// @see #encodePolyId
    static int[] decodePolyId(long ref) {
        int salt;
        int it;
        int ip;
        long saltMask = (1L << DT_SALT_BITS) - 1;
        long tileMask = (1L << DT_TILE_BITS) - 1;
        long polyMask = (1L << DT_POLY_BITS) - 1;
        salt = (int) ((ref >> (DT_POLY_BITS + DT_TILE_BITS)) & saltMask);
        it = (int) ((ref >> DT_POLY_BITS) & tileMask);
        ip = (int) (ref & polyMask);
        return new int[]{salt, it, ip};
    }

    /// Extracts a tile's salt value from the specified polygon reference.
    /// @note This function is generally meant for internal use only.
    /// @param[in] ref The polygon reference.
    /// @see #encodePolyId
    static int decodePolyIdSalt(long ref) {
        long saltMask = (1L << DT_SALT_BITS) - 1;
        return (int) ((ref >> (DT_POLY_BITS + DT_TILE_BITS)) & saltMask);
    }

    /// Extracts the tile's index from the specified polygon reference.
    /// @note This function is generally meant for internal use only.
    /// @param[in] ref The polygon reference.
    /// @see #encodePolyId
    public static int decodePolyIdTile(long ref) {
        long tileMask = (1L << DT_TILE_BITS) - 1;
        return (int) ((ref >> DT_POLY_BITS) & tileMask);
    }

    /// Extracts the polygon's index (within its tile) from the specified
    /// polygon reference.
    /// @note This function is generally meant for internal use only.
    /// @param[in] ref The polygon reference.
    /// @see #encodePolyId
    static int decodePolyIdPoly(long ref) {
        long polyMask = (1L << DT_POLY_BITS) - 1;
        return (int) (ref & polyMask);
    }

    private int allocLink(MeshTile tile) {
        if (tile.linksFreeList == DT_NULL_LINK) {
            Link link = new Link();
            link.indexOfNextLink = DT_NULL_LINK;
            tile.links.add(link);
            return tile.links.size() - 1;
        }
        int link = tile.linksFreeList;
        tile.linksFreeList = tile.links.get(link).indexOfNextLink;
        return link;
    }

    private void freeLink(MeshTile tile, int link) {
        tile.links.get(link).indexOfNextLink = tile.linksFreeList;
        tile.linksFreeList = link;
    }

    /**
     * Calculates the tile grid location for the specified world position.
     *
     * @param pos The world position for the query. [(x, y, z)]
     * @return 2-element int array with (tx,ty) tile location
     */
    @SuppressWarnings("unused")
    public int[] calcTileLoc(Vector3f pos) {
        int tx = (int) Math.floor((pos.x - origin.x) / m_tileWidth);
        int ty = (int) Math.floor((pos.z - origin.z) / m_tileHeight);
        return new int[]{tx, ty};
    }

    public int calcTileLocX(Vector3f pos) {
        return (int) Math.floor((pos.x - origin.x) / m_tileWidth);
    }

    public int calcTileLocY(Vector3f pos) {
        return (int) Math.floor((pos.z - origin.z) / m_tileHeight);
    }

    public Result<Pair<MeshTile, Poly>> getTileAndPolyByRef(long ref) {
        if (ref == 0) {
            return Result.invalidParam("ref = 0");
        }
        int[] saltitip = decodePolyId(ref);
        int salt = saltitip[0];
        int it = saltitip[1];
        int ip = saltitip[2];
        if (it >= m_maxTiles) {
            return Result.invalidParam("tile > m_maxTiles");
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data.header == null) {
            return Result.invalidParam("Invalid salt or header");
        }
        if (ip >= m_tiles[it].data.header.polyCount) {
            return Result.invalidParam("poly > polyCount");
        }
        return Result.success(new Pair<>(m_tiles[it], m_tiles[it].data.polygons[ip]));
    }

    /**
     * @warning Only use this function if it is known that the provided polygon reference is valid.
     * This function is faster than getTileAndPolyByRef, but it does not validate the reference.
     * */
    Pair<MeshTile, Poly> getTileAndPolyByRefUnsafe(long ref) {
        int[] saltitip = decodePolyId(ref);
        int it = saltitip[1];
        int ip = saltitip[2];
        return new Pair<>(m_tiles[it], m_tiles[it].data.polygons[ip]);
    }

    boolean isValidPolyRef(long ref) {
        if (ref == 0) return false;
        int[] saltitip = decodePolyId(ref);
        int salt = saltitip[0];
        int it = saltitip[1];
        int ip = saltitip[2];
        if (it >= m_maxTiles) return false;
        if (m_tiles[it].salt != salt || m_tiles[it].data == null) return false;
        return ip < m_tiles[it].data.header.polyCount;
    }

    public NavMeshParams getParams() {
        return m_params;
    }

    public NavMesh(MeshData data, int maxVerticesPerPoly, int flags) {
        this(getNavMeshParams(data), maxVerticesPerPoly);
        addTile(data, flags, 0);
    }

    public NavMesh(NavMeshParams params, int maxVerticesPerPoly) {
        m_params = params;
        origin = params.origin;
        m_tileWidth = params.tileWidth;
        m_tileHeight = params.tileHeight;
        // Init tiles
        m_maxTiles = params.maxTiles;
        this.maxVerticesPerPoly = maxVerticesPerPoly;
        tileLutMask = Math.max(1, nextPow2(params.maxTiles)) - 1;
        m_tiles = new MeshTile[m_maxTiles];
        for (int i = 0; i < m_maxTiles; i++) {
            m_tiles[i] = new MeshTile(i);
            m_tiles[i].salt = 1;
            availableTiles.add(m_tiles[i]);
        }

    }

    private static NavMeshParams getNavMeshParams(MeshData data) {
        NavMeshParams params = new NavMeshParams();
        copy(params.origin, data.header.bmin);
        params.tileWidth = data.header.bmax.x - data.header.bmin.x;
        params.tileHeight = data.header.bmax.z - data.header.bmin.z;
        params.maxTiles = 1;
        params.maxPolys = data.header.polyCount;
        return params;
    }

    LongArrayList queryPolygonsInTile(MeshTile tile, Vector3f qmin, Vector3f qmax) {
        LongArrayList polys = new LongArrayList();
        if (tile.data.bvTree != null) {
            int nodeIndex = 0;
            Vector3f tbmin = tile.data.header.bmin;
            Vector3f tbmax = tile.data.header.bmax;
            float qfac = tile.data.header.bvQuantizationFactor;
            // Calculate quantized box
            Vector3i bmin = new Vector3i();
            Vector3i bmax = new Vector3i();
            // dtClamp query box to world box.
            float minx = clamp(qmin.x, tbmin.x, tbmax.x) - tbmin.x;
            float miny = clamp(qmin.y, tbmin.y, tbmax.y) - tbmin.y;
            float minz = clamp(qmin.z, tbmin.z, tbmax.z) - tbmin.z;
            float maxx = clamp(qmax.x, tbmin.x, tbmax.x) - tbmin.x;
            float maxy = clamp(qmax.y, tbmin.y, tbmax.y) - tbmin.y;
            float maxz = clamp(qmax.z, tbmin.z, tbmax.z) - tbmin.z;
            // Quantize
            bmin.x = (int) (qfac * minx) & 0x7ffffffe;
            bmin.y = (int) (qfac * miny) & 0x7ffffffe;
            bmin.z = (int) (qfac * minz) & 0x7ffffffe;
            bmax.x = (int) (qfac * maxx + 1) | 1;
            bmax.y = (int) (qfac * maxy + 1) | 1;
            bmax.z = (int) (qfac * maxz + 1) | 1;

            // Traverse tree
            long base = getPolyRefBase(tile);
            int end = tile.data.header.bvNodeCount;
            while (nodeIndex < end) {
                BVNode node = tile.data.bvTree[nodeIndex];
                boolean overlap = overlapQuantBounds(bmin, bmax, node);
                boolean isLeafNode = node.index >= 0;

                if (isLeafNode && overlap) {
                    polys.add(base | node.index);
                }

                if (overlap || isLeafNode) {
                    nodeIndex++;
                } else {
                    int escapeIndex = -node.index;
                    nodeIndex += escapeIndex;
                }
            }

        } else {
            Vector3f bmin = new Vector3f();
            Vector3f bmax = new Vector3f();
            long base = getPolyRefBase(tile);
            for (int i = 0; i < tile.data.header.polyCount; ++i) {
                Poly p = tile.data.polygons[i];
                // Do not return off-mesh connection polygons.
                if (p.getType() == Poly.DT_POLYTYPE_OFFMESH_CONNECTION) {
                    continue;
                }
                // Calc polygon bounds.
                int v = p.vertices[0] * 3;
                copy(bmin, tile.data.vertices, v);
                copy(bmax, tile.data.vertices, v);
                for (int j = 1; j < p.vertCount; ++j) {
                    v = p.vertices[j] * 3;
                    min(bmin, tile.data.vertices, v);
                    max(bmax, tile.data.vertices, v);
                }
                if (overlapBounds(qmin, qmax, bmin, bmax)) {
                    polys.add(base | i);
                }
            }
        }
        return polys;
    }

    @SuppressWarnings("unused")
    public long updateTile(MeshData data, int flags) {
        long ref = getTileRefAt(data.header.x, data.header.y, data.header.layer);
        ref = removeTile(ref);
        return addTile(data, flags, ref);
    }

    /**
     * Adds a tile to the navigation mesh.
     *
     * The add operation will fail if the data is in the wrong format, the allocated tile space is full, or there is a tile already at the specified reference.
     *
     * The lastRef parameter is used to restore a tile with the same tile
     * reference it had previously used. In this case the #long's for the
     * tile will be restored to the same values they were before the tile was removed.
     *
     * The nav mesh assumes exclusive access to the data passed and will make
     * changes to the dynamic portion of the data. For that reason the data
     * should not be reused in other nav meshes until the tile has been successfully
     * removed from this nav mesh.
     *
     * @param data Data for the new tile mesh. (See: #dtCreateNavMeshData)
     * @param flags Tile flags. (See: #dtTileFlags)
     * @param lastRef The desired reference for the tile. (When reloading a tile.) [opt] [Default: 0]
     * @return The tile reference. (If the tile was successfully added.) [opt]
     * */
    public long addTile(MeshData data, int flags, long lastRef) {
        // Make sure the data is in right format.
        MeshHeader header = data.header;

        // Make sure the location is free.
        if (getTileAt(header.x, header.y, header.layer) != null) {
            throw new RuntimeException("Tile already exists");
        }

        // Allocate a tile.
        MeshTile tile;
        if (lastRef == 0) {
            // Make sure we could allocate a tile.
            if (availableTiles.isEmpty()) {
                throw new RuntimeException("Could not allocate a tile");
            }
            tile = availableTiles.poll();
            tileCount++;
        } else {
            // Try to relocate the tile to specific index with same salt.
            int tileIndex = decodePolyIdTile(lastRef);
            if (tileIndex >= m_maxTiles) {
                throw new RuntimeException("Tile index too high");
            }
            // Try to find the specific tile id from the free list.
            MeshTile target = m_tiles[tileIndex];
            // Remove from freelist
            if (!availableTiles.remove(target)) {
                // Could not find the correct location.
                throw new RuntimeException("Could not find tile");
            }
            tile = target;
            // Restore salt.
            tile.salt = decodePolyIdSalt(lastRef);
        }

        tile.data = data;
        tile.flags = flags;
        tile.links.clear();
        tile.polyLinks = new int[data.polygons.length];
        Arrays.fill(tile.polyLinks, NavMesh.DT_NULL_LINK);

        // Insert tile into the position lut.
        getTileListByPos(header.x, header.y).add(tile);

        // Patch header pointers.

        // If there are no items in the bvtree, reset the tree pointer.
        if (tile.data.bvTree != null && tile.data.bvTree.length == 0) {
            tile.data.bvTree = null;
        }

        // Init tile.

        connectIntLinks(tile);
        // Base off-mesh connections to their starting polygons and connect connections inside the tile.
        baseOffMeshLinks(tile);
        connectExtOffMeshLinks(tile, tile, -1);

        // Connect with layers in current tile.
        List<MeshTile> neis = getTilesAt(header.x, header.y);
        for (MeshTile meshTile : neis) {
            if (meshTile == tile) continue;
            connectExtLinks(tile, meshTile, -1);
            connectExtLinks(meshTile, tile, -1);
            connectExtOffMeshLinks(tile, meshTile, -1);
            connectExtOffMeshLinks(meshTile, tile, -1);
        }

        // Connect with neighbour tiles.
        for (int i = 0; i < 8; ++i) {
            neis = getNeighbourTilesAt(header.x, header.y, i);
            for (MeshTile nei : neis) {
                connectExtLinks(tile, nei, i);
                connectExtLinks(nei, tile, oppositeTile(i));
                connectExtOffMeshLinks(tile, nei, i);
                connectExtOffMeshLinks(nei, tile, oppositeTile(i));
            }
        }

        return getTileRef(tile);
    }

    /// Removes the specified tile from the navigation mesh.
    /// @param[in] ref The reference of the tile to remove.
    /// @param[out] data Data associated with deleted tile.
    /// @param[out] dataSize Size of the data associated with deleted tile.
    ///
    /// This function returns the data for the tile so that, if desired,
    /// it can be added back to the navigation mesh at a later point.
    ///
    /// @see #addTile
    public long removeTile(long ref) {
        if (ref == 0) {
            return 0;
        }
        int tileIndex = decodePolyIdTile(ref);
        int tileSalt = decodePolyIdSalt(ref);
        if (tileIndex >= m_maxTiles) {
            throw new RuntimeException("Invalid tile index");
        }
        MeshTile tile = m_tiles[tileIndex];
        if (tile.salt != tileSalt) {
            throw new RuntimeException("Invalid tile salt");
        }

        // Remove tile from hash lookup.
        getTileListByPos(tile.data.header.x, tile.data.header.y).remove(tile);

        // Remove connections to neighbour tiles.
        // Create connections with neighbour tiles.

        // Disconnect from other layers in current tile.
        List<MeshTile> nneis = getTilesAt(tile.data.header.x, tile.data.header.y);
        for (MeshTile j : nneis) {
            if (j == tile) {
                continue;
            }
            unconnectLinks(j, tile);
        }

        // Disconnect from neighbour tiles.
        for (int i = 0; i < 8; ++i) {
            nneis = getNeighbourTilesAt(tile.data.header.x, tile.data.header.y, i);
            for (MeshTile j : nneis) {
                unconnectLinks(j, tile);
            }
        }
        // Reset tile.
        tile.data = null;

        tile.flags = 0;
        tile.links.clear();
        tile.linksFreeList = NavMesh.DT_NULL_LINK;

        // Update salt, salt should never be zero.
        tile.salt = (tile.salt + 1) & ((1 << DT_SALT_BITS) - 1);
        if (tile.salt == 0) {
            tile.salt++;
        }

        // Add to free list.
        availableTiles.addFirst(tile);
        tileCount--;
        return getTileRef(tile);
    }

    /// Builds internal polygons links for a tile.
    void connectIntLinks(MeshTile tile) {
        if (tile == null) {
            return;
        }

        long base = getPolyRefBase(tile);

        for (int i = 0; i < tile.data.header.polyCount; ++i) {
            Poly poly = tile.data.polygons[i];
            tile.polyLinks[poly.index] = DT_NULL_LINK;

            if (poly.getType() == Poly.DT_POLYTYPE_OFFMESH_CONNECTION) {
                continue;
            }

            // Build edge links backwards so that the links will be
            // in the linked list from lowest index to highest.
            for (int j = poly.vertCount - 1; j >= 0; --j) {
                // Skip hard and non-internal edges.
                if (poly.neighborData[j] == 0 || (poly.neighborData[j] & DT_EXT_LINK) != 0) {
                    continue;
                }

                int idx = allocLink(tile);
                Link link = tile.links.get(idx);
                link.neighborRef = base | (poly.neighborData[j] - 1);
                link.indexOfPolyEdge = j;
                link.side = 0xff;
                link.bmin = link.bmax = 0;
                // Add to linked list.
                link.indexOfNextLink = tile.polyLinks[poly.index];
                tile.polyLinks[poly.index] = idx;
            }
        }
    }

    void unconnectLinks(MeshTile tile, MeshTile target) {
        if (tile == null || target == null) {
            return;
        }

        int targetNum = decodePolyIdTile(getTileRef(target));

        for (int i = 0; i < tile.data.header.polyCount; ++i) {
            Poly poly = tile.data.polygons[i];
            int j = tile.polyLinks[poly.index];
            int pj = DT_NULL_LINK;
            while (j != DT_NULL_LINK) {
                if (decodePolyIdTile(tile.links.get(j).neighborRef) == targetNum) {
                    // Remove link.
                    int nj = tile.links.get(j).indexOfNextLink;
                    if (pj == DT_NULL_LINK) {
                        tile.polyLinks[poly.index] = nj;
                    } else {
                        tile.links.get(pj).indexOfNextLink = nj;
                    }
                    freeLink(tile, j);
                    j = nj;
                } else {
                    // Advance
                    pj = j;
                    j = tile.links.get(j).indexOfNextLink;
                }
            }
        }
    }

    void connectExtLinks(MeshTile tile, MeshTile target, int side) {
        if (tile == null) {
            return;
        }

        // Connect border links.
        for (int i = 0; i < tile.data.header.polyCount; ++i) {
            Poly poly = tile.data.polygons[i];

            // Create new links.
            // short m = DT_EXT_LINK | (short)side;

            int nv = poly.vertCount;
            for (int j = 0; j < nv; ++j) {
                // Skip non-portal edges.
                if ((poly.neighborData[j] & DT_EXT_LINK) == 0) {
                    continue;
                }

                int dir = poly.neighborData[j] & 0xff;
                if (side != -1 && dir != side) {
                    continue;
                }

                // Create new links
                int va = poly.vertices[j] * 3;
                int vb = poly.vertices[(j + 1) % nv] * 3;
                Triple<long[], float[], Integer> connectedPolys = findConnectingPolys(tile.data.vertices, va, vb, target,
                        oppositeTile(dir), 4);
                long[] nei = connectedPolys.getFirst();
                float[] neia = connectedPolys.getSecond();
                int nnei = connectedPolys.getThird();
                for (int k = 0; k < nnei; ++k) {
                    int idx = allocLink(tile);
                    Link link = tile.links.get(idx);
                    link.neighborRef = nei[k];
                    link.indexOfPolyEdge = j;
                    link.side = dir;

                    link.indexOfNextLink = tile.polyLinks[poly.index];
                    tile.polyLinks[poly.index] = idx;

                    // Compress portal limits to a byte value.
                    if (dir == 0 || dir == 4) {
                        float tmin = (neia[k * 2] - tile.data.vertices[va + 2])
                                / (tile.data.vertices[vb + 2] - tile.data.vertices[va + 2]);
                        float tmax = (neia[k * 2 + 1] - tile.data.vertices[va + 2])
                                / (tile.data.vertices[vb + 2] - tile.data.vertices[va + 2]);
                        if (tmin > tmax) {
                            float temp = tmin;
                            tmin = tmax;
                            tmax = temp;
                        }
                        link.bmin = Math.round(clamp(tmin, 0f, 1f) * 255f);
                        link.bmax = Math.round(clamp(tmax, 0f, 1f) * 255f);
                    } else if (dir == 2 || dir == 6) {
                        float tmin = (neia[k * 2] - tile.data.vertices[va])
                                / (tile.data.vertices[vb] - tile.data.vertices[va]);
                        float tmax = (neia[k * 2 + 1] - tile.data.vertices[va])
                                / (tile.data.vertices[vb] - tile.data.vertices[va]);
                        if (tmin > tmax) {
                            float temp = tmin;
                            tmin = tmax;
                            tmax = temp;
                        }
                        link.bmin = Math.round(clamp(tmin, 0f, 1f) * 255f);
                        link.bmax = Math.round(clamp(tmax, 0f, 1f) * 255f);
                    }
                }
            }
        }
    }

    void connectExtOffMeshLinks(MeshTile tile, MeshTile target, int side) {
        if (tile == null) {
            return;
        }

        // Connect off-mesh links.
        // We are interested on links which land from target tile to this tile.
        int oppositeSide = (side == -1) ? 0xff : oppositeTile(side);

        for (int i = 0; i < target.data.header.offMeshConCount; ++i) {
            OffMeshConnection targetCon = target.data.offMeshCons[i];
            if (targetCon.side != oppositeSide) {
                continue;
            }

            Poly targetPoly = target.data.polygons[targetCon.poly];
            // Skip off-mesh connections which start location could not be
            // connected at all.
            if (target.polyLinks[targetPoly.index] == DT_NULL_LINK) {
                continue;
            }

            Vector3f ext = new Vector3f(targetCon.rad, target.data.header.walkableClimb, targetCon.rad);

            // Find polygon to connect to.
            Vector3f p = new Vector3f(targetCon.posB);
            FindNearestPolyResult nearest = findNearestPolyInTile(tile, p, ext);
            long ref = nearest.nearestRef;
            if (ref == 0) {
                continue;
            }
            Vector3f nearestPt = nearest.nearestPos;
            // findNearestPoly may return too optimistic results, further check
            // to make sure.

            if (sqr(nearestPt.x - p.x) + sqr(nearestPt.z - p.z) > sqr(targetCon.rad)) {
                continue;
            }
            // Make sure the location is on current mesh.
            target.data.vertices[targetPoly.vertices[1] * 3] = nearestPt.x;
            target.data.vertices[targetPoly.vertices[1] * 3 + 1] = nearestPt.y;
            target.data.vertices[targetPoly.vertices[1] * 3 + 2] = nearestPt.z;

            // Link off-mesh connection to target poly.
            int idx = allocLink(target);
            Link link = target.links.get(idx);
            link.neighborRef = ref;
            link.indexOfPolyEdge = 1;
            link.side = oppositeSide;
            link.bmin = link.bmax = 0;
            // Add to linked list.
            link.indexOfNextLink = target.polyLinks[targetPoly.index];
            target.polyLinks[targetPoly.index] = idx;

            // Link target poly to off-mesh connection.
            if ((targetCon.flags & DT_OFFMESH_CON_BIDIR) != 0) {
                int tidx = allocLink(tile);
                int landPolyIdx = decodePolyIdPoly(ref);
                Poly landPoly = tile.data.polygons[landPolyIdx];
                link = tile.links.get(tidx);
                link.neighborRef = getPolyRefBase(target) | (targetCon.poly);
                link.indexOfPolyEdge = 0xff;
                link.side = (side == -1 ? 0xff : side);
                link.bmin = link.bmax = 0;
                // Add to linked list.
                link.indexOfNextLink = tile.polyLinks[landPoly.index];
                tile.polyLinks[landPoly.index] = tidx;
            }
        }
    }

    Triple<long[], float[], Integer> findConnectingPolys(float[] vertices, int va, int vb, MeshTile tile, int side, int maxcon) {
        if (tile == null) {
            return new Triple<>(null, null, 0);
        }
        long[] con = new long[maxcon];
        float[] conarea = new float[maxcon * 2];
        float[] amin = new float[2];
        float[] amax = new float[2];
        calcSlabEndPoints(vertices, va, vb, amin, amax, side);
        float apos = getSlabCoord(vertices, va, side);

        // Remove links pointing to 'side' and compact the links array.
        float[] bmin = new float[2];
        float[] bmax = new float[2];
        int m = DT_EXT_LINK | side;
        int n = 0;
        long base = getPolyRefBase(tile);

        for (int i = 0; i < tile.data.header.polyCount; ++i) {
            Poly poly = tile.data.polygons[i];
            int nv = poly.vertCount;
            for (int j = 0; j < nv; ++j) {
                // Skip edges which do not point to the right side.
                if (poly.neighborData[j] != m) {
                    continue;
                }
                int vc = poly.vertices[j] * 3;
                int vd = poly.vertices[(j + 1) % nv] * 3;
                float bpos = getSlabCoord(tile.data.vertices, vc, side);
                // Segments are not close enough.
                if (Math.abs(apos - bpos) > 0.01f) {
                    continue;
                }

                // Check if the segments touch.
                calcSlabEndPoints(tile.data.vertices, vc, vd, bmin, bmax, side);

                if (!overlapSlabs(amin, amax, bmin, bmax, 0.01f, tile.data.header.walkableClimb)) {
                    continue;
                }

                // Add return value.
                if (n < maxcon) {
                    conarea[n * 2] = Math.max(amin[0], bmin[0]);
                    conarea[n * 2 + 1] = Math.min(amax[0], bmax[0]);
                    con[n] = base | i;
                    n++;
                }
                break;
            }
        }
        return new Triple<>(con, conarea, n);
    }

    static float getSlabCoord(float[] vertices, int va, int side) {
        if (side == 0 || side == 4) {
            return vertices[va];
        } else if (side == 2 || side == 6) {
            return vertices[va + 2];
        }
        return 0;
    }

    static void calcSlabEndPoints(float[] vertices, int va, int vb, float[] bmin, float[] bmax, int side) {
        if (side == 0 || side == 4) {
            if (vertices[va + 2] < vertices[vb + 2]) {
                bmin[0] = vertices[va + 2];
                bmin[1] = vertices[va + 1];
                bmax[0] = vertices[vb + 2];
                bmax[1] = vertices[vb + 1];
            } else {
                bmin[0] = vertices[vb + 2];
                bmin[1] = vertices[vb + 1];
                bmax[0] = vertices[va + 2];
                bmax[1] = vertices[va + 1];
            }
        } else if (side == 2 || side == 6) {
            if (vertices[va] < vertices[vb]) {
                bmin[0] = vertices[va];
                bmin[1] = vertices[va + 1];
                bmax[0] = vertices[vb];
                bmax[1] = vertices[vb + 1];
            } else {
                bmin[0] = vertices[vb];
                bmin[1] = vertices[vb + 1];
                bmax[0] = vertices[va];
                bmax[1] = vertices[va + 1];
            }
        }
    }

    boolean overlapSlabs(float[] amin, float[] amax, float[] bmin, float[] bmax, float px, float py) {
        // Check for horizontal overlap.
        // The segment is shrunken a little so that slabs, which touch
        // at end points are not connected.
        float minX = Math.max(amin[0] + px, bmin[0] + px);
        float maxX = Math.min(amax[0] - px, bmax[0] - px);
        if (minX > maxX) {
            return false;
        }

        // Check vertical overlap.
        float ad = (amax[1] - amin[1]) / (amax[0] - amin[0]);
        float ak = amin[1] - ad * amin[0];
        float bd = (bmax[1] - bmin[1]) / (bmax[0] - bmin[0]);
        float bk = bmin[1] - bd * bmin[0];
        float aminy = ad * minX + ak;
        float amaxy = ad * maxX + ak;
        float bminy = bd * minX + bk;
        float bmaxy = bd * maxX + bk;
        float dmin = bminy - aminy;
        float dmax = bmaxy - amaxy;

        // Crossing segments always overlap.
        if (dmin * dmax < 0) return true;

        // Check for overlap at endpoints.
        float thr = (py * 2) * (py * 2);
        return dmin * dmin <= thr || dmax * dmax <= thr;
    }

    /**
     * Builds internal polygons links for a tile.
     */
    void baseOffMeshLinks(MeshTile tile) {
        if (tile == null) {
            return;
        }

        long base = getPolyRefBase(tile);

        // Base off-mesh connection start points.
        for (int i = 0; i < tile.data.header.offMeshConCount; ++i) {
            OffMeshConnection con = tile.data.offMeshCons[i];
            Poly poly = tile.data.polygons[con.poly];

            Vector3f ext = new Vector3f(con.rad, tile.data.header.walkableClimb, con.rad);

            // Find polygon to connect to.
            FindNearestPolyResult nearestPoly = findNearestPolyInTile(tile, con.posA, ext);
            long ref = nearestPoly.nearestRef;
            if (ref == 0) {
                continue;
            }
            Vector3f p = con.posA; // First vertex
            Vector3f nearestPt = nearestPoly.nearestPos;
            // findNearestPoly may return too optimistic results, further check
            // to make sure.
            if (sqr(nearestPt.x - p.x) + sqr(nearestPt.z - p.z) > sqr(con.rad)) {
                continue;
            }
            // Make sure the location is on current mesh.
            copy(tile.data.vertices, poly.vertices[0] * 3, nearestPt);

            // Link off-mesh connection to target poly.
            int idx = allocLink(tile);
            Link link = tile.links.get(idx);
            link.neighborRef = ref;
            link.indexOfPolyEdge = 0;
            link.side = 0xff;
            link.bmin = link.bmax = 0;
            // Add to linked list.
            link.indexOfNextLink = tile.polyLinks[poly.index];
            tile.polyLinks[poly.index] = idx;

            // Start end-point is always connect back to off-mesh connection.
            int tidx = allocLink(tile);
            int landPolyIdx = decodePolyIdPoly(ref);
            Poly landPoly = tile.data.polygons[landPolyIdx];
            link = tile.links.get(tidx);
            link.neighborRef = base | (con.poly);
            link.indexOfPolyEdge = 0xff;
            link.side = 0xff;
            link.bmin = link.bmax = 0;
            // Add to linked list.
            link.indexOfNextLink = tile.polyLinks[landPoly.index];
            tile.polyLinks[landPoly.index] = tidx;
        }
    }

    /**
     * Returns closest point on polygon.
     */
    Vector3f closestPointOnDetailEdges(MeshTile tile, Poly poly, Vector3f pos, boolean onlyBoundary) {
        int ANY_BOUNDARY_EDGE = (DT_DETAIL_EDGE_BOUNDARY) | (DT_DETAIL_EDGE_BOUNDARY << 2) | (DT_DETAIL_EDGE_BOUNDARY << 4);
        int ip = poly.index;
        float dmin = Float.MAX_VALUE;
        float tmin = 0;
        Vector3f pmin = null;
        Vector3f pmax = null;

        if (tile.data.detailMeshes != null) {

            PolyDetail pd = tile.data.detailMeshes[ip];
            for (int i = 0; i < pd.triCount; i++) {
                int ti = (pd.triBase + i) * 4;
                int[] tris = tile.data.detailTriangles;
                if (onlyBoundary && (tris[ti + 3] & ANY_BOUNDARY_EDGE) == 0) {
                    continue;
                }

                Vector3f[] v = {new Vector3f(), new Vector3f(), new Vector3f()};
                for (int j = 0; j < 3; ++j) {
                    if (tris[ti + j] < poly.vertCount) {
                        int index = poly.vertices[tris[ti + j]] * 3;
                        v[j].set(tile.data.vertices[index], tile.data.vertices[index + 1],
                                tile.data.vertices[index + 2]);
                    } else {
                        int index = (pd.vertBase + (tris[ti + j] - poly.vertCount)) * 3;
                        v[j].set(tile.data.detailVertices[index], tile.data.detailVertices[index + 1],
                                tile.data.detailVertices[index + 2]);
                    }
                }

                for (int k = 0, j = 2; k < 3; j = k++) {
                    if ((getDetailTriEdgeFlags(tris[ti + 3], j) & DT_DETAIL_EDGE_BOUNDARY) == 0
                            && (onlyBoundary || tris[ti + j] < tris[ti + k])) {
                        // Only looking at boundary edges and this is internal, or
                        // this is an inner edge that we will see again or have already seen.
                        continue;
                    }

                    Pair<Float, Float> dt = distancePtSegSqr2D(pos, v[j], v[k]);
                    float d = dt.getFirst();
                    float t = dt.getSecond();
                    if (d < dmin) {
                        dmin = d;
                        tmin = t;
                        pmin = v[j];
                        pmax = v[k];
                    }
                }
            }
        } else {
            Vector3f v0 = new Vector3f();
            Vector3f v1 = new Vector3f();
            for (int j = 0; j < poly.vertCount; ++j) {
                int k = (j + 1) % poly.vertCount;
                copy(v0, tile.data.vertices, poly.vertices[j] * 3);
                copy(v1, tile.data.vertices, poly.vertices[k] * 3);
                Pair<Float, Float> dt = distancePtSegSqr2D(pos, v0, v1);
                float d = dt.getFirst();
                float t = dt.getSecond();
                if (d < dmin) {
                    dmin = d;
                    tmin = t;
                    pmin = v0;
                    pmax = v0;
                }
            }
        }

        return lerp(pmin, pmax, tmin);
    }

    float getPolyHeight(MeshTile tile, Poly poly, Vector3f pos) {
        // Off-mesh connections do not have detail polys and getting height
        // over them does not make sense.
        if (poly.getType() == Poly.DT_POLYTYPE_OFFMESH_CONNECTION) {
            return Float.NaN;
        }

        int ip = poly.index;

        float[] vertices = new float[maxVerticesPerPoly * 3];
        int nv = poly.vertCount;
        for (int i = 0; i < nv; ++i) {
            System.arraycopy(tile.data.vertices, poly.vertices[i] * 3, vertices, i * 3, 3);
        }

        if (!pointInPolygon(pos, vertices, nv)) {
            return Float.NaN;
        }

        // Find height at the location.
        if (tile.data.detailMeshes != null) {
            PolyDetail pd = tile.data.detailMeshes[ip];
            for (int j = 0; j < pd.triCount; ++j) {
                int t = (pd.triBase + j) * 4;
                Vector3f[] v = {new Vector3f(), new Vector3f(), new Vector3f()};
                for (int k = 0; k < 3; ++k) {
                    if (tile.data.detailTriangles[t + k] < poly.vertCount) {
                        int index = poly.vertices[tile.data.detailTriangles[t + k]] * 3;
                        copy(v[k], tile.data.vertices, index);
                    } else {
                        int index = (pd.vertBase + (tile.data.detailTriangles[t + k] - poly.vertCount)) * 3;
                        copy(v[k], tile.data.detailVertices, index);
                    }
                }
                float h = closestHeightPointTriangle(pos, v[0], v[1], v[2]);
                if (Float.isFinite(h)) {
                    return h;
                }
            }
        } else {
            Vector3f v0 = new Vector3f();
            Vector3f v1 = new Vector3f();
            Vector3f v2 = new Vector3f();
            copy(v0, tile.data.vertices, poly.vertices[0] * 3);
            for (int j = 1; j < poly.vertCount - 1; ++j) {
                copy(v1, tile.data.vertices, poly.vertices[j + 1] * 3);
                copy(v2, tile.data.vertices, poly.vertices[j + 2] * 3);
                float h = closestHeightPointTriangle(pos, v0, v1, v2);
                if (Float.isFinite(h)) return h;
            }
        }

        // If all triangle checks failed above (can happen with degenerate triangles
        // or larger floating point values) the point is on an edge, so just select
        // closest. This should almost never happen, so the extra iteration here is ok.
        Vector3f closest = closestPointOnDetailEdges(tile, poly, pos, false);
        return closest.y;
    }

    ClosestPointOnPolyResult closestPointOnPoly(long ref, Vector3f pos) {
        Pair<MeshTile, Poly> tileAndPoly = getTileAndPolyByRefUnsafe(ref);
        MeshTile tile = tileAndPoly.getFirst();
        Poly poly = tileAndPoly.getSecond();
        Vector3f closest = new Vector3f(pos);
        float h = getPolyHeight(tile, poly, pos);
        if (Float.isFinite(h)) {
            closest.y = h;
            return new ClosestPointOnPolyResult(true, closest);
        }

        // Off-mesh connections don't have detail polygons.
        if (poly.getType() == Poly.DT_POLYTYPE_OFFMESH_CONNECTION) {
            int i = poly.vertices[0] * 3;
            Vector3f v0 = new Vector3f(tile.data.vertices[i], tile.data.vertices[i + 1], tile.data.vertices[i + 2]);
            i = poly.vertices[1] * 3;
            Vector3f v1 = new Vector3f(tile.data.vertices[i], tile.data.vertices[i + 1], tile.data.vertices[i + 2]);
            Pair<Float, Float> dt = distancePtSegSqr2D(pos, v0, v1);
            return new ClosestPointOnPolyResult(false, lerp(v0, v1, dt.getSecond()));
        }
        // Outside poly that is not an offmesh connection.
        return new ClosestPointOnPolyResult(false, closestPointOnDetailEdges(tile, poly, pos, true));
    }

    FindNearestPolyResult findNearestPolyInTile(MeshTile tile, Vector3f center, Vector3f extents) {
        Vector3f nearestPt = null;
        boolean overPoly = false;
        Vector3f bmin = sub(center, extents);
        Vector3f bmax = Vectors.add(center, extents);

        // Get nearby polygons from proximity grid.
        LongArrayList polys = queryPolygonsInTile(tile, bmin, bmax);

        // Find the nearest polygon amongst the nearby polygons.
        long nearest = 0;
        float nearestDistanceSqr = Float.MAX_VALUE;
        for (int i = 0, l = polys.getSize(); i < l; i++) {
            long ref = polys.get(i);
            float d;
            ClosestPointOnPolyResult cpp = closestPointOnPoly(ref, center);
            boolean posOverPoly = cpp.isPosOverPoly;
            Vector3f closestPtPoly = cpp.pos;

            // If a point is directly over a polygon and closer than
            // climb height, favor that instead of straight line nearest point.
            Vector3f diff = sub(center, closestPtPoly);
            if (posOverPoly) {
                d = Math.abs(diff.y) - tile.data.header.walkableClimb;
                d = d > 0 ? d * d : 0;
            } else {
                d = diff.lengthSquared();
            }
            if (d < nearestDistanceSqr) {
                nearestPt = closestPtPoly;
                nearestDistanceSqr = d;
                nearest = ref;
                overPoly = posOverPoly;
            }
        }
        return new FindNearestPolyResult(nearest, nearestPt, overPoly);
    }

    MeshTile getTileAt(int x, int y, int layer) {
        for (MeshTile tile : getTileListByPos(x, y)) {
            if (tile.data.header != null && tile.data.header.x == x && tile.data.header.y == y
                    && tile.data.header.layer == layer) {
                return tile;
            }
        }
        return null;
    }

    List<MeshTile> getNeighbourTilesAt(int x, int y, int side) {
        int nx = x, ny = y;
        switch (side) {
            case 0:
                nx++;
                break;
            case 1:
                nx++;
                ny++;
                break;
            case 2:
                ny++;
                break;
            case 3:
                nx--;
                ny++;
                break;
            case 4:
                nx--;
                break;
            case 5:
                nx--;
                ny--;
                break;
            case 6:
                ny--;
                break;
            case 7:
                nx++;
                ny--;
                break;
        }
        return getTilesAt(nx, ny);
    }

    public List<MeshTile> getTilesAt(int x, int y) {
        List<MeshTile> tiles = new ArrayList<>();
        for (MeshTile tile : getTileListByPos(x, y)) {
            if (tile.data.header != null && tile.data.header.x == x && tile.data.header.y == y) {
                tiles.add(tile);
            }
        }
        return tiles;
    }

    public long getTileRefAt(int x, int y, int layer) {
        return getTileRef(getTileAt(x, y, layer));
    }

    public MeshTile getTileByRef(long ref) {
        if (ref == 0) {
            return null;
        }
        int tileIndex = decodePolyIdTile(ref);
        int tileSalt = decodePolyIdSalt(ref);
        if (tileIndex >= m_maxTiles) {
            return null;
        }
        MeshTile tile = m_tiles[tileIndex];
        if (tile.salt != tileSalt) {
            return null;
        }
        return tile;
    }

    public long getTileRef(MeshTile tile) {
        if (tile == null) return 0;
        return encodePolyId(tile.salt, tile.index, 0);
    }

    public static int computeTileHash(int x, int y, int mask) {
        int h1 = 0x8da6b343; // Large multiplicative constants;
        int h2 = 0xd8163841; // here arbitrarily chosen primes
        int n = h1 * x + h2 * y;
        return n & mask;
    }

    /**
     * Off-mesh connections are stored in the navigation mesh as special 2-vertex polygons with a single edge.
     * At least one of the vertices is expected to be inside a normal polygon. So an off-mesh connection is "entered"
     * from a normal polygon at one of its endpoints. This is the polygon identified by the prevRef parameter.
     * */
    public Result<Pair<Vector3f, Vector3f>> getOffMeshConnectionPolyEndPoints(long prevRef, long polyRef) {
        if (polyRef == 0) {
            return Result.invalidParam("polyRef = 0");
        }

        // Get current polygon
        int[] saltitip = decodePolyId(polyRef);
        int salt = saltitip[0];
        int it = saltitip[1];
        int ip = saltitip[2];
        if (it >= m_maxTiles) {
            return Result.invalidParam("Invalid tile ID > max tiles");
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data.header == null) {
            return Result.invalidParam("Invalid salt or missing tile header");
        }
        MeshTile tile = m_tiles[it];
        if (ip >= tile.data.header.polyCount) {
            return Result.invalidParam("Invalid poly ID > poly count");
        }
        Poly poly = tile.data.polygons[ip];

        // Make sure that the current poly is indeed off-mesh link.
        if (poly.getType() != Poly.DT_POLYTYPE_OFFMESH_CONNECTION) {
            return Result.invalidParam("Invalid poly type");
        }

        // Figure out which way to hand out the vertices.
        int idx0 = 0, idx1 = 1;

        // Find link that points to first vertex.
        for (int i = tile.polyLinks[poly.index]; i != DT_NULL_LINK; i = tile.links.get(i).indexOfNextLink) {
            if (tile.links.get(i).indexOfPolyEdge == 0) {
                if (tile.links.get(i).neighborRef != prevRef) {
                    idx0 = 1;
                    idx1 = 0;
                }
                break;
            }
        }
        Vector3f startPos = new Vector3f();
        Vector3f endPos = new Vector3f();
        copy(startPos, tile.data.vertices, poly.vertices[idx0] * 3);
        copy(endPos, tile.data.vertices, poly.vertices[idx1] * 3);
        return Result.success(new Pair<>(startPos, endPos));

    }

    @SuppressWarnings("unused")
    public Status setPolyFlags(long ref, int flags) {
        if (ref == 0) {
            return Status.FAILURE;
        }
        int[] saltTilePoly = decodePolyId(ref);
        int salt = saltTilePoly[0];
        int it = saltTilePoly[1];
        int ip = saltTilePoly[2];
        if (it >= m_maxTiles) {
            return Status.FAILURE_INVALID_PARAM;
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data == null || m_tiles[it].data.header == null) {
            return Status.FAILURE_INVALID_PARAM;
        }
        MeshTile tile = m_tiles[it];
        if (ip >= tile.data.header.polyCount) {
            return Status.FAILURE_INVALID_PARAM;
        }
        Poly poly = tile.data.polygons[ip];

        // Change flags.
        poly.flags = flags;
        return Status.SUCCESS;
    }

    @SuppressWarnings("unused")
    public Result<Integer> getPolyFlags(long ref) {
        if (ref == 0) {
            return Result.failure();
        }
        int[] saltTilePoly = decodePolyId(ref);
        int salt = saltTilePoly[0];
        int it = saltTilePoly[1];
        int ip = saltTilePoly[2];
        if (it >= m_maxTiles) {
            return Result.invalidParam();
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data == null || m_tiles[it].data.header == null) {
            return Result.invalidParam();
        }
        MeshTile tile = m_tiles[it];
        if (ip >= tile.data.header.polyCount) {
            return Result.invalidParam();
        }
        Poly poly = tile.data.polygons[ip];

        return Result.success(poly.flags);
    }

    @SuppressWarnings("unused")
    public Status setPolyArea(long ref, char area) {
        if (ref == 0) {
            return Status.FAILURE;
        }
        int[] saltTilePoly = decodePolyId(ref);
        int salt = saltTilePoly[0];
        int it = saltTilePoly[1];
        int ip = saltTilePoly[2];
        if (it >= m_maxTiles) {
            return Status.FAILURE;
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data == null || m_tiles[it].data.header == null) {
            return Status.FAILURE_INVALID_PARAM;
        }
        MeshTile tile = m_tiles[it];
        if (ip >= tile.data.header.polyCount) {
            return Status.FAILURE_INVALID_PARAM;
        }
        Poly poly = tile.data.polygons[ip];

        poly.setArea(area);

        return Status.SUCCESS;
    }

    @SuppressWarnings("unused")
    public Result<Integer> getPolyArea(long ref) {
        if (ref == 0) {
            return Result.failure();
        }
        int[] saltTilePoly = decodePolyId(ref);
        int salt = saltTilePoly[0];
        int it = saltTilePoly[1];
        int ip = saltTilePoly[2];
        if (it >= m_maxTiles) {
            return Result.invalidParam();
        }
        if (m_tiles[it].salt != salt || m_tiles[it].data == null || m_tiles[it].data.header == null) {
            return Result.invalidParam();
        }
        MeshTile tile = m_tiles[it];
        if (ip >= tile.data.header.polyCount) {
            return Result.invalidParam();
        }
        Poly poly = tile.data.polygons[ip];

        return Result.success(poly.getArea());
    }

    /**
     * Get flags for edge in detail triangle.
     *
     * @param triFlags  The flags for the triangle (last component of detail vertices above).
     * @param edgeIndex The index of the first vertex of the edge. For instance, if 0,
     * @return flags for edge AB.
     */
    public static int getDetailTriEdgeFlags(int triFlags, int edgeIndex) {
        return (triFlags >> (edgeIndex * 2)) & 0x3;
    }

    private List<MeshTile> getTileListByPos(int x, int z) {
        return posLookup.computeIfAbsent(computeTileHash(x, z, tileLutMask), __ -> new ArrayList<>());
    }
}
