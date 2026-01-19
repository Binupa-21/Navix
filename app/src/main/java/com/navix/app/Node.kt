package com.navix.app

import com.google.firebase.firestore.Exclude

data class Node(
    val id: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val neighborIds: List<String> = emptyList(),
    val name: String? = null,
    val type: String = "WALKING", /* WALKING or STAIRS */

    @get:Exclude
    private var neighbors: List<Node> = emptyList()
) {
    // For Firestore serialization (Firestore requires empty constructor)
    constructor() : this("", 0f, 0f, 0f, emptyList(), null, "WALKING")
}