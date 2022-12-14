package org.recast4j.detour.extras.jumplink;

import kotlin.Pair;
import org.joml.Vector3f;
import org.recast4j.Vectors;

import java.util.function.BiFunction;

abstract class AbstractGroundSampler implements GroundSampler {

    protected void sampleGround(
            JumpLinkBuilderConfig cfg, EdgeSampler es,
            BiFunction<Vector3f, Float, Pair<Boolean, Float>> heightFunc
    ) {
        float cellSize = cfg.cellSize;
        float dist = es.start.p.distance(es.start.q);
        int numSamples = Math.max(2, (int) Math.ceil(dist / cellSize));
        sampleGroundSegment(heightFunc, es.start, numSamples);
        for (GroundSegment end : es.end) {
            sampleGroundSegment(heightFunc, end, numSamples);
        }
    }

    protected void sampleGroundSegment(BiFunction<Vector3f, Float, Pair<Boolean, Float>> heightFunc, GroundSegment seg, int numSamples) {
        seg.samples = new GroundSample[numSamples];
        for (int i = 0; i < numSamples; ++i) {
            float u = i / (float) (numSamples - 1);
            GroundSample s = seg.samples[i] = new GroundSample();
            Vector3f pt = Vectors.lerp(seg.p, seg.q, u);
            Pair<Boolean, Float> height = heightFunc.apply(pt, seg.height);
            s.p.set(pt);
            s.p.y = height.getSecond();
            if (!height.getFirst()) continue;
            s.validHeight = true;
        }
    }

}
