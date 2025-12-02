package com.navix.app

data class Node(
    val id: String = "",       // Unique ID (e.g., "node_001")
    val x: Float = 0f,         // Position X
    val y: Float = 0f,         // Position Y (Height)
    val z: Float = 0f,         // Position Z
    val neighborIds: List<String> = emptyList() // Connections to other nodes
)
