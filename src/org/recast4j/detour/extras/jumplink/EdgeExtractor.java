package org.recast4j.detour.extras.jumplink;

import org.joml.Vector3f;
import org.recast4j.recast.PolyMesh;

import java.util.ArrayList;
import java.util.List;

import static org.recast4j.Vectors.copy;
import static org.recast4j.recast.RecastConstants.RC_MESH_NULL_IDX;

class EdgeExtractor {

    Edge[] extractEdges(PolyMesh mesh) {
        List<Edge> edges = new ArrayList<>();
        if (mesh != null) {
            Vector3f orig = mesh.bmin;
            float cs = mesh.cellSize;
            float ch = mesh.cellHeight;
            for (int i = 0; i < mesh.numPolygons; i++) {
                int nvp = mesh.maxVerticesPerPolygon;
                int p = i * 2 * nvp;
                for (int j = 0; j < nvp; ++j) {
                    if (mesh.polygons[p + j] == RC_MESH_NULL_IDX) {
                        break;
                    }
                    // Skip connected edges.
                    if ((mesh.polygons[p + nvp + j] & 0x8000) != 0) {
                        int dir = mesh.polygons[p + nvp + j] & 0xf;
                        if (dir == 0xf) {// Border
                            if (mesh.polygons[p + nvp + j] != RC_MESH_NULL_IDX) {
                                continue;
                            }
                            int nj = j + 1;
                            if (nj >= nvp || mesh.polygons[p + nj] == RC_MESH_NULL_IDX) {
                                nj = 0;
                            }
                            Edge e = new Edge();
                            copy(e.a, mesh.vertices, mesh.polygons[p + nj] * 3);
                            e.a.mul(cs, ch, cs).add(orig);
                            copy(e.b, mesh.vertices, mesh.polygons[p + j] * 3);
                            e.b.mul(cs, ch, cs).add(orig);
                            edges.add(e);
                        }
                    }
                }
            }
        }
        return edges.toArray(new Edge[0]);

    }

}
