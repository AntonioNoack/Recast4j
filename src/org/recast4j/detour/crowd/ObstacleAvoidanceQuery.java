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

import org.joml.Vector3f;
import org.recast4j.Pair;
import org.recast4j.detour.crowd.debug.ObstacleAvoidanceDebugData;

import static org.recast4j.detour.DetourCommon.*;

public class ObstacleAvoidanceQuery {

    private static final int DT_MAX_PATTERN_DIVS = 32; /// < Max numver of adaptive divs.
    private static final int DT_MAX_PATTERN_RINGS = 4; /// < Max number of adaptive rings.

    static class ObstacleCircle {
        /**
         * Position of the obstacle
         */
        final Vector3f p = new Vector3f();
        /**
         * Velocity of the obstacle
         */
        final Vector3f vel = new Vector3f();
        /**
         * Velocity of the obstacle
         */
        final Vector3f dvel = new Vector3f();
        /**
         * Radius of the obstacle
         */
        float rad;
        /**
         * Use for side selection during sampling.
         */
        final Vector3f dp = new Vector3f();
        /**
         * Use for side selection during sampling.
         */
        final Vector3f np = new Vector3f();
    }

    static class ObstacleSegment {
        /**
         * End points of the obstacle segment
         */
        final Vector3f p = new Vector3f();
        /**
         * End points of the obstacle segment
         */
        final Vector3f q = new Vector3f();
        boolean touch;
    }

    public static class ObstacleAvoidanceParams {
        public float velBias;
        public float weightDesVel;
        public float weightCurVel;
        public float weightSide;
        public float weightToi;
        public float horizTime;
        public int gridSize; /// < grid
        public int adaptiveDivs; /// < adaptive
        public int adaptiveRings; /// < adaptive
        public int adaptiveDepth; /// < adaptive

        public ObstacleAvoidanceParams() {
            velBias = 0.4f;
            weightDesVel = 2.0f;
            weightCurVel = 0.75f;
            weightSide = 0.75f;
            weightToi = 2.5f;
            horizTime = 2.5f;
            gridSize = 33;
            adaptiveDivs = 7;
            adaptiveRings = 2;
            adaptiveDepth = 5;
        }

        public ObstacleAvoidanceParams(ObstacleAvoidanceParams params) {
            velBias = params.velBias;
            weightDesVel = params.weightDesVel;
            weightCurVel = params.weightCurVel;
            weightSide = params.weightSide;
            weightToi = params.weightToi;
            horizTime = params.horizTime;
            gridSize = params.gridSize;
            adaptiveDivs = params.adaptiveDivs;
            adaptiveRings = params.adaptiveRings;
            adaptiveDepth = params.adaptiveDepth;
        }
    }

    ;

    private ObstacleAvoidanceParams m_params;
    private float m_invHorizTime;
    private float m_vmax;
    private float m_invVmax;

    private final int m_maxCircles;
    private final ObstacleCircle[] m_circles;
    private int m_ncircles;

    private final int m_maxSegments;
    private final ObstacleSegment[] m_segments;
    private int m_nsegments;

    public ObstacleAvoidanceQuery(int maxCircles, int maxSegments) {
        m_maxCircles = maxCircles;
        m_ncircles = 0;
        m_circles = new ObstacleCircle[m_maxCircles];
        for (int i = 0; i < m_maxCircles; i++) {
            m_circles[i] = new ObstacleCircle();
        }
        m_maxSegments = maxSegments;
        m_nsegments = 0;
        m_segments = new ObstacleSegment[m_maxSegments];
        for (int i = 0; i < m_maxSegments; i++) {
            m_segments[i] = new ObstacleSegment();
        }
    }

    public void reset() {
        m_ncircles = 0;
        m_nsegments = 0;
    }

    public void addCircle(Vector3f pos, float rad, Vector3f vel, Vector3f dvel) {
        if (m_ncircles >= m_maxCircles)
            return;

        ObstacleCircle cir = m_circles[m_ncircles++];
        copy(cir.p, pos);
        cir.rad = rad;
        copy(cir.vel, vel);
        copy(cir.dvel, dvel);
    }

    public void addSegment(Vector3f p, Vector3f q) {
        if (m_nsegments >= m_maxSegments)
            return;
        ObstacleSegment seg = m_segments[m_nsegments++];
        copy(seg.p, p);
        copy(seg.q, q);
    }

    public int getObstacleCircleCount() {
        return m_ncircles;
    }

    public ObstacleCircle getObstacleCircle(int i) {
        return m_circles[i];
    }

    public int getObstacleSegmentCount() {
        return m_nsegments;
    }

    public ObstacleSegment getObstacleSegment(int i) {
        return m_segments[i];
    }

    private void prepare(Vector3f pos, Vector3f dvel) {
        // Prepare obstacles
        for (int i = 0; i < m_ncircles; i++) {
            ObstacleCircle cir = m_circles[i];

            // Side
            Vector3f pa = pos;
            Vector3f pb = cir.p;

            Vector3f orig = new Vector3f();
            Vector3f dv;
            copy(cir.dp, vSub(pb, pa));
            cir.dp.normalize();
            dv = vSub(cir.dvel, dvel);

            float a = triArea2D(orig, cir.dp, dv);
            if (a < 0.01f) {
                cir.np.x = -cir.dp.z;
                cir.np.z = cir.dp.x;
            } else {
                cir.np.x = cir.dp.z;
                cir.np.z = -cir.dp.x;
            }
        }

        for (int i = 0; i < m_nsegments; ++i) {
            ObstacleSegment seg = m_segments[i];

            // Precalc if the agent is really close to the segment.
            float r = 0.01f;
            Pair<Float, Float> dt = distancePtSegSqr2D(pos, seg.p, seg.q);
            seg.touch = dt.first < sqr(r);
        }
    }

    SweepCircleCircleResult sweepCircleCircle(Vector3f c0, float r0, Vector3f v, Vector3f c1, float r1) {
        final float EPS = 0.0001f;
        Vector3f s = vSub(c1, c0);
        float r = r0 + r1;
        float c = vDot2D(s, s) - r * r;
        float a = vDot2D(v, v);
        if (a < EPS)
            return new SweepCircleCircleResult(false, 0f, 0f); // not moving

        // Overlap, calc time to exit.
        float b = vDot2D(v, s);
        float d = b * b - a * c;
        if (d < 0.0f)
            return new SweepCircleCircleResult(false, 0f, 0f); // no intersection.
        a = 1.0f / a;
        float rd = (float) Math.sqrt(d);
        return new SweepCircleCircleResult(true, (b - rd) * a, (b + rd) * a);
    }

    Pair<Boolean, Float> isectRaySeg(Vector3f ap, Vector3f u, Vector3f bp, Vector3f bq) {
        Vector3f v = vSub(bq, bp);
        Vector3f w = vSub(ap, bp);
        float d = vPerp2D(u, v);
        if (Math.abs(d) < 1e-6f)
            return new Pair<>(false, 0f);
        d = 1.0f / d;
        float t = vPerp2D(v, w) * d;
        if (t < 0 || t > 1)
            return new Pair<>(false, 0f);
        float s = vPerp2D(u, w) * d;
        if (s < 0 || s > 1)
            return new Pair<>(false, 0f);
        return new Pair<>(true, t);
    }

    /**
     * Calculate the collision penalty for a given velocity vector
     *
     * @param vcand      sampled velocity
     * @param dvel       desired velocity
     * @param minPenalty threshold penalty for early out
     */
    private float processSample(Vector3f vcand, float cs, Vector3f pos, float rad, Vector3f vel, Vector3f dvel,
                                float minPenalty, ObstacleAvoidanceDebugData debug) {
        // penalty for straying away from the desired and current velocities
        float vpen = m_params.weightDesVel * (vDist2D(vcand, dvel) * m_invVmax);
        float vcpen = m_params.weightCurVel * (vDist2D(vcand, vel) * m_invVmax);

        // find the threshold hit time to bail out based on the early out penalty
        // (see how the penalty is calculated below to understnad)
        float minPen = minPenalty - vpen - vcpen;
        float tThresold = (m_params.weightToi / minPen - 0.1f) * m_params.horizTime;
        if (tThresold - m_params.horizTime > -Float.MIN_VALUE)
            return minPenalty; // already too much

        // Find min time of impact and exit amongst all obstacles.
        float tmin = m_params.horizTime;
        float side = 0;
        int nside = 0;

        for (int i = 0; i < m_ncircles; ++i) {
            ObstacleCircle cir = m_circles[i];

            // RVO
            Vector3f vab = new Vector3f(vcand).mul(2f);
            vab = vSub(vab, vel);
            vab = vSub(vab, cir.vel);

            // Side
            side += clamp(Math.min(vDot2D(cir.dp, vab) * 0.5f + 0.5f, vDot2D(cir.np, vab) * 2), 0.0f, 1.0f);
            nside++;

            SweepCircleCircleResult sres = sweepCircleCircle(pos, rad, vab, cir.p, cir.rad);
            if (!sres.intersection)
                continue;
            float htmin = sres.htmin, htmax = sres.htmax;

            // Handle overlapping obstacles.
            if (htmin < 0.0f && htmax > 0.0f) {
                // Avoid more when overlapped.
                htmin = -htmin * 0.5f;
            }

            if (htmin >= 0.0f) {
                // The closest obstacle is somewhere ahead of us, keep track of nearest obstacle.
                if (htmin < tmin) {
                    tmin = htmin;
                    if (tmin < tThresold)
                        return minPenalty;
                }
            }
        }

        for (int i = 0; i < m_nsegments; ++i) {
            ObstacleSegment seg = m_segments[i];
            float htmin = 0;

            if (seg.touch) {
                // Special case when the agent is very close to the segment.
                Vector3f sdir = vSub(seg.q, seg.p);
                Vector3f snorm = new Vector3f();
                snorm.x = -sdir.z;
                snorm.z = sdir.x;
                // If the velocity is pointing towards the segment, no collision.
                if (vDot2D(snorm, vcand) < 0.0f)
                    continue;
                // Else immediate collision.
                htmin = 0.0f;
            } else {
                Pair<Boolean, Float> ires = isectRaySeg(pos, vcand, seg.p, seg.q);
                if (!ires.first)
                    continue;
                htmin = ires.second;
            }

            // Avoid less when facing walls.
            htmin *= 2.0f;

            // The closest obstacle is somewhere ahead of us, keep track of nearest obstacle.
            if (htmin < tmin) {
                tmin = htmin;
                if (tmin < tThresold)
                    return minPenalty;
            }
        }

        // Normalize side bias, to prevent it dominating too much.
        if (nside != 0)
            side /= nside;

        float spen = m_params.weightSide * side;
        float tpen = m_params.weightToi * (1.0f / (0.1f + tmin * m_invHorizTime));

        float penalty = vpen + vcpen + spen + tpen;
        // Store different penalties for debug viewing
        if (debug != null)
            debug.addSample(vcand, cs, penalty, vpen, vcpen, spen, tpen);

        return penalty;
    }

    public Pair<Integer, Vector3f> sampleVelocityGrid(Vector3f pos, float rad, float vmax, Vector3f vel, Vector3f dvel,
                                                      ObstacleAvoidanceParams params, ObstacleAvoidanceDebugData debug) {
        prepare(pos, dvel);
        m_params = params;
        m_invHorizTime = 1.0f / m_params.horizTime;
        m_vmax = vmax;
        m_invVmax = vmax > 0 ? 1.0f / vmax : Float.MAX_VALUE;

        Vector3f nvel = new Vector3f();
        vSet(nvel, 0f, 0f, 0f);

        if (debug != null)
            debug.reset();

        float cvx = dvel.x * m_params.velBias;
        float cvz = dvel.z * m_params.velBias;
        float cs = vmax * 2 * (1 - m_params.velBias) / (m_params.gridSize - 1);
        float half = (m_params.gridSize - 1) * cs * 0.5f;

        float minPenalty = Float.MAX_VALUE;
        int ns = 0;

        for (int y = 0; y < m_params.gridSize; ++y) {
            for (int x = 0; x < m_params.gridSize; ++x) {
                Vector3f vcand = new Vector3f(cvx + x * cs - half, 0f, cvz + y * cs - half);
                if (sqr(vcand.x) + sqr(vcand.z) > sqr(vmax + cs / 2))
                    continue;

                float penalty = processSample(vcand, cs, pos, rad, vel, dvel, minPenalty, debug);
                ns++;
                if (penalty < minPenalty) {
                    minPenalty = penalty;
                    copy(nvel, vcand);
                }
            }
        }

        return new Pair<>(ns, nvel);
    }

    // vector normalization that ignores the y-component.
    void dtNormalize2D(float[] v) {
        float d = (float) Math.sqrt(v[0] * v[0] + v[2] * v[2]);
        if (d == 0)
            return;
        d = 1.0f / d;
        v[0] *= d;
        v[2] *= d;
    }

    static final float DT_PI = 3.14159265f;

    public Pair<Integer, Vector3f> sampleVelocityAdaptive(Vector3f pos, float rad, float vmax, Vector3f vel,
                                                          Vector3f dvel, ObstacleAvoidanceParams params, ObstacleAvoidanceDebugData debug) {
        prepare(pos, dvel);
        m_params = params;
        m_invHorizTime = 1.0f / m_params.horizTime;
        m_vmax = vmax;
        m_invVmax = vmax > 0 ? 1.0f / vmax : Float.MAX_VALUE;

        Vector3f nvel = new Vector3f();

        if (debug != null)
            debug.reset();

        // Build sampling pattern aligned to desired velocity.
        float[] pat = new float[(DT_MAX_PATTERN_DIVS * DT_MAX_PATTERN_RINGS + 1) * 2];

        int ndivs = m_params.adaptiveDivs;
        int nrings = m_params.adaptiveRings;
        int depth = m_params.adaptiveDepth;

        int nd = clamp(ndivs, 1, DT_MAX_PATTERN_DIVS);
        int nr = clamp(nrings, 1, DT_MAX_PATTERN_RINGS);
        float da = (1.0f / nd) * DT_PI * 2;
        float ca = (float) Math.cos(da);
        float sa = (float) Math.sin(da);

        // desired direction
        float[] ddir = {dvel.x, dvel.y, dvel.z, 0f, 0f, 0f};
        dtNormalize2D(ddir);
        Vector3f rotated = new Vector3f(ddir).rotateY(da * 0.5f); // rotated by da/2
        ddir[3] = rotated.x;
        ddir[4] = rotated.y;
        ddir[5] = rotated.z;

        int npat = 1;
        for (int j = 0; j < nr; ++j) {
            float r = (float) (nr - j) / (float) nr;
            pat[npat * 2] = ddir[(j % 2) * 3] * r;
            pat[npat * 2 + 1] = ddir[(j % 2) * 3 + 2] * r;
            int last1 = npat * 2;
            int last2 = last1;
            npat++;

            for (int i = 1; i < nd - 1; i += 2) {
                // get next point on the "right" (rotate CW)
                pat[npat * 2] = pat[last1] * ca + pat[last1 + 1] * sa;
                pat[npat * 2 + 1] = -pat[last1] * sa + pat[last1 + 1] * ca;
                // get next point on the "left" (rotate CCW)
                pat[npat * 2 + 2] = pat[last2] * ca - pat[last2 + 1] * sa;
                pat[npat * 2 + 3] = pat[last2] * sa + pat[last2 + 1] * ca;

                last1 = npat * 2;
                last2 = last1 + 2;
                npat += 2;
            }

            if ((nd & 1) == 0) {
                pat[npat * 2 + 2] = pat[last2] * ca - pat[last2 + 1] * sa;
                pat[npat * 2 + 3] = pat[last2] * sa + pat[last2 + 1] * ca;
                npat++;
            }
        }

        // Start sampling.
        float cr = vmax * (1.0f - m_params.velBias);
        Vector3f res = new Vector3f(dvel.x * m_params.velBias, 0, dvel.z * m_params.velBias);
        int ns = 0;
        for (int k = 0; k < depth; ++k) {
            float minPenalty = Float.MAX_VALUE;
            Vector3f bvel = new Vector3f();
            for (int i = 0; i < npat; ++i) {
                Vector3f vcand = new Vector3f(res.x + pat[i * 2] * cr, 0f, res.z + pat[i * 2 + 1] * cr);
                if (sqr(vcand.x) + sqr(vcand.z) > sqr(vmax + 0.001f))
                    continue;

                float penalty = processSample(vcand, cr / 10, pos, rad, vel, dvel, minPenalty, debug);
                ns++;
                if (penalty < minPenalty) {
                    minPenalty = penalty;
                    bvel.set(vcand);
                }
            }

            res.set(bvel);

            cr *= 0.5f;
        }
        nvel.set(res);

        return new Pair<>(ns, nvel);
    }
}
