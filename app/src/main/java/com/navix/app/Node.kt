package com.navix.app

data class Node(
    val id: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val neighborIds: List<String> = emptyList(),

    // NEW FIELDS
    val name: String? = null,      // e.g., "Lab 1", "Office", "Entrance"
    val type: String = "WALKING"   // "WALKING", "STAIRS", "ELEVATOR"
)