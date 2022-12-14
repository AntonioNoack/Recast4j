package org.recast4j.detour.extras.jumplink;

import org.joml.Vector3f;
import org.recast4j.Vectors;
import org.recast4j.recast.RecastBuilder.RecastBuilderResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.stream.Collectors.toList;
import static org.recast4j.Vectors.copy;

public class JumpLinkBuilder {

    private final EdgeExtractor edgeExtractor = new EdgeExtractor();
    private final EdgeSamplerFactory edgeSamplerFactory = new EdgeSamplerFactory();
    private final GroundSampler groundSampler = new NavMeshGroundSampler();
    private final TrajectorySampler trajectorySampler = new TrajectorySampler();
    private final JumpSegmentBuilder jumpSegmentBuilder = new JumpSegmentBuilder();

    public final List<Edge[]> edges;
    private final List<RecastBuilderResult> results;

    @SuppressWarnings("unused")
    public JumpLinkBuilder(List<RecastBuilderResult> results) {
        this.results = results;
        edges = results.stream().map(r -> edgeExtractor.extractEdges(r.mesh)).collect(toList());
    }

    public List<JumpLink> build(JumpLinkBuilderConfig cfg, JumpLinkType type) {
        List<JumpLink> links = new ArrayList<>();
        for (int tile = 0; tile < results.size(); tile++) {
            Edge[] edges = this.edges.get(tile);
            for (Edge edge : edges) {
                links.addAll(processEdge(cfg, results.get(tile), type, edge));
            }
        }
        return links;
    }

    private List<JumpLink> processEdge(JumpLinkBuilderConfig cfg, RecastBuilderResult result, JumpLinkType type, Edge edge) {
        EdgeSampler es = edgeSamplerFactory.get(cfg, type, edge);
        groundSampler.sample(cfg, result, es);
        trajectorySampler.sample(cfg, result.solidHeightField, es);
        JumpSegment[] jumpSegments = jumpSegmentBuilder.build(cfg, es);
        return buildJumpLinks(cfg, es, jumpSegments);
    }


    private List<JumpLink> buildJumpLinks(JumpLinkBuilderConfig acfg, EdgeSampler es, JumpSegment[] jumpSegments) {
        List<JumpLink> links = new ArrayList<>();
        for (JumpSegment js : jumpSegments) {
            Vector3f sp = es.start.samples[js.startSample].p;
            Vector3f sq = es.start.samples[js.startSample + js.samples - 1].p;
            GroundSegment end = es.end.get(js.groundSegment);
            Vector3f ep = end.samples[js.startSample].p;
            Vector3f eq = end.samples[js.startSample + js.samples - 1].p;
            float d = Math.min(Vectors.dist2DSqr(sp, sq), Vectors.dist2DSqr(ep, eq));
            if (d >= 4 * acfg.agentRadius * acfg.agentRadius) {
                JumpLink link = new JumpLink();
                links.add(link);
                link.startSamples = Arrays.copyOfRange(es.start.samples, js.startSample, js.startSample + js.samples);
                link.endSamples = Arrays.copyOfRange(end.samples, js.startSample, js.startSample + js.samples);
                link.start = es.start;
                link.end = end;
                link.trajectory = es.trajectory;
                for (int j = 0; j < link.numSpines; ++j) {
                    float u = ((float) j) / (link.numSpines - 1);
                    copy(link.spine0, j * 3, es.trajectory.apply(sp, ep, u));
                    copy(link.spine1, j * 3, es.trajectory.apply(sq, eq, u));
                }
            }
        }
        return links;
    }

}
