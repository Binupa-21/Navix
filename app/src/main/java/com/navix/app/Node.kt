package com.navix.app

data class Node(
    val id: String = "",
    val x: Float = 0.0f,
    val y: Float = 0.0f,
    val z: Float = 0.0f,
    val neighbors: List<String> = listOf()
)
