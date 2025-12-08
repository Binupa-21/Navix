package com.navix.app

import com.google.firebase.firestore.Exclude

// We are now using a MutableList for neighbors
data class Node(
    val id: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val neighbors: MutableList<String> = mutableListOf()
)
