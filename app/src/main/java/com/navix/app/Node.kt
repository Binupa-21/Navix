package com.navix.app

data class Node(
    val id: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val neighborIds: List<String> = emptyList(),
    val name: String? = null,
    val type: String = "WALKING",
    // NEW FIELD
    val floorId: String = "floor_1",

    val cloudAnchorId: String? = null
) {
    // For Firestore serialization (Firestore requires empty constructor)
    constructor() : this("", 0f, 0f, 0f, emptyList(), null, "WALKING")
}