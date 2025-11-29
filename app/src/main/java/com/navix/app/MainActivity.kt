package com.navix.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import io.github.sceneview.ar.ArSceneView
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // UI Variables
    lateinit var sceneView: ArSceneView
    lateinit var btnPlaceNode: Button
    lateinit var btnSave: Button
    lateinit var spinnerType: Spinner

    // Database Variable (Using the standard instance to avoid import errors)
    val db = FirebaseFirestore.getInstance()

    // Data lists
    val nodesList = mutableListOf<NodeData>()
    var lastNodeId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Link UI elements
        sceneView = findViewById(R.id.sceneView)
        btnPlaceNode = findViewById(R.id.btnPlaceNode)
        btnSave = findViewById(R.id.btnSave)
        spinnerType = findViewById(R.id.spinnerType)

        // 2. Setup the Dropdown menu
        setupSpinner()

        // 3. Button Listeners
        btnPlaceNode.setOnClickListener {
            placeNode()
        }

        btnSave.setOnClickListener {
            uploadToFirebase()
        }
    }

    private fun setupSpinner() {
        val types = arrayOf("NORMAL", "STAIRS", "RAMP", "ELEVATOR")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        spinnerType.adapter = adapter
    }

    private fun placeNode() {
        // Fake coordinates for testing (since AR camera isn't working on your phone)
        val fakeX = (0..10).random().toFloat()
        val fakeZ = (0..10).random().toFloat()
        val nodeId = UUID.randomUUID().toString()
        val selectedType = spinnerType.selectedItem.toString()

        // Create Node
        val newNode = NodeData(
            id = nodeId,
            x = fakeX,
            y = 0f,
            z = fakeZ,
            type = selectedType
        )

        // Link to previous node
        if (lastNodeId != null) {
            newNode.neighbors.add(lastNodeId!!)
            // Bidirectional linking
            nodesList.find { it.id == lastNodeId }?.neighbors?.add(nodeId)
        }

        nodesList.add(newNode)
        lastNodeId = nodeId

        Toast.makeText(this, "Node Added! Total: ${nodesList.size}", Toast.LENGTH_SHORT).show()
    }

    private fun uploadToFirebase() {
        if (nodesList.isEmpty()) {
            Toast.makeText(this, "No nodes to save!", Toast.LENGTH_SHORT).show()
            return
        }

        val batch = db.batch()

        for (node in nodesList) {
            // Path: maps -> floor_1 -> nodes -> [ID]
            val ref = db.collection("maps")
                .document("floor_1")
                .collection("nodes")
                .document(node.id)

            batch.set(ref, node)
        }

        batch.commit()
            .addOnSuccessListener {
                Toast.makeText(this, "Success! Uploaded to Cloud.", Toast.LENGTH_LONG).show()
                nodesList.clear()
                lastNodeId = null
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}