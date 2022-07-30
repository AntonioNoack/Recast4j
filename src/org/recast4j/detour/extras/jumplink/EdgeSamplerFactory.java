package org.recast4j.detour.extras.jumplink;

import org.joml.Vector3f;

class EdgeSamplerFactory {

    EdgeSampler get(JumpLinkBuilderConfig acfg, JumpLinkType type, Edge edge) {
        switch (type) {
            case EDGE_JUMP:
                return initEdgeJumpSampler(acfg, edge);
            case EDGE_CLIMB_DOWN:
                return initClimbDownSampler(acfg, edge);
            case EDGE_JUMP_OVER:
            default:
                throw new IllegalArgumentException("Unsupported jump type " + type);
        }
    }


    private EdgeSampler initEdgeJumpSampler(JumpLinkBuilderConfig acfg, Edge edge) {

        EdgeSampler es = new EdgeSampler(edge, new JumpTrajectory(acfg.jumpHeight));
        es.start.height = acfg.agentClimb * 2;
        Vector3f offset = new Vector3f();
        trans2d(offset, es.az, es.ay, acfg.startDistance, -acfg.agentClimb);
        vadd(es.start.p, edge.sp, offset);
        vadd(es.start.q, edge.sq, offset);

        float dx = acfg.endDistance - 2 * acfg.agentRadius;
        float cs = acfg.cellSize;
        int nsamples = Math.max(2, (int) Math.ceil(dx / cs));

        for (int j = 0; j < nsamples; ++j) {
            float v = (float) j / (float) (nsamples - 1);
            float ox = 2 * acfg.agentRadius + dx * v;
            trans2d(offset, es.az, es.ay, ox, acfg.minHeight);
            GroundSegment end = new GroundSegment();
            end.height = acfg.heightRange;
            vadd(end.p, edge.sp, offset);
            vadd(end.q, edge.sq, offset);
            es.end.add(end);
        }
        return es;
    }

    private EdgeSampler initClimbDownSampler(JumpLinkBuilderConfig acfg, Edge edge) {
        EdgeSampler es = new EdgeSampler(edge, new ClimbTrajectory());
        es.start.height = acfg.agentClimb * 2;
        Vector3f offset = new Vector3f();
        trans2d(offset, es.az, es.ay, acfg.startDistance, -acfg.agentClimb);
        vadd(es.start.p, edge.sp, offset);
        vadd(es.start.q, edge.sq, offset);

        trans2d(offset, es.az, es.ay, acfg.endDistance, acfg.minHeight);
        GroundSegment end = new GroundSegment();
        end.height = acfg.heightRange;
        vadd(end.p, edge.sp, offset);
        vadd(end.q, edge.sq, offset);
        es.end.add(end);
        return es;
    }

    private void vadd(Vector3f dest, Vector3f v1, Vector3f v2) {
        dest.set(v1).add(v2);
    }

    private void trans2d(Vector3f dst, Vector3f ax, Vector3f ay, float pt0, float pt1) {
        dst.x = ax.x * pt0 + ay.x * pt1;
        dst.y = ax.y * pt0 + ay.y * pt1;
        dst.z = ax.z * pt0 + ay.z * pt1;
    }

}