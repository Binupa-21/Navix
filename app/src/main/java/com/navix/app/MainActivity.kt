package com.navix.app

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {

    private var latestFrame: Frame? = null

    private lateinit var sceneView: ARSceneView
    private lateinit var previewModelNode: ModelNode
    private val db: FirebaseFirestore = Firebase.firestore
    private var lastNodeId: String? = null
    private var isModelLoaded = false
    private var isUserMode = false
    private val placedAnchorNodes = mutableListOf<AnchorNode>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)

        sceneView.onSessionUpdated = { _, frame ->
            latestFrame = frame
        }


        // 1. MODE SETUP
        val mode = intent.getStringExtra("mode")
        if (mode == "USER") {
            isUserMode = true
            Toast.makeText(this, "User Mode", Toast.LENGTH_SHORT).show()

            // Wait for AR session to be ready
            sceneView.onSessionCreated = { session ->
                runOnUiThread {
                    showDestinationPicker()
                }
            }
        } else {
            isUserMode = false
            Toast.makeText(this, "Admin Mode: Tap to Map", Toast.LENGTH_SHORT).show()
        }

        // 2. LOAD MARKER MODEL
        loadPreviewModel()

        // 3. SET UP AR TAP LISTENER - CORRECT SCENEVIEW 2.0.3 WAY
        // In SceneView 2.0.3, onTapAr only takes one parameter (hitResult)
        sceneView.setOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                if (isUserMode || !isModelLoaded) return@setOnGestureListener

                val frame = latestFrame ?: return@setOnGestureListener
                val hitResults = frame.hitTest(motionEvent)

                for (hit in hitResults) {
                    val trackable = hit.trackable

                    if (
                        trackable is com.google.ar.core.Plane &&
                        trackable.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        trackable.isPoseInPolygon(hit.hitPose)
                    ) {
                        placeMarkerAtHit(hit)
                        return@setOnGestureListener
                    }
                }
            }
        )






    }

    private fun loadPreviewModel() {
        lifecycleScope.launch {
            val modelInstance = sceneView.modelLoader.loadModelInstance("models/marker.glb")

            if (modelInstance != null) {
                // centerOrigin and scaleToUnits go in the constructor
                previewModelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.2f,
                    centerOrigin = Position(y = -0.5f) // This aligns the bottom of the model to the floor
                )

                isModelLoaded = true
                if (!isUserMode) {
                    Toast.makeText(this@MainActivity, "Marker Loaded!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun placeMarkerAtHit(hitResult: HitResult) {
        val anchor = hitResult.createAnchor()

        // Correct: (Engine first, then the anchor)
        // Do NOT use "engine = anchor"
        val anchorNode = AnchorNode(sceneView.engine, anchor)

        lifecycleScope.launch {
            val modelInstance = sceneView.modelLoader.loadModelInstance("models/marker.glb")
            if (modelInstance != null) {
                val markerModelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.2f,
                    centerOrigin = Position(y = -0.5f)
                )

                anchorNode.addChildNode(markerModelNode)
                sceneView.addChildNode(anchorNode)
                placedAnchorNodes.add(anchorNode)

                // worldPosition is a property, no parentheses
                val worldPos = anchorNode.worldPosition
                showNameDialog(worldPos.x, worldPos.y, worldPos.z)
            }
        }
    }

    private fun showNameDialog(x: Float, y: Float, z: Float) {
        val input = EditText(this)
        input.hint = "Name (e.g. Lab 1)"

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Node")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                val finalName = if (name.isEmpty()) null else name
                val type = if (name.equals("stairs", true)) "STAIRS" else "WALKING"
                createAndSaveNode(x, y, z, finalName, type)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createAndSaveNode(x: Float, y: Float, z: Float, name: String?, type: String) {
        val nodeId = "node_" + System.currentTimeMillis()
        val neighbors = mutableListOf<String>()
        if (lastNodeId != null) neighbors.add(lastNodeId!!)

        val newNode = Node(nodeId, x, y, z, neighbors, name, type)
        saveNode(newNode)
    }

    private fun saveNode(newNode: Node) {
        db.collection("maps").document("floor_1")
            .collection("nodes").document(newNode.id).set(newNode)
            .addOnSuccessListener {
                if (lastNodeId != null) {
                    db.collection("maps").document("floor_1")
                        .collection("nodes").document(lastNodeId!!)
                        .update("neighborIds", FieldValue.arrayUnion(newNode.id))
                        .addOnSuccessListener {
                            lastNodeId = newNode.id
                            Toast.makeText(this, "Node saved and linked!", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    lastNodeId = newNode.id
                    Toast.makeText(this, "First node saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showDestinationPicker() {
        db.collection("maps").document("floor_1")
            .collection("nodes").get()
            .addOnSuccessListener { result ->
                val allNodes = result.toObjects(Node::class.java)
                val destinations = allNodes.filter { !it.name.isNullOrEmpty() }

                if (destinations.isEmpty()) {
                    Toast.makeText(this, "No destinations mapped yet", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val names = destinations.map { it.name!! }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Select Destination")
                    .setItems(names) { _, which ->
                        val startNode = findClosestNode(allNodes)
                        startNavigation(startNode, destinations[which], allNodes)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to load nodes: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun findClosestNode(allNodes: List<Node>): Node {
        return allNodes.firstOrNull { it.name != null } ?: allNodes.first()
    }

    private fun startNavigation(start: Node, end: Node, allNodes: List<Node>) {
        val pf = PathFinder()
        val path = pf.findPath(allNodes, start.id, end.id, false)

        if (path.isNotEmpty()) {
            drawPathInAR(path)
        } else {
            Toast.makeText(this, "No path found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawPathInAR(path: List<Node>) {
        clearPath()

        // 1. Launch a coroutine to load the model asynchronously
        lifecycleScope.launch {
            // 2. Load the model once to reuse it for all path nodes
            val modelInstance = sceneView.modelLoader.loadModelInstance("models/sphere.glb")

            if (modelInstance != null) {
                path.forEach { node ->
                    // 3. Create a new ModelNode using the loaded instance
                    val sphereModelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = 0.05f // 5cm spheres
                    ).apply {
                        // 4. Set the position
                        position = Position(node.x, node.y, node.z)
                    }
                    sceneView.addChildNode(sphereModelNode)
                }
                Toast.makeText(this@MainActivity, "Path displayed!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "Failed to load sphere model", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearPath() {
        sceneView.childNodes.forEach { node ->
            if (node is ModelNode && !placedAnchorNodes.any { it == node.parent }) {
                sceneView.removeChildNode(node)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // SceneView 2.0.3 uses onSessionResumed
        sceneView.onSessionResumed = { session ->
            // Session resumed, you can add custom logic here if needed
        }
    }

    override fun onPause() {
        super.onPause()
        // SceneView 2.0.3 uses onSessionPaused
        sceneView.onSessionPaused = { session ->
            // Session paused, you can add custom logic here if needed
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneView.destroy()
    }
}