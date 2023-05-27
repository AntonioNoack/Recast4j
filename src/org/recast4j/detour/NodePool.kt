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
package org.recast4j.detour

class NodePool {

    companion object {
        private val nodeCache = ArrayList<Node>()
        private val listCache = ArrayList<ArrayList<Node>>()
        private fun createList(): ArrayList<Node> {
            return synchronized(listCache) {
                listCache.removeLastOrNull() ?: ArrayList()
            }
        }

        private fun createNode(): Node {
            return synchronized(nodeCache) {
                nodeCache.removeLastOrNull() ?: Node()
            }
        }
    }

    private val nodeMap = HashMap<Long, ArrayList<Node>>(64)
    private val nodeList = ArrayList<Node>()

    fun clear() {
        nodeList.clear()
        synchronized(listCache) {
            for ((_, v) in nodeMap) {
                if (nodeCache.size < 512) nodeCache.addAll(v)
                if (listCache.size < 512) listCache.add(v)
                v.clear()
            }
        }
        nodeMap.clear()
    }

    fun findNodes(id: Long): List<Node> {
        var nodes: List<Node>? = nodeMap[id]
        if (nodes == null) {
            nodes = ArrayList()
        }
        return nodes
    }

    fun findNode(id: Long): Node? {
        val nodes: List<Node>? = nodeMap[id]
        return if (nodes != null && nodes.isNotEmpty()) {
            nodes[0]
        } else null
    }

    fun getNode(id: Long, state: Int): Node {
        var nodes = nodeMap[id]
        if (nodes != null) {
            for (node in nodes) {
                if (node.state == state) {
                    return node
                }
            }
        }
        val node = createNode()
        node.index = nodeList.size + 1
        node.polygonRef = id
        node.state = state
        node.cost = 0f
        node.flags = 0
        node.shortcut = null
        node.parentIndex = 0
        node.pos.set(0f)
        nodeList.add(node)
        nodes = nodeMap.computeIfAbsent(id) { createList() }
        nodes.add(node)
        return node
    }

    fun getNodeIdx(node: Node?): Int {
        return node?.index ?: 0
    }

    fun getNodeAtIdx(idx: Int): Node? {
        return if (idx != 0) nodeList[idx - 1] else null
    }

    fun getNode(ref: Long): Node {
        return getNode(ref, 0)
    }
}