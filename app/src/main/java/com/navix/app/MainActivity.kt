package com.navix.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.ar.node.PlacementMode
import io.github.sceneview.math.Position
import java.util.UUID

class MainActivity : AppCompatActivity() {

    lateinit var sceneView: ArSceneView
    lateinit var btnPlaceNode: Button
    lateinit var btnSave: Button
    lateinit var spinnerType: Spinner

    // Database connection
    val db = Firebase.firestore

    // List to hold our nodes temporarily
    val nodesList = mutableListOf<NodeData>()
    var lastNodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)
        btnPlaceNode = findViewById(R.id.btnPlaceNode)
        btnSave = findViewById(R.id.btnSave)
        spinnerType = findViewById(R.id.spinnerType)

        setupSpinner()

        // 1. PLACE NODE BUTTON
        btnPlaceNode.setOnClickListener {
            placeNode()
        }

        // 2. SAVE BUTTON
        btnSave.setOnClickListener {
            uploadToFirebase()
        }
    }

    private fun setupSpinner() {
        // Dropdown options
        val types = arrayOf("NORMAL", "STAIRS", "RAMP", "ELEVATOR")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        spinnerType.adapter = adapter
    }

    private fun placeNode() {
        // NOTE: Since your AR doesn't work, this part won't show visual dots yet.
        // But the LOGIC will run.

        // 1. Get current position (In real AR, this comes from the camera)
        // For now, we simulate a fake position so you can test the database
        val fakeX = (0..10).random().toFloat()
        val fakeZ = (0..10).random().toFloat()

        val nodeId = UUID.randomUUID().toString()
        val selectedType = spinnerType.selectedItem.toString()

        // 2. Create the Node Data
        val newNode = NodeData(
            id = nodeId,
            x = fakeX,
            y = 0f,
            z = fakeZ,
            type = selectedType
        )

        // 3. Link to previous node (Graph logic)
        if (lastNodeId != null) {
            newNode.neighbors.add(lastNodeId!!)
            // Also find previous node and add this one to it
            nodesList.find { it.id == lastNodeId }?.neighbors?.add(nodeId)
        }

        // 4. Add to list
        nodesList.add(newNode)
        lastNodeId = nodeId

        Toast.makeText(this, "Node Added! Total: ${nodesList.size}", Toast.LENGTH_SHORT).show()
    }

    private fun uploadToFirebase() {
        if (nodesList.isEmpty()) {
            Toast.makeText(this, "No nodes to save!", Toast.LENGTH_SHORT).show()
            return
        }

        // Save every node to the "maps" collection
        val batch = db.batch()

        for (node in nodesList) {
            val ref = db.collection("maps").document("floor_1").collection("nodes").document(node.id)
            batch.set(ref, node)
        }

        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Success! Uploaded to Cloud.", Toast.LENGTH_LONG).show()
            nodesList.clear() // Clear list after save
            lastNodeId = null
        }.addOnFailureListener { e ->
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}