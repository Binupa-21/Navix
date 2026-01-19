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

    // Inside MainActivity class
    private var currentFloorId = "floor_1" // Default

    // Inside onCreate
    private fun setupFloorSpinner() {
        val floorSpinner = findViewById<android.widget.Spinner>(R.id.floorSpinner)
        // Check if view exists (it might be hidden in User mode, but good to check)
        if (floorSpinner != null && !isUserMode) {
            val floors = arrayOf("floor_1", "floor_2", "floor_3")
            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, floors)
            floorSpinner.adapter = adapter

            floorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                    currentFloorId = floors[position]
                    lastNodeId = null // Reset linking when switching floors
                    Toast.makeText(this@MainActivity, "Switched to $currentFloorId", Toast.LENGTH_SHORT).show()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
        } else {
            // Hide controls in User Mode
            findViewById<android.view.View>(R.id.adminControls)?.visibility = android.view.View.GONE
        }
    }

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

            sceneView.onSessionCreated = { _ ->
                runOnUiThread {
                    showDestinationPicker()
                }
            }
        } else {
            isUserMode = false
            Toast.makeText(this, "Admin Mode", Toast.LENGTH_SHORT).show()

            // --- ADD THIS LINE ---
            // Load the old dots so we can see them
            sceneView.onSessionCreated = { _ ->
                loadExistingMap()
            }
            // ---------------------
        }

        // 2. LOAD MARKER MODEL
        loadPreviewModel()

        // 3. SET UP AR TAP LISTENER - CORRECT SCENE VIEW 2.0.3 WAY
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
                val finalName = name.ifEmpty { null }
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
        // USE VARIABLE currentFloorId
        db.collection("maps").document(currentFloorId)
            .collection("nodes").document(newNode.id).set(newNode)

        if (lastNodeId != null) {
            db.collection("maps").document(currentFloorId)
                .collection("nodes").document(lastNodeId!!)
                .update("neighborIds", FieldValue.arrayUnion(newNode.id))
        }
        lastNodeId = newNode.id
    }

    private fun showDestinationPicker() {
        Toast.makeText(this, "Loading Map...", Toast.LENGTH_SHORT).show()

        db.collection("maps").document("floor_1")
            .collection("nodes").get()
            .addOnSuccessListener { result ->
                val allNodes = result.toObjects(Node::class.java)

                // Filter only named nodes (Locations)
                val namedNodes = allNodes.filter { !it.name.isNullOrEmpty() }

                if (namedNodes.size < 2) {
                    Toast.makeText(this, "Need at least 2 named locations to navigate!", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                // STEP 1: Ask for START Location
                showStartSelectionDialog(namedNodes, allNodes)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error loading map: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showStartSelectionDialog(namedNodes: List<Node>, allNodes: List<Node>) {
        val names = namedNodes.map { it.name!! }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Step 1: Where are you now?")
            .setItems(names) { _, which ->
                val startNode = namedNodes[which]
                // STEP 2: Ask for DESTINATION
                showEndSelectionDialog(startNode, namedNodes, allNodes)
            }
            .setCancelable(false) // User must pick a start point
            .show()
    }

    private fun showEndSelectionDialog(startNode: Node, namedNodes: List<Node>, allNodes: List<Node>) {
        // Filter out the start node (can't go to where you already are)
        val possibleDestinations = namedNodes.filter { it.id != startNode.id }
        val names = possibleDestinations.map { it.name!! }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Step 2: Where do you want to go?")
            .setItems(names) { _, which ->
                val endNode = possibleDestinations[which]
                startNavigation(startNode, endNode, allNodes)
            }
            .setNegativeButton("Back") { _, _ ->
                showStartSelectionDialog(namedNodes, allNodes)
            }
            .show()
    }

    private fun startNavigation(start: Node, end: Node, allNodes: List<Node>) {
        val pf = PathFinder()
        val path = pf.findPath(allNodes, start.id, end.id, false)

        if (path.isNotEmpty()) {
            // PASS THE START NODE so we can calculate the offset
            drawPathInAR(path, start)
        } else {
            Toast.makeText(this, "No path found between these points.", Toast.LENGTH_SHORT).show()
        }
    }


    private fun findClosestNode(allNodes: List<Node>): Node {
        return allNodes.firstOrNull { it.name != null } ?: allNodes.first()
    }

    private fun drawPathInAR(path: List<Node>, startNode: Node) {
        clearPath()

        lifecycleScope.launch {
            val modelInstance = sceneView.modelLoader.loadModelInstance("models/sphere.glb")

            if (modelInstance != null) {
                path.forEach { node ->
                    val sphereModelNode = ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = 0.05f
                    ).apply {
                        // --- THE MATHEMATICAL FIX ---
                        // Shift the world so the Start Node is at (0,0,0) (Your feet)
                        val offsetX = node.x - startNode.x
                        val offsetY = node.y - startNode.y
                        val offsetZ = node.z - startNode.z

                        position = Position(offsetX, offsetY, offsetZ)
                    }
                    sceneView.addChildNode(sphereModelNode)
                    placedAnchorNodes.add(sphereModelNode as? AnchorNode ?: return@forEach)
                }
                Toast.makeText(this@MainActivity, "Path loaded relative to ${startNode.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearPath() {
        // Remove all children that are NOT the main preview marker
        val nodesToRemove = sceneView.childNodes.filter { it != previewModelNode }
        nodesToRemove.forEach { sceneView.removeChildNode(it) }
    }

    override fun onResume() {
        super.onResume()
        // SceneView 2.0.3 uses onSessionResumed
        sceneView.onSessionResumed = { _ ->
            // Session resumed, you can add custom logic here if needed
        }
    }

    override fun onPause() {
        super.onPause()
        /* SceneView 2.0.3 uses onSessionPaused */
        sceneView.onSessionPaused = { _ ->
            // Session paused, you can add custom logic here if needed
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneView.destroy()
    }
    private fun loadExistingMap() {
        Toast.makeText(this, "Loading map for $currentFloorId...", Toast.LENGTH_SHORT).show()

        db.collection("maps").document(currentFloorId)
            .collection("nodes").get()
            .addOnSuccessListener { result ->
                val nodes = result.toObjects(Node::class.java)

                if (nodes.isEmpty()) {
                    Toast.makeText(this, "No nodes found on this floor.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                lifecycleScope.launch {
                    // Loop through every saved node
                    for (node in nodes) {
                        // FIX: Load a NEW model instance for every single node
                        val modelInstance = sceneView.modelLoader.loadModelInstance("models/marker.glb")

                        if (modelInstance != null) {
                            val markerNode = ModelNode(
                                modelInstance = modelInstance,
                                scaleToUnits = 0.2f,
                                centerOrigin = Position(y = -0.5f)
                            ).apply {
                                position = Position(node.x, node.y, node.z)
                            }

                            // Optional: Make these markers deletable too
                            markerNode.onSingleTapConfirmed = {
                                if (!isUserMode) {
                                    // Use 'node.id' from the loop variable directly
                                    // Note: We need to cast markerNode to AnchorNode isn't possible here
                                    // because these are purely virtual ModelNodes (not anchored to AR planes).
                                    // So we just remove the visual.
                                    showDeleteVirtualNodeDialog(node.id, markerNode)
                                }
                                true
                            }

                            sceneView.addChildNode(markerNode)
                        }
                    }
                    Toast.makeText(this@MainActivity, "${nodes.size} nodes loaded.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to load map.", Toast.LENGTH_SHORT).show()
            }
    }

    // Helper to delete these "Virtual" loaded nodes
    private fun showDeleteVirtualNodeDialog(nodeId: String, nodeToDelete: ModelNode) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Node?")
            .setMessage("Remove this saved node?")
            .setPositiveButton("Delete") { _, _ ->
                db.collection("maps").document(currentFloorId)
                    .collection("nodes").document(nodeId)
                    .delete()
                    .addOnSuccessListener {
                        sceneView.removeChildNode(nodeToDelete)
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}