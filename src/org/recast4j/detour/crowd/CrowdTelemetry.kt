/*
recast4j copyright (c) 2021 Piotr Piastucki piotr@jtilia.org

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
package org.recast4j.detour.crowd

import org.recast4j.LongArrayList
import kotlin.math.max

class CrowdTelemetry {
    var maxTimeToEnqueueRequest = 0f
    var maxTimeToFindPath = 0f
    val executionTimings: MutableMap<String, Long> = HashMap()
    private val executionTimingSamples: MutableMap<String, LongArrayList> = HashMap()
    fun start() {
        maxTimeToEnqueueRequest = 0f
        maxTimeToFindPath = 0f
        executionTimings.clear()
    }

    fun recordMaxTimeToEnqueueRequest(time: Float) {
        maxTimeToEnqueueRequest = max(maxTimeToEnqueueRequest, time)
    }

    fun recordMaxTimeToFindPath(time: Float) {
        maxTimeToFindPath = max(maxTimeToFindPath, time)
    }

    fun start(name: String) {
        executionTimings[name] = System.nanoTime()
    }

    fun stop(name: String) {
        val duration = System.nanoTime() - executionTimings[name]!!
        val s = executionTimingSamples.computeIfAbsent(name) { LongArrayList() }
        if (s.size == TIMING_SAMPLES) {
            s.removeAt(0)
        }
        s.add(duration)
        var sum = 0L
        for (i in 0 until s.size) {
            sum += s[i]
        }
        executionTimings[name] = sum / max(s.size, 1)
    }

    companion object {
        private const val TIMING_SAMPLES = 10
    }
}