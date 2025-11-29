package com.navix.app

// This represents one dot on the map
data class NodeData(
    val id: String = "",       // Unique ID (e.g., "node_123")
    val x: Float = 0f,         // Position X
    val y: Float = 0f,         // Position Y (Height)
    val z: Float = 0f,         // Position Z
    val type: String = "NORMAL", // "NORMAL", "STAIRS", "RAMP"
    val neighbors: MutableList<String> = mutableListOf() // IDs of connected nodes
)