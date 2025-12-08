package com.navix.app

import java.util.PriorityQueue
import kotlin.math.sqrt

class PathFinder {

    fun findPath(startNode: Node, endNode: Node, allNodes: List<Node>): List<Node> {
        val nodeMap = allNodes.associateBy { it.id }

        val gScore = mutableMapOf<Node, Double>()
        val fScore = mutableMapOf<Node, Double>()

        val openSet = PriorityQueue<Node> { n1, n2 ->
            val score1 = fScore.getOrDefault(n1, Double.MAX_VALUE)
            val score2 = fScore.getOrDefault(n2, Double.MAX_VALUE)
            score1.compareTo(score2)
        }

        gScore[startNode] = 0.0
        fScore[startNode] = heuristic(startNode, endNode)
        openSet.add(startNode)

        val cameFrom = mutableMapOf<Node, Node>()

        while (openSet.isNotEmpty()) {
            val current = openSet.poll() ?: break

            if (current.id == endNode.id) {
                return reconstructPath(cameFrom, current)
            }

            val neighbors = current.neighbors.mapNotNull { neighborId -> nodeMap[neighborId] }

            for (neighbor in neighbors) {
                val tentativeGScore = gScore.getOrDefault(current, Double.MAX_VALUE) + distanceBetween(current, neighbor)
                
                if (tentativeGScore < gScore.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    cameFrom[neighbor] = current
                    gScore[neighbor] = tentativeGScore
                    fScore[neighbor] = tentativeGScore + heuristic(neighbor, endNode)
                    if (neighbor !in openSet) {
                        openSet.add(neighbor)
                    }
                }
            }
        }

        return emptyList() // No path found
    }

    private fun distanceBetween(node1: Node, node2: Node): Double {
        val dx = (node1.x - node2.x).toDouble()
        val dy = (node1.y - node2.y).toDouble()
        val dz = (node1.z - node2.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun heuristic(node: Node, endNode: Node): Double {
        return distanceBetween(node, endNode)
    }

    private fun reconstructPath(cameFrom: Map<Node, Node>, current: Node): List<Node> {
        val totalPath = mutableListOf(current)
        var currentTrace = current
        while (cameFrom.containsKey(currentTrace)) {
            currentTrace = cameFrom[currentTrace]!!
            totalPath.add(0, currentTrace)
        }
        return totalPath
    }
}
