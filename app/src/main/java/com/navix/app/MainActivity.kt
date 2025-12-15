package com.navix.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.math.Position

class MainActivity : AppCompatActivity() {

    lateinit var sceneView: ArSceneView
    private lateinit var modelNode: ArModelNode
    val db = FirebaseFirestore.getInstance()
    private var lastNodeId: String? = null
    private var isModelLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)

        modelNode = ArModelNode(sceneView.engine).apply {
            loadModelGlbAsync(
                glbFileLocation = "models/sphere.glb",
                centerOrigin = Position(y = -0.5f), // Places the origin at the bottom of the sphere
                onLoaded = { _ ->
                    Toast.makeText(this@MainActivity, "Model Loaded Successfully", Toast.LENGTH_SHORT).show()
                    sceneView.planeRenderer.isEnabled = false
                    isModelLoaded = true
                },
                onError = { exception ->
                    Toast.makeText(this@MainActivity, "Failed to load model: $exception", Toast.LENGTH_LONG).show()
                }
            )
        }

        sceneView.onTapAr = { hitResult, _ ->
            if (isModelLoaded) {
                // Create an anchor from the tap
                val anchor = hitResult.createAnchor()

                // Clone the pre-loaded model and add it to the scene
                modelNode.clone().let {
                    it.anchor = anchor
                    sceneView.addChild(it)
                }

                // Get the coordinates for the database
                val pose = anchor.pose
                val x = pose.tx()
                val y = pose.ty()
                val z = pose.tz()

                val nodeId = "node_" + System.currentTimeMillis()
                val nodeData = Node(
                    id = nodeId,
                    x = x,
                    y = y,
                    z = z,
                    neighbors = mutableListOf()
                )

                chainAndUpdateNode(nodeData)

                Toast.makeText(this@MainActivity, "Saved Point: $nodeId", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Model is loading, please wait...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun chainAndUpdateNode(newNode: Node) {
        val nodeDocument = db.collection("maps").document("floor_1")
            .collection("nodes").document(newNode.id)

        nodeDocument.set(newNode).addOnFailureListener { e ->
            Toast.makeText(this, "Error saving node: ${e.message}", Toast.LENGTH_LONG).show()
        }

        lastNodeId?.let {
            val lastNodeDocument = db.collection("maps").document("floor_1")
                .collection("nodes").document(it)

            lastNodeDocument.update("neighbors", FieldValue.arrayUnion(newNode.id))
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Error updating previous node: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        lastNodeId = newNode.id
    }
}
