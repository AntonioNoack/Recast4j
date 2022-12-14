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
import org.recast4j.detour.NavMeshQuery;
import org.recast4j.detour.StraightPathItem;
import org.recast4j.detour.crowd.Crowd.CrowdNeighbour;

import java.util.ArrayList;
import java.util.List;

import static org.recast4j.Vectors.*;

/**
 * Represents an agent managed by a #dtCrowd object.
 */
public class CrowdAgent {

    /**
     * The type of navigation mesh polygon the agent is currently traversing.
     */
    public enum CrowdAgentState {
        /** The agent is not in a valid state. */
        INVALID,
        /** The agent is traversing a normal navigation mesh polygon. */
        WALKING,
        /** The agent is traversing an off-mesh connection. */
        OFFMESH,
    }

    public enum MoveRequestState {
        NONE,
        FAILED,
        VALID,
        REQUESTING,
        WAITING_FOR_QUEUE,
        WAITING_FOR_PATH,
        VELOCITY,
    }

    public final long idx;
    /** The type of mesh polygon the agent is traversing. (See: #CrowdAgentState) */
    public CrowdAgentState state;
    /**
     * True if the agent has valid path (targetState == CROWDAGENT_TARGET_VALID), and the path does not lead to the
     * requested position, else false.
     * */
    boolean partial;
    /** The path corridor the agent is using. */
    public PathCorridor corridor;
    /** The local boundary data for the agent. */
    public LocalBoundary boundary;
    /** Time since the agent's path corridor was optimized. */
    float topologyOptTime;
    /** The known neighbors of the agent. */
    public List<CrowdNeighbour> neis = new ArrayList<>();
    /** The desired speed. */
    float desiredSpeed;

    public Vector3f currentPosition = new Vector3f();
    
    /** A temporary value used to accumulate agent displacement during iterative collision resolution */
    Vector3f disp = new Vector3f();
    
    /** The desired velocity of the agent. Based on the current path, calculated from scratch each frame. */
    public Vector3f desiredVelocity = new Vector3f();
    
    /** The desired velocity adjusted by obstacle avoidance, calculated from scratch each frame */
    Vector3f desiredVelAdjusted = new Vector3f();
    /** The actual velocity of the agent. The change from nvel -> vel is constrained by max acceleration */
    public Vector3f actualVelocity = new Vector3f();

    /** The agent's configuration parameters. */
    public CrowdAgentParams params;
    /** The local path corridor corners for the agent. */
    public List<StraightPathItem> corners = new ArrayList<>();

    /** State of the movement request. */
    public MoveRequestState targetState;
    /** Target polyref of the movement request. */
    public long targetRef;
    /** Target position of the movement request (or velocity in case of CROWDAGENT_TARGET_VELOCITY). */
    public Vector3f targetPos = new Vector3f();
    /** Pathfinder query */
    PathQueryResult targetPathQueryResult;
    /** Flag indicating that the current path is being replanned. */
    boolean targetReplan;
    /** Time since the agent's target was replanned. */
    float targetReplanTime;
    float targetReplanWaitTime;

    public CrowdAgentAnimation animation;

    public CrowdAgent(int idx) {
        this.idx = idx;
        corridor = new PathCorridor();
        boundary = new LocalBoundary();
        animation = new CrowdAgentAnimation();
    }

    void integrate(float dt) {
        // Fake dynamic constraint.
        float maxDelta = params.maxAcceleration * dt;
        Vector3f dv = sub(desiredVelAdjusted, actualVelocity);
        float ds = dv.length();
        if (ds > maxDelta)
            dv.mul(maxDelta / ds);
        actualVelocity.add(dv);

        // Integrate
        if (actualVelocity.length() > 0.0001f)
            mad2(currentPosition, actualVelocity, dt);
        else actualVelocity.set(0f);
    }

    boolean overOffmeshConnection(float radius) {
        if (corners.isEmpty()) return false;

        boolean offMeshConnection = (corners.get(corners.size() - 1).flags & NavMeshQuery.DT_STRAIGHTPATH_OFFMESH_CONNECTION) != 0;
        if (offMeshConnection) {
            float distSq = dist2DSqr(currentPosition, corners.get(corners.size() - 1).pos);
            return distSq < radius * radius;
        }
        return false;
    }

    float getDistanceToGoal(float range) {
        if (corners.isEmpty()) return range;
        boolean endOfPath = (corners.get(corners.size() - 1).flags & NavMeshQuery.DT_STRAIGHTPATH_END) != 0;
        if (endOfPath) return Math.min(dist2D(currentPosition, corners.get(corners.size() - 1).pos), range);
        return range;
    }

    public Vector3f calcSmoothSteerDirection(Vector3f dst) {
        if (!corners.isEmpty()) {

            int ip0 = 0;
            int ip1 = Math.min(1, corners.size() - 1);
            Vector3f p0 = corners.get(ip0).pos;
            Vector3f p1 = corners.get(ip1).pos;

            Vector3f dir0 = sub(p0, currentPosition);
            Vector3f dir1 = sub(p1, currentPosition);
            dir0.y = 0;
            dir1.y = 0;

            float len0 = dir0.length();
            float len1 = dir1.length();
            if (len1 > 0.001f)
                dir1.mul(1f / len1);

            dst.x = dir0.x - dir1.x * len0 * 0.5f;
            dst.y = 0;
            dst.z = dir0.z - dir1.z * len0 * 0.5f;

            dst.normalize();
        }
        return dst;
    }

    public Vector3f calcStraightSteerDirection(Vector3f dst) {
        if (!corners.isEmpty()) {
            dst.set(corners.get(0).pos).sub(currentPosition);
            dst.y = 0;
            dst.normalize();
        }
        return dst;
    }

    void setTarget(long ref, Vector3f pos) {
        targetRef = ref;
        copy(targetPos, pos);
        targetPathQueryResult = null;
        targetState = targetRef != 0 ? MoveRequestState.REQUESTING : MoveRequestState.FAILED;
    }

}