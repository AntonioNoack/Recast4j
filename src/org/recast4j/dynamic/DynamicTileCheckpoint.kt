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
package org.recast4j.dynamic

import org.joml.Vector3f
import org.recast4j.Vectors
import org.recast4j.recast.Heightfield
import org.recast4j.recast.Span

class DynamicTileCheckpoint(heightfield: Heightfield, val colliders: Set<Long>) {

    val heightfield = clone(heightfield)

    private fun clone(source: Heightfield): Heightfield {
        val clone = Heightfield(
            source.width, source.height,
            Vector3f(source.bmin),
            Vector3f(source.bmax),
            source.cellSize,
            source.cellHeight, source.borderSize
        )
        var z = 0
        var pz = 0
        while (z < source.height) {
            for (x in 0 until source.width) {
                var span = source.spans[pz + x]
                var prevCopy: Span? = null
                while (span != null) {
                    val copy = Span()
                    copy.min = span.min
                    copy.max = span.max
                    copy.area = span.area
                    if (prevCopy == null) {
                        clone.spans[pz + x] = copy
                    } else {
                        prevCopy.next = copy
                    }
                    prevCopy = copy
                    span = span.next
                }
            }
            z++
            pz += source.width
        }
        return clone
    }
}