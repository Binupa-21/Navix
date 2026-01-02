package com.navix.app

import java.util.PriorityQueue
import kotlin.math.pow
import kotlin.math.sqrt

class PathFinder {

    // Helper class to store A* scores without messing up your main Node data
    data class PathNode(
        val node: Node,
        var gCost: Float = Float.MAX_VALUE, // Distance from start
        var hCost: Float = 0f,              // Estimated distance to end
        var parent: PathNode? = null        // The previous node (bread crumbs)
    ) {
        val fCost: Float
            get() = gCost + hCost
    }

    // THE MAIN ALGORITHM
    fun findPath(allNodes: List<Node>, startId: String, targetId: String, isWheelchair: Boolean = false): List<Node> {

        // 1. Find the Start and Target objects in your list
        val startNodeData = allNodes.find { it.id == startId } ?: return emptyList()
        val targetNodeData = allNodes.find { it.id == targetId } ?: return emptyList()

        // 2. Wrap all Nodes into "PathNodes" to track costs
        val pathNodeMap = allNodes.associate { it.id to PathNode(it) }

        val startNode = pathNodeMap[startId]!!
        val targetNode = pathNodeMap[targetId]!!

        // 3. Setup the Open Set (Nodes to be checked) and Closed Set (Nodes already checked)
        val openSet = PriorityQueue<PathNode> { a, b -> a.fCost.compareTo(b.fCost) }
        val closedSet = HashSet<PathNode>()

        // Initialize Start
        startNode.gCost = 0f
        startNode.hCost = calculateDistance(startNode.node, targetNode.node)
        openSet.add(startNode)

        // 4. Loop until we find the target
        while (openSet.isNotEmpty()) {
            val current = openSet.poll()

            // FOUND IT! Retrace steps
            if (current.node.id == targetId) {
                return reconstructPath(current)
            }

            closedSet.add(current)

            // Check all neighbors
            // Corrected from .neighbors to .neighborIds
            for (neighborId in current.node.neighborIds) {
                val neighbor = pathNodeMap[neighborId] ?: continue // Skip if neighbor missing

                if (closedSet.contains(neighbor)) continue // Skip if already checked

                // Calculate cost to move to this neighbor
                val newMovementCost = current.gCost + calculateDistance(current.node, neighbor.node)

                // If this path is shorter than the previous best path to this neighbor
                if (newMovementCost < neighbor.gCost || !openSet.contains(neighbor)) {
                    neighbor.gCost = newMovementCost
                    neighbor.hCost = calculateDistance(neighbor.node, targetNode.node)
                    neighbor.parent = current // Mark where we came from

                    if (!openSet.contains(neighbor)) {
                        openSet.add(neighbor)
                    }
                }
            }
        }

        return emptyList() // No path found
    }

    // MATH: Calculate 3D distance between two nodes
    private fun calculateDistance(a: Node, b: Node): Float {
        return sqrt(
            (a.x - b.x).pow(2) +
                    (a.y - b.y).pow(2) +
                    (a.z - b.z).pow(2)
        )
    }

    // Helper: Walk backwards from End -> Start to build the final list
    private fun reconstructPath(endNode: PathNode): List<Node> {
        val path = ArrayList<Node>()
        var current: PathNode? = endNode
        while (current != null) {
            path.add(current.node)
            current = current.parent
        }
        return path.reversed() // Flip it so it goes Start -> End
    }
}
