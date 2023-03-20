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
package org.recast4j.recast

import org.recast4j.Edge
import org.recast4j.recast.RecastConstants.RC_BORDER_VERTEX
import org.recast4j.recast.RecastConstants.RC_MESH_NULL_IDX
import org.recast4j.recast.RecastConstants.RC_MULTIPLE_REGS
import java.util.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

object RecastMesh {
    private const val MAX_MESH_VERTICES_POLY = 0xffff
    var VERTEX_BUCKET_COUNT = 1 shl 12
    private fun buildMeshAdjacency(polys: IntArray, npolys: Int, nvertices: Int, verticesPerPoly: Int) {
        // Based on code by Eric Lengyel from:
        // http://www.terathon.com/code/edges.php
        val maxEdgeCount = npolys * verticesPerPoly
        val firstEdge = IntArray(nvertices + maxEdgeCount)
        var edgeCount = 0
        val edges = arrayOfNulls<Edge>(maxEdgeCount)
        for (i in 0 until nvertices) firstEdge[i] = RC_MESH_NULL_IDX
        for (i in 0 until npolys) {
            val t = i * verticesPerPoly * 2
            for (j in 0 until verticesPerPoly) {
                if (polys[t + j] == RC_MESH_NULL_IDX) break
                val v0 = polys[t + j]
                val v1 =
                    if (j + 1 >= verticesPerPoly || polys[t + j + 1] == RC_MESH_NULL_IDX) polys[t] else polys[t + j + 1]
                if (v0 < v1) {
                    val edge = Edge()
                    edges[edgeCount] = edge
                    edge.vert0 = v0
                    edge.vert1 = v1
                    edge.poly0 = i
                    edge.polyEdge0 = j
                    edge.poly1 = i
                    edge.polyEdge1 = 0
                    // Insert edge
                    firstEdge[nvertices + edgeCount] = firstEdge[v0]
                    firstEdge[v0] = edgeCount
                    edgeCount++
                }
            }
        }
        for (i in 0 until npolys) {
            val t = i * verticesPerPoly * 2
            for (j in 0 until verticesPerPoly) {
                if (polys[t + j] == RC_MESH_NULL_IDX) break
                val v0 = polys[t + j]
                val v1 =
                    if (j + 1 >= verticesPerPoly || polys[t + j + 1] == RC_MESH_NULL_IDX) polys[t] else polys[t + j + 1]
                if (v0 > v1) {
                    var e = firstEdge[v1]
                    while (e != RC_MESH_NULL_IDX) {
                        val edge = edges[e]
                        if (edge!!.vert1 == v0 && edge.poly0 == edge.poly1) {
                            edge.poly1 = i
                            edge.polyEdge1 = j
                            break
                        }
                        e = firstEdge[nvertices + e]
                    }
                }
            }
        }

        // Store adjacency
        for (i in 0 until edgeCount) {
            val e = edges[i]
            if (e!!.poly0 != e.poly1) {
                val p0 = e.poly0 * verticesPerPoly * 2
                val p1 = e.poly1 * verticesPerPoly * 2
                polys[p0 + verticesPerPoly + e.polyEdge0] = e.poly1
                polys[p1 + verticesPerPoly + e.polyEdge1] = e.poly0
            }
        }
    }

    private fun computeVertexHash(x: Int, y: Int, z: Int): Int {
        val h1 = -0x72594cbd // Large multiplicative constants;
        val h2 = -0x27e9c7bf // here arbitrarily chosen primes
        val h3 = -0x34e54ce1
        val n = h1 * x + h2 * y + h3 * z
        return n and VERTEX_BUCKET_COUNT - 1
    }

    private fun addVertex(
        x: Int,
        y: Int,
        z: Int,
        vertices: IntArray,
        firstVert: IntArray,
        nextVert: IntArray,
        nv0: Int
    ): IntArray {
        var nv = nv0
        val bucket = computeVertexHash(x, 0, z) // todo why is y skipped?
        var i = firstVert[bucket]
        while (i != -1) {
            val v = i * 3
            if (vertices[v] == x && abs(vertices[v + 1] - y) <= 2 && vertices[v + 2] == z) return intArrayOf(i, nv)
            i = nextVert[i] // next
        }

        // Could not find, create new.
        i = nv
        nv++
        val v = i * 3
        vertices[v] = x
        vertices[v + 1] = y
        vertices[v + 2] = z
        nextVert[i] = firstVert[bucket]
        firstVert[bucket] = i
        return intArrayOf(i, nv)
    }

    fun prev(i: Int, n: Int): Int {
        return if (i - 1 >= 0) i - 1 else n - 1
    }

    fun next(i: Int, n: Int): Int {
        return if (i + 1 < n) i + 1 else 0
    }

    private fun area2(vertices: IntArray?, a: Int, b: Int, c: Int): Int {
        return ((vertices!![b] - vertices[a]) * (vertices[c + 2] - vertices[a + 2])
                - (vertices[c] - vertices[a]) * (vertices[b + 2] - vertices[a + 2]))
    }

    // Returns true iff c is strictly to the left of the directed
    // line through a to b.
    fun left(vertices: IntArray?, a: Int, b: Int, c: Int): Boolean {
        return area2(vertices, a, b, c) < 0
    }

    fun leftOn(vertices: IntArray?, a: Int, b: Int, c: Int): Boolean {
        return area2(vertices, a, b, c) <= 0
    }

    private fun collinear(vertices: IntArray?, a: Int, b: Int, c: Int): Boolean {
        return area2(vertices, a, b, c) == 0
    }

    // Returns true iff ab properly intersects cd: they share
    // a point interior to both segments. The properness of the
    // intersection is ensured by using strict leftness.
    private fun intersectProp(vertices: IntArray?, a: Int, b: Int, c: Int, d: Int): Boolean {
        // Eliminate improper cases.
        return if (collinear(vertices, a, b, c) || collinear(vertices, a, b, d) || collinear(
                vertices,
                c,
                d,
                a
            )
            || collinear(vertices, c, d, b)
        ) false else left(vertices, a, b, c) xor left(
            vertices,
            a,
            b,
            d
        ) && left(vertices, c, d, a) xor left(vertices, c, d, b)
    }

    // Returns T iff (a,b,c) are collinear and point c lies
    // on the closed segement ab.
    private fun between(vertices: IntArray, a: Int, b: Int, c: Int): Boolean {
        if (!collinear(vertices, a, b, c)) return false
        // If ab not vertical, check betweenness on x; else on y.
        return if (vertices[a] != vertices[b])
                (vertices[a] <= vertices[c] && vertices[c] <= vertices[b] || vertices[a] >= vertices[c] && vertices[c] >= vertices[b])
        else
                (vertices[a + 2] <= vertices[c + 2] && vertices[c + 2] <= vertices[b + 2] || vertices[a + 2] >= vertices[c + 2] && vertices[c + 2] >= vertices[b + 2])
    }

    // Returns true iff segments ab and cd intersect, properly or improperly.
    fun intersect(vertices: IntArray, a: Int, b: Int, c: Int, d: Int): Boolean {
        return if (intersectProp(vertices, a, b, c, d)) true else between(
            vertices, a, b, c
        ) || between(vertices, a, b, d) || between(vertices, c, d, a)
                || between(vertices, c, d, b)
    }

    fun vequal(vertices: IntArray?, a: Int, b: Int): Boolean {
        return vertices!![a] == vertices[b] && vertices[a + 2] == vertices[b + 2]
    }

    // Returns T iff (v_i, v_j) is a proper internal *or* external
    // diagonal of P, *ignoring edges incident to v_i and v_j*.
    private fun diagonalie(i: Int, j: Int, n: Int, vertices: IntArray, indices: IntArray): Boolean {
        val d0 = (indices[i] and 0x0fffffff) * 4
        val d1 = (indices[j] and 0x0fffffff) * 4

        // For each edge (k,k+1) of P
        for (k in 0 until n) {
            val k1 = next(k, n)
            // Skip edges incident to i or j
            if (k == i || k1 == i || k == j || k1 != j) {
                val p0 = (indices[k] and 0x0fffffff) * 4
                val p1 = (indices[k1] and 0x0fffffff) * 4
                if (vequal(vertices, d0, p0) || vequal(vertices, d1, p0) || vequal(vertices, d0, p1) || vequal(
                        vertices,
                        d1,
                        p1
                    )
                ) continue
                if (intersect(vertices, d0, d1, p0, p1)) return false
            }
        }
        return true
    }

    // Returns true iff the diagonal (i,j) is strictly internal to the
    // polygon P in the neighborhood of the i endpoint.
    private fun inCone(i: Int, j: Int, n: Int, vertices: IntArray?, indices: IntArray): Boolean {
        val pi = (indices[i] and 0x0fffffff) * 4
        val pj = (indices[j] and 0x0fffffff) * 4
        val pi1 = (indices[next(i, n)] and 0x0fffffff) * 4
        val pin1 = (indices[prev(i, n)] and 0x0fffffff) * 4
        // If P[i] is a convex vertex [ i+1 left or on (i-1,i) ].
        return if (leftOn(vertices, pin1, pi, pi1)) {
            left(vertices, pi, pj, pin1) && left(vertices, pj, pi, pi1)
        } else !(leftOn(
            vertices,
            pi,
            pj,
            pi1
        ) && leftOn(vertices, pj, pi, pin1))
        // Assume (i-1,i,i+1) not collinear.
        // else P[i] is reflex.
    }

    // Returns T iff (v_i, v_j) is a proper internal
    // diagonal of P.
    private fun diagonal(i: Int, j: Int, n: Int, vertices: IntArray, indices: IntArray): Boolean {
        return inCone(i, j, n, vertices, indices) && diagonalie(i, j, n, vertices, indices)
    }

    private fun diagonalieLoose(i: Int, j: Int, n: Int, vertices: IntArray?, indices: IntArray): Boolean {
        val d0 = (indices[i] and 0x0fffffff) * 4
        val d1 = (indices[j] and 0x0fffffff) * 4

        // For each edge (k,k+1) of P
        for (k in 0 until n) {
            val k1 = next(k, n)
            // Skip edges incident to i or j
            if (k == i || k1 == i || k == j || k1 != j) {
                val p0 = (indices[k] and 0x0fffffff) * 4
                val p1 = (indices[k1] and 0x0fffffff) * 4
                if (vequal(vertices, d0, p0) || vequal(vertices, d1, p0) || vequal(vertices, d0, p1) || vequal(
                        vertices,
                        d1,
                        p1
                    )
                ) continue
                if (intersectProp(vertices, d0, d1, p0, p1)) return false
            }
        }
        return true
    }

    private fun inConeLoose(i: Int, j: Int, n: Int, vertices: IntArray?, indices: IntArray): Boolean {
        val pi = (indices[i] and 0x0fffffff) * 4
        val pj = (indices[j] and 0x0fffffff) * 4
        val pi1 = (indices[next(i, n)] and 0x0fffffff) * 4
        val pin1 = (indices[prev(i, n)] and 0x0fffffff) * 4

        // If P[i] is a convex vertex [ i+1 left or on (i-1,i) ].
        return if (leftOn(vertices, pin1, pi, pi1)) leftOn(
            vertices,
            pi,
            pj,
            pin1
        ) && leftOn(vertices, pj, pi, pi1) else !(leftOn(
            vertices,
            pi,
            pj,
            pi1
        ) && leftOn(vertices, pj, pi, pin1))
        // Assume (i-1,i,i+1) not collinear.
        // else P[i] is reflex.
    }

    private fun diagonalLoose(i: Int, j: Int, n: Int, vertices: IntArray?, indices: IntArray): Boolean {
        return inConeLoose(i, j, n, vertices, indices) && diagonalieLoose(i, j, n, vertices, indices)
    }

    private fun triangulate(n: Int, vertices: IntArray, indices: IntArray, tris: IntArray): Int {
        var n = n
        var ntris = 0

        // The last bit of the index is used to indicate if the vertex can be removed.
        for (i in 0 until n) {
            val i1 = next(i, n)
            val i2 = next(i1, n)
            if (diagonal(i, i2, n, vertices, indices)) {
                indices[i1] = indices[i1] or -0x80000000
            }
        }
        while (n > 3) {
            var minLen = -1
            var mini = -1
            for (i in 0 until n) {
                val i1 = next(i, n)
                if (indices[i1] and -0x80000000 != 0) {
                    val p0 = (indices[i] and 0x0fffffff) * 4
                    val p2 = (indices[next(i1, n)] and 0x0fffffff) * 4
                    val dx = vertices[p2] - vertices[p0]
                    val dy = vertices[p2 + 2] - vertices[p0 + 2]
                    val len = dx * dx + dy * dy
                    if (minLen < 0 || len < minLen) {
                        minLen = len
                        mini = i
                    }
                }
            }
            if (mini == -1) {
                // We might get here because the contour has overlapping segments, like this:
                //
                // A o-o=====o---o B
                // / |C D| \
                // o o o o
                // : : : :
                // We'll try to recover by loosing up the inCone test a bit so that a diagonal
                // like A-B or C-D can be found and we can continue.
                for (i in 0 until n) {
                    val i1 = next(i, n)
                    val i2 = next(i1, n)
                    if (diagonalLoose(i, i2, n, vertices, indices)) {
                        val p0 = (indices[i] and 0x0fffffff) * 4
                        val p2 = (indices[next(i2, n)] and 0x0fffffff) * 4
                        val dx = vertices[p2] - vertices[p0]
                        val dy = vertices[p2 + 2] - vertices[p0 + 2]
                        val len = dx * dx + dy * dy
                        if (minLen < 0 || len < minLen) {
                            minLen = len
                            mini = i
                        }
                    }
                }
                if (mini == -1) {
                    // The contour is messed up. This sometimes happens
                    // if the contour simplification is too aggressive.
                    return -ntris
                }
            }
            var i = mini
            var i1 = next(i, n)
            val i2 = next(i1, n)
            tris[ntris * 3] = indices[i] and 0x0fffffff
            tris[ntris * 3 + 1] = indices[i1] and 0x0fffffff
            tris[ntris * 3 + 2] = indices[i2] and 0x0fffffff
            ntris++

            // Removes P[i1] by copying P[i+1]...P[n-1] left one index.
            n--
            if (n - i1 >= 0) System.arraycopy(indices, i1 + 1, indices, i1, n - i1)
            if (i1 >= n) i1 = 0
            i = prev(i1, n)
            // Update diagonal flags.
            if (diagonal(prev(i, n), i1, n, vertices, indices)) indices[i] = indices[i] or -0x80000000 else indices[i] =
                indices[i] and 0x0fffffff
            if (diagonal(i, next(i1, n), n, vertices, indices)) indices[i1] =
                indices[i1] or -0x80000000 else indices[i1] = indices[i1] and 0x0fffffff
        }

        // Append the remaining triangle.
        tris[ntris * 3] = indices[0] and 0x0fffffff
        tris[ntris * 3 + 1] = indices[1] and 0x0fffffff
        tris[ntris * 3 + 2] = indices[2] and 0x0fffffff
        ntris++
        return ntris
    }

    private fun countPolyVertices(p: IntArray, j: Int, nvp: Int): Int {
        for (i in 0 until nvp) if (p[i + j] == RC_MESH_NULL_IDX) return i
        return nvp
    }

    private fun uleft(vertices: IntArray, a: Int, b: Int, c: Int): Boolean {
        return (vertices[b] - vertices[a]) * (vertices[c + 2] - vertices[a + 2])-(vertices[c] - vertices[a]) * (vertices[b + 2] - vertices[a + 2]) < 0
    }

    private fun getPolyMergeValue(polys: IntArray, pa: Int, pb: Int, vertices: IntArray, nvp: Int): IntArray {
        var ea = -1
        var eb = -1
        val na = countPolyVertices(polys, pa, nvp)
        val nb = countPolyVertices(polys, pb, nvp)

        // If the merged polygon would be too big, do not merge.
        if (na + nb - 2 > nvp) return intArrayOf(-1, ea, eb)

        // Check if the polygons share an edge.
        for (i in 0 until na) {
            var va0 = polys[pa + i]
            var va1 = polys[pa + (i + 1) % na]
            if (va0 > va1) {
                val temp = va0
                va0 = va1
                va1 = temp
            }
            for (j in 0 until nb) {
                var vb0 = polys[pb + j]
                var vb1 = polys[pb + (j + 1) % nb]
                if (vb0 > vb1) {
                    val temp = vb0
                    vb0 = vb1
                    vb1 = temp
                }
                if (va0 == vb0 && va1 == vb1) {
                    ea = i
                    eb = j
                    break
                }
            }
        }

        // No common edge, cannot merge.
        if (ea == -1 || eb == -1) return intArrayOf(-1, ea, eb)

        // Check to see if the merged polygon would be convex.
        var va: Int
        var vb: Int
        var vc: Int
        va = polys[pa + (ea + na - 1) % na]
        vb = polys[pa + ea]
        vc = polys[pb + (eb + 2) % nb]
        if (!uleft(vertices, va * 3, vb * 3, vc * 3)) return intArrayOf(-1, ea, eb)
        va = polys[pb + (eb + nb - 1) % nb]
        vb = polys[pb + eb]
        vc = polys[pa + (ea + 2) % na]
        if (!uleft(vertices, va * 3, vb * 3, vc * 3)) return intArrayOf(-1, ea, eb)
        va = polys[pa + ea]
        vb = polys[pa + (ea + 1) % na]
        val dx = vertices[va * 3] - vertices[vb * 3]
        val dy = vertices[va * 3 + 2] - vertices[vb * 3 + 2]
        return intArrayOf(dx * dx + dy * dy, ea, eb)
    }

    private fun mergePolyVertices(polys: IntArray, pa: Int, pb: Int, ea: Int, eb: Int, tmp: Int, nvp: Int) {
        val na = countPolyVertices(polys, pa, nvp)
        val nb = countPolyVertices(polys, pb, nvp)

        // Merge polygons.
        Arrays.fill(polys, tmp, tmp + nvp, RC_MESH_NULL_IDX)
        var n = 0
        // Add pa
        for (i in 0 until na - 1) {
            polys[tmp + n] = polys[pa + (ea + 1 + i) % na]
            n++
        }
        // Add pb
        for (i in 0 until nb - 1) {
            polys[tmp + n] = polys[pb + (eb + 1 + i) % nb]
            n++
        }
        System.arraycopy(polys, tmp, polys, pa, nvp)
    }

    private fun pushFront(v: Int, arr: IntArray, an: Int) {
        if (an - 1 >= 0) System.arraycopy(arr, 0, arr, 1, an - 1)
        arr[0] = v
    }

    private fun pushBack(v: Int, arr: IntArray, an: Int) {
        arr[an] = v
    }

    private fun canRemoveVertex(mesh: PolyMesh, rem: Int): Boolean {
        val nvp = mesh.maxVerticesPerPolygon

        // Count number of polygons to remove.
        var numTouchedVertices = 0
        var numRemainingEdges = 0
        for (i in 0 until mesh.numPolygons) {
            val p = i * nvp * 2
            val nv = countPolyVertices(mesh.polygons, p, nvp)
            var numRemoved = 0
            var numVertices = 0
            for (j in 0 until nv) {
                if (mesh.polygons[p + j] == rem) {
                    numTouchedVertices++
                    numRemoved++
                }
                numVertices++
            }
            if (numRemoved != 0) {
                numRemainingEdges += numVertices - (numRemoved + 1)
            }
        }
        // There would be too few edges remaining to create a polygon.
        // This can happen for example when a tip of a triangle is marked
        // as deletion, but there are no other polys that share the vertex.
        // In this case, the vertex should not be removed.
        if (numRemainingEdges <= 2) return false

        // Find edges which share the removed vertex.
        val maxEdges = numTouchedVertices * 2
        var nedges = 0
        val edges = IntArray(maxEdges * 3)
        for (i in 0 until mesh.numPolygons) {
            val p = i * nvp * 2
            val nv = countPolyVertices(mesh.polygons, p, nvp)

            // Collect edges which touches the removed vertex.
            var j = 0
            var k = nv - 1
            while (j < nv) {
                if (mesh.polygons[p + j] == rem || mesh.polygons[p + k] == rem) {
                    // Arrange edge so that a=rem.
                    var a = mesh.polygons[p + j]
                    var b = mesh.polygons[p + k]
                    if (b == rem) {
                        val temp = a
                        a = b
                        b = temp
                    }
                    // Check if the edge exists
                    var exists = false
                    for (m in 0 until nedges) {
                        val e = m * 3
                        if (edges[e + 1] == b) {
                            // Exists, increment vertex share count.
                            edges[e + 2]++
                            exists = true
                        }
                    }
                    // Add new edge.
                    if (!exists) {
                        val e = nedges * 3
                        edges[e] = a
                        edges[e + 1] = b
                        edges[e + 2] = 1
                        nedges++
                    }
                }
                k = j++
            }
        }

        // There should be no more than 2 open edges.
        // This catches the case that two non-adjacent polygons
        // share the removed vertex. In that case, do not remove the vertex.
        var numOpenEdges = 0
        for (i in 0 until nedges) {
            if (edges[i * 3 + 2] < 2) numOpenEdges++
        }
        return numOpenEdges <= 2
    }

    private fun removeVertex(ctx: Telemetry?, mesh: PolyMesh, rem: Int, maxTris: Int) {
        val nvp = mesh.maxVerticesPerPolygon

        // Count number of polygons to remove.
        var numRemovedVertices = 0
        for (i in 0 until mesh.numPolygons) {
            val p = i * nvp * 2
            val nv = countPolyVertices(mesh.polygons, p, nvp)
            for (j in 0 until nv) {
                if (mesh.polygons[p + j] == rem) numRemovedVertices++
            }
        }
        var nedges = 0
        val edges = IntArray(numRemovedVertices * nvp * 4)
        var nhole = 0
        val hole = IntArray(numRemovedVertices * nvp)
        var nhreg = 0
        val hreg = IntArray(numRemovedVertices * nvp)
        var nharea = 0
        val harea = IntArray(numRemovedVertices * nvp)
        run {
            var i = 0
            while (i < mesh.numPolygons) {
                val p = i * nvp * 2
                val nv = countPolyVertices(mesh.polygons, p, nvp)
                var hasRem = false
                for (j in 0 until nv) if (mesh.polygons[p + j] == rem) {
                    hasRem = true
                    break
                }
                if (hasRem) {
                    // Collect edges which does not touch the removed vertex.
                    var j = 0
                    var k = nv - 1
                    while (j < nv) {
                        if (mesh.polygons[p + j] != rem && mesh.polygons[p + k] != rem) {
                            val e = nedges * 4
                            edges[e] = mesh.polygons[p + k]
                            edges[e + 1] = mesh.polygons[p + j]
                            edges[e + 2] = mesh.regionIds[i]
                            edges[e + 3] = mesh.areaIds[i]
                            nedges++
                        }
                        k = j++
                    }
                    // Remove the polygon.
                    val p2 = (mesh.numPolygons - 1) * nvp * 2
                    if (p != p2) {
                        System.arraycopy(mesh.polygons, p2, mesh.polygons, p, nvp)
                    }
                    Arrays.fill(mesh.polygons, p + nvp, p + nvp + nvp, RC_MESH_NULL_IDX)
                    mesh.regionIds[i] = mesh.regionIds[mesh.numPolygons - 1]
                    mesh.areaIds[i] = mesh.areaIds[mesh.numPolygons - 1]
                    mesh.numPolygons--
                    --i
                }
                ++i
            }
        }

        // Remove vertex.
        for (i in rem until mesh.numVertices - 1) {
            mesh.vertices[i * 3] = mesh.vertices[(i + 1) * 3]
            mesh.vertices[i * 3 + 1] = mesh.vertices[(i + 1) * 3 + 1]
            mesh.vertices[i * 3 + 2] = mesh.vertices[(i + 1) * 3 + 2]
        }
        mesh.numVertices--

        // Adjust indices to match the removed vertex layout.
        for (i in 0 until mesh.numPolygons) {
            val p = i * nvp * 2
            val nv = countPolyVertices(mesh.polygons, p, nvp)
            for (j in 0 until nv) if (mesh.polygons[p + j] > rem) mesh.polygons[p + j]--
        }
        for (i in 0 until nedges) {
            if (edges[i * 4] > rem) edges[i * 4]--
            if (edges[i * 4 + 1] > rem) edges[i * 4 + 1]--
        }
        if (nedges == 0) return

        // Start with one vertex, keep appending connected
        // segments to the start and end of the hole.
        hole[nhole++] = edges[0]
        hreg[nhreg++] = edges[2]
        harea[nharea++] = edges[3]
        while (nedges != 0) {
            var match = false
            var i = 0
            while (i < nedges) {
                val ea = edges[i * 4]
                val eb = edges[i * 4 + 1]
                val r = edges[i * 4 + 2]
                val a = edges[i * 4 + 3]
                var add = false
                if (hole[0] == eb) {
                    // The segment matches the beginning of the hole boundary.
                    pushFront(ea, hole, ++nhole)
                    pushFront(r, hreg, ++nhreg)
                    pushFront(a, harea, ++nharea)
                    add = true
                } else if (hole[nhole - 1] == ea) {
                    // The segment matches the end of the hole boundary.
                    pushBack(eb, hole, nhole++)
                    pushBack(r, hreg, nhreg++)
                    pushBack(a, harea, nharea++)
                    add = true
                }
                if (add) {
                    // The edge segment was added, remove it.
                    edges[i * 4] = edges[(nedges - 1) * 4]
                    edges[i * 4 + 1] = edges[(nedges - 1) * 4 + 1]
                    edges[i * 4 + 2] = edges[(nedges - 1) * 4 + 2]
                    edges[i * 4 + 3] = edges[(nedges - 1) * 4 + 3]
                    --nedges
                    match = true
                    --i
                }
                ++i
            }
            if (!match) break
        }
        val tris = IntArray(nhole * 3)
        val tvertices = IntArray(nhole * 4)
        val thole = IntArray(nhole)

        // Generate temp vertex array for triangulation.
        for (i in 0 until nhole) {
            val pi = hole[i]
            tvertices[i * 4] = mesh.vertices[pi * 3]
            tvertices[i * 4 + 1] = mesh.vertices[pi * 3 + 1]
            tvertices[i * 4 + 2] = mesh.vertices[pi * 3 + 2]
            tvertices[i * 4 + 3] = 0
            thole[i] = i
        }

        // Triangulate the hole.
        var ntris = triangulate(nhole, tvertices, thole, tris)
        if (ntris < 0) {
            ntris = -ntris
            ctx!!.warn("removeVertex: triangulate() returned bad results.")
        }

        // Merge the hole triangles back to polygons.
        val polys = IntArray((ntris + 1) * nvp)
        val pregs = IntArray(ntris)
        val pareas = IntArray(ntris)
        val tmpPoly = ntris * nvp

        // Build initial polygons.
        var npolys = 0
        Arrays.fill(polys, 0, ntris * nvp, RC_MESH_NULL_IDX)
        for (j in 0 until ntris) {
            val t = j * 3
            if (tris[t] != tris[t + 1] && tris[t] != tris[t + 2] && tris[t + 1] != tris[t + 2]) {
                polys[npolys * nvp] = hole[tris[t]]
                polys[npolys * nvp + 1] = hole[tris[t + 1]]
                polys[npolys * nvp + 2] = hole[tris[t + 2]]

                // If this polygon covers multiple region types then
                // mark it as such
                if (hreg[tris[t]] != hreg[tris[t + 1]] || hreg[tris[t + 1]] != hreg[tris[t + 2]]) pregs[npolys] =
                    RC_MULTIPLE_REGS else pregs[npolys] = hreg[tris[t]]
                pareas[npolys] = harea[tris[t]]
                npolys++
            }
        }
        if (npolys == 0) return

        // Merge polygons.
        if (nvp > 3) {
            while (true) {

                // Find best polygons to merge.
                var bestMergeVal = 0
                var bestPa = 0
                var bestPb = 0
                var bestEa = 0
                var bestEb = 0
                for (j in 0 until npolys - 1) {
                    val pj = j * nvp
                    for (k in j + 1 until npolys) {
                        val pk = k * nvp
                        val veaeb = getPolyMergeValue(polys, pj, pk, mesh.vertices, nvp)
                        val v = veaeb[0]
                        val ea = veaeb[1]
                        val eb = veaeb[2]
                        if (v > bestMergeVal) {
                            bestMergeVal = v
                            bestPa = j
                            bestPb = k
                            bestEa = ea
                            bestEb = eb
                        }
                    }
                }
                if (bestMergeVal > 0) {
                    // Found best, merge.
                    val pa = bestPa * nvp
                    val pb = bestPb * nvp
                    mergePolyVertices(polys, pa, pb, bestEa, bestEb, tmpPoly, nvp)
                    if (pregs[bestPa] != pregs[bestPb]) pregs[bestPa] = RC_MULTIPLE_REGS
                    val last = (npolys - 1) * nvp
                    if (pb != last) {
                        System.arraycopy(polys, last, polys, pb, nvp)
                    }
                    pregs[bestPb] = pregs[npolys - 1]
                    pareas[bestPb] = pareas[npolys - 1]
                    npolys--
                } else {
                    // Could not merge any polygons, stop.
                    break
                }
            }
        }

        // Store polygons.
        for (i in 0 until npolys) {
            if (mesh.numPolygons >= maxTris) break
            val p = mesh.numPolygons * nvp * 2
            Arrays.fill(mesh.polygons, p, p + nvp * 2, RC_MESH_NULL_IDX)
            if (nvp >= 0) System.arraycopy(polys, i * nvp, mesh.polygons, p, nvp)
            mesh.regionIds[mesh.numPolygons] = pregs[i]
            mesh.areaIds[mesh.numPolygons] = pareas[i]
            mesh.numPolygons++
            if (mesh.numPolygons > maxTris) {
                throw RuntimeException("removeVertex: Too many polygons " + mesh.numPolygons + " (max:" + maxTris + ".")
            }
        }
    }

    /// @par
    ///
    /// @note If the mesh data is to be used to construct a Detour navigation mesh, then the upper
    /// limit must be retricted to <= #DT_VERTICES_PER_POLYGON.
    ///
    /// @see rcAllocPolyMesh, rcContourSet, rcPolyMesh, rcConfig
    fun buildPolyMesh(ctx: Telemetry?, cset: ContourSet, nvp: Int): PolyMesh {
        ctx?.startTimer("POLYMESH")
        val mesh = PolyMesh()
        mesh.bmin.set(cset.bmin)
        mesh.bmax.set(cset.bmax)
        mesh.cellSize = cset.cellSize
        mesh.cellHeight = cset.cellHeight
        mesh.borderSize = cset.borderSize
        mesh.maxEdgeError = cset.maxError
        var maxVertices = 0
        var maxTris = 0
        var maxVerticesPerCont = 0
        for (i in cset.contours.indices) {
            // Skip null contours.
            if (cset.contours[i].numVertices < 3) continue
            maxVertices += cset.contours[i].numVertices
            maxTris += cset.contours[i].numVertices - 2
            maxVerticesPerCont = max(maxVerticesPerCont, cset.contours[i].numVertices)
        }
        if (maxVertices >= 0xfffe) {
            throw RuntimeException("rcBuildPolyMesh: Too many vertices $maxVertices")
        }
        val vflags = IntArray(maxVertices)
        mesh.vertices = IntArray(maxVertices * 3)
        mesh.polygons = IntArray(maxTris * nvp * 2)
        Arrays.fill(mesh.polygons, RC_MESH_NULL_IDX)
        mesh.regionIds = IntArray(maxTris)
        mesh.areaIds = IntArray(maxTris)
        mesh.numVertices = 0
        mesh.numPolygons = 0
        mesh.maxVerticesPerPolygon = nvp
        mesh.numAllocatedPolygons = maxTris
        val nextVert = IntArray(maxVertices)
        val firstVert = IntArray(VERTEX_BUCKET_COUNT)
        for (i in 0 until VERTEX_BUCKET_COUNT) firstVert[i] = -1
        val indices = IntArray(maxVerticesPerCont)
        val tris = IntArray(maxVerticesPerCont * 3)
        val polys = IntArray((maxVerticesPerCont + 1) * nvp)
        val tmpPoly = maxVerticesPerCont * nvp
        for (i in cset.contours.indices) {
            val cont = cset.contours[i]

            // Skip null contours.
            if (cont.numVertices < 3) continue

            // Triangulate contour
            for (j in 0 until cont.numVertices) indices[j] = j
            var ntris = triangulate(cont.numVertices, cont.vertices!!, indices, tris)
            if (ntris <= 0) {
                // Bad triangulation, should not happen.
                ctx?.warn("buildPolyMesh: Bad triangulation Contour $i.")
                ntris = -ntris
            }

            // Add and merge vertices.
            for (j in 0 until cont.numVertices) {
                val v = j * 4
                val inv = addVertex(
                    cont.vertices!![v], cont.vertices!![v + 1], cont.vertices!![v + 2], mesh.vertices, firstVert,
                    nextVert, mesh.numVertices
                )
                indices[j] = inv[0]
                mesh.numVertices = inv[1]
                if (cont.vertices!![v + 3] and RC_BORDER_VERTEX != 0) {
                    // This vertex should be removed.
                    vflags[indices[j]] = 1
                }
            }

            // Build initial polygons.
            var npolys = 0
            Arrays.fill(polys, RC_MESH_NULL_IDX)
            for (j in 0 until ntris) {
                val t = j * 3
                if (tris[t] != tris[t + 1] && tris[t] != tris[t + 2] && tris[t + 1] != tris[t + 2]) {
                    polys[npolys * nvp] = indices[tris[t]]
                    polys[npolys * nvp + 1] = indices[tris[t + 1]]
                    polys[npolys * nvp + 2] = indices[tris[t + 2]]
                    npolys++
                }
            }
            if (npolys == 0) continue

            // Merge polygons.
            if (nvp > 3) {
                while (true) {

                    // Find the best polygons to merge.
                    var bestMergeVal = 0
                    var bestPa = 0
                    var bestPb = 0
                    var bestEa = 0
                    var bestEb = 0
                    for (j in 0 until npolys - 1) {
                        val pj = j * nvp
                        for (k in j + 1 until npolys) {
                            val pk = k * nvp
                            val veaeb = getPolyMergeValue(polys, pj, pk, mesh.vertices, nvp)
                            val v = veaeb[0]
                            val ea = veaeb[1]
                            val eb = veaeb[2]
                            if (v > bestMergeVal) {
                                bestMergeVal = v
                                bestPa = j
                                bestPb = k
                                bestEa = ea
                                bestEb = eb
                            }
                        }
                    }
                    if (bestMergeVal > 0) {
                        // Found best, merge.
                        val pa = bestPa * nvp
                        val pb = bestPb * nvp
                        mergePolyVertices(polys, pa, pb, bestEa, bestEb, tmpPoly, nvp)
                        val lastPoly = (npolys - 1) * nvp
                        if (pb != lastPoly) {
                            System.arraycopy(polys, lastPoly, polys, pb, nvp)
                        }
                        npolys--
                    } else {
                        // Could not merge any polygons, stop.
                        break
                    }
                }
            }

            // Store polygons.
            for (j in 0 until npolys) {
                val p = mesh.numPolygons * nvp * 2
                val q = j * nvp
                if (nvp >= 0) System.arraycopy(polys, q, mesh.polygons, p, nvp)
                mesh.regionIds[mesh.numPolygons] = cont.reg
                mesh.areaIds[mesh.numPolygons] = cont.area
                mesh.numPolygons++
                if (mesh.numPolygons > maxTris) {
                    throw RuntimeException(
                        "rcBuildPolyMesh: Too many polygons " + mesh.numPolygons + " (max:" + maxTris + ")."
                    )
                }
            }
        }

        // Remove edge vertices.
        var i = 0
        while (i < mesh.numVertices) {
            if (vflags[i] != 0) {
                if (!canRemoveVertex(mesh, i)) {
                    ++i
                    continue
                }
                removeVertex(ctx, mesh, i, maxTris)
                // Remove vertex
                // Note: mesh.nvertices is already decremented inside removeVertex()!
                // Fixup vertex flags
                if (mesh.numVertices - i >= 0) System.arraycopy(vflags, i + 1, vflags, i, mesh.numVertices - i)
                --i
            }
            ++i
        }

        // Calculate adjacency.
        buildMeshAdjacency(mesh.polygons, mesh.numPolygons, mesh.numVertices, nvp)

        // Find portal edges
        if (mesh.borderSize > 0) {
            val w = cset.width
            val h = cset.height
            for (k in 0 until mesh.numPolygons) {
                val p = k * 2 * nvp
                for (j in 0 until nvp) {
                    if (mesh.polygons[p + j] == RC_MESH_NULL_IDX) break
                    // Skip connected edges.
                    if (mesh.polygons[p + nvp + j] != RC_MESH_NULL_IDX) continue
                    var nj = j + 1
                    if (nj >= nvp || mesh.polygons[p + nj] == RC_MESH_NULL_IDX) nj = 0
                    val va = mesh.polygons[p + j] * 3
                    val vb = mesh.polygons[p + nj] * 3
                    if (mesh.vertices[va] == 0 && mesh.vertices[vb] == 0) mesh.polygons[p + nvp + j] =
                        0x8000 else if (mesh.vertices[va + 2] == h && mesh.vertices[vb + 2] == h) mesh.polygons[p + nvp + j] =
                        0x8001 else if (mesh.vertices[va] == w && mesh.vertices[vb] == w) mesh.polygons[p + nvp + j] =
                        0x8002 else if (mesh.vertices[va + 2] == 0 && mesh.vertices[vb + 2] == 0) mesh.polygons[p + nvp + j] =
                        0x8003
                }
            }
        }

        // Just allocate the mesh flags array. The user is responsible to fill it.
        mesh.flags = IntArray(mesh.numPolygons)
        if (mesh.numVertices > MAX_MESH_VERTICES_POLY) {
            throw RuntimeException(
                "rcBuildPolyMesh: The resulting mesh has too many vertices " + mesh.numVertices
                        + " (max " + MAX_MESH_VERTICES_POLY + "). Data can be corrupted."
            )
        }
        if (mesh.numPolygons > MAX_MESH_VERTICES_POLY) {
            throw RuntimeException(
                "rcBuildPolyMesh: The resulting mesh has too many polygons " + mesh.numPolygons
                        + " (max " + MAX_MESH_VERTICES_POLY + "). Data can be corrupted."
            )
        }
        ctx?.stopTimer("POLYMESH")
        return mesh
    }

    /// @see rcAllocPolyMesh, rcPolyMesh
    fun mergePolyMeshes(ctx: Telemetry?, meshes: Array<PolyMesh>?, nmeshes: Int): PolyMesh? {
        if (nmeshes == 0 || meshes == null) return null
        ctx?.startTimer("MERGE_POLYMESH")
        val mesh = PolyMesh()
        mesh.maxVerticesPerPolygon = meshes[0].maxVerticesPerPolygon
        mesh.cellSize = meshes[0].cellSize
        mesh.cellHeight = meshes[0].cellHeight
        mesh.bmin.set(meshes[0].bmin)
        mesh.bmax.set(meshes[0].bmax)
        var maxVertices = 0
        var maxPolys = 0
        var maxVerticesPerMesh = 0
        for (i in 0 until nmeshes) {
            mesh.bmin.min(meshes[i].bmin, mesh.bmin)
            mesh.bmax.max(meshes[i].bmax, mesh.bmax)
            maxVerticesPerMesh = max(maxVerticesPerMesh, meshes[i].numVertices)
            maxVertices += meshes[i].numVertices
            maxPolys += meshes[i].numPolygons
        }
        mesh.numVertices = 0
        mesh.vertices = IntArray(maxVertices * 3)
        mesh.numPolygons = 0
        mesh.polygons = IntArray(maxPolys * 2 * mesh.maxVerticesPerPolygon)
        Arrays.fill(mesh.polygons, 0, mesh.polygons.size, RC_MESH_NULL_IDX)
        mesh.regionIds = IntArray(maxPolys)
        mesh.areaIds = IntArray(maxPolys)
        mesh.flags = IntArray(maxPolys)
        val nextVert = IntArray(maxVertices)
        val firstVert = IntArray(VERTEX_BUCKET_COUNT)
        for (i in 0 until VERTEX_BUCKET_COUNT) firstVert[i] = -1
        val vremap = IntArray(maxVerticesPerMesh)
        for (i in 0 until nmeshes) {
            val pmesh = meshes[i]
            val ox = floor(((pmesh.bmin.x - mesh.bmin.x) / mesh.cellSize + 0.5f)).toInt()
            val oz = floor(((pmesh.bmin.z - mesh.bmin.z) / mesh.cellSize + 0.5f)).toInt()
            val isMinX = ox == 0
            val isMinZ = oz == 0
            val isMaxX = floor(((mesh.bmax.x - pmesh.bmax.x) / mesh.cellSize + 0.5f)) == 0f
            val isMaxZ = floor(((mesh.bmax.z - pmesh.bmax.z) / mesh.cellSize + 0.5f)) == 0f
            val isOnBorder = isMinX || isMinZ || isMaxX || isMaxZ
            for (j in 0 until pmesh.numVertices) {
                val v = j * 3
                val inv = addVertex(
                    pmesh.vertices[v] + ox, pmesh.vertices[v + 1], pmesh.vertices[v + 2] + oz, mesh.vertices,
                    firstVert, nextVert, mesh.numVertices
                )
                vremap[j] = inv[0]
                mesh.numVertices = inv[1]
            }
            for (j in 0 until pmesh.numPolygons) {
                val tgt = mesh.numPolygons * 2 * mesh.maxVerticesPerPolygon
                val src = j * 2 * mesh.maxVerticesPerPolygon
                mesh.regionIds[mesh.numPolygons] = pmesh.regionIds[j]
                mesh.areaIds[mesh.numPolygons] = pmesh.areaIds[j]
                mesh.flags[mesh.numPolygons] = pmesh.flags[j]
                mesh.numPolygons++
                for (k in 0 until mesh.maxVerticesPerPolygon) {
                    if (pmesh.polygons[src + k] == RC_MESH_NULL_IDX) break
                    mesh.polygons[tgt + k] = vremap[pmesh.polygons[src + k]]
                }
                if (isOnBorder) {
                    for (k in mesh.maxVerticesPerPolygon until mesh.maxVerticesPerPolygon * 2) {
                        if (pmesh.polygons[src + k] and 0x8000 != 0 && pmesh.polygons[src + k] != 0xffff) {
                            val dir = pmesh.polygons[src + k] and 0xf
                            when (dir) {
                                0 -> if (isMinX) mesh.polygons[tgt + k] = pmesh.polygons[src + k]
                                1 -> if (isMaxZ) mesh.polygons[tgt + k] = pmesh.polygons[src + k]
                                2 -> if (isMaxX) mesh.polygons[tgt + k] = pmesh.polygons[src + k]
                                3 -> if (isMinZ) mesh.polygons[tgt + k] = pmesh.polygons[src + k]
                            }
                        }
                    }
                }
            }
        }

        // Calculate adjacency.
        buildMeshAdjacency(mesh.polygons, mesh.numPolygons, mesh.numVertices, mesh.maxVerticesPerPolygon)
        if (mesh.numVertices > MAX_MESH_VERTICES_POLY) {
            throw RuntimeException(
                "rcBuildPolyMesh: The resulting mesh has too many vertices " + mesh.numVertices
                        + " (max " + MAX_MESH_VERTICES_POLY + "). Data can be corrupted."
            )
        }
        if (mesh.numPolygons > MAX_MESH_VERTICES_POLY) {
            throw RuntimeException(
                "rcBuildPolyMesh: The resulting mesh has too many polygons " + mesh.numPolygons
                        + " (max " + MAX_MESH_VERTICES_POLY + "). Data can be corrupted."
            )
        }
        ctx?.stopTimer("MERGE_POLYMESH")
        return mesh
    }

    fun copyPolyMesh(src: PolyMesh): PolyMesh {
        val dst = PolyMesh()
        dst.numVertices = src.numVertices
        dst.numPolygons = src.numPolygons
        dst.numAllocatedPolygons = src.numPolygons
        dst.maxVerticesPerPolygon = src.maxVerticesPerPolygon
        dst.bmin.set(src.bmin)
        dst.bmax.set(src.bmax)
        dst.cellSize = src.cellSize
        dst.cellHeight = src.cellHeight
        dst.borderSize = src.borderSize
        dst.maxEdgeError = src.maxEdgeError
        dst.vertices = IntArray(src.numVertices * 3)
        System.arraycopy(src.vertices, 0, dst.vertices, 0, dst.vertices.size)
        dst.polygons = IntArray(src.numPolygons * 2 * src.maxVerticesPerPolygon)
        System.arraycopy(src.polygons, 0, dst.polygons, 0, dst.polygons.size)
        dst.regionIds = IntArray(src.numPolygons)
        System.arraycopy(src.regionIds, 0, dst.regionIds, 0, dst.regionIds.size)
        dst.areaIds = IntArray(src.numPolygons)
        System.arraycopy(src.areaIds, 0, dst.areaIds, 0, dst.areaIds.size)
        dst.flags = IntArray(src.numPolygons)
        System.arraycopy(src.flags, 0, dst.flags, 0, dst.flags.size)
        return dst
    }
}