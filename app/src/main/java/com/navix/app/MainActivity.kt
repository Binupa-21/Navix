package com.navix.app

import android.os.Bundle
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

    private var pendingAnchor: com.google.ar.core.Anchor? = null
    private var isHosting = false

    // Inside MainActivity class
    private var currentFloorId = "floor_1" // Default

    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var statusText: android.widget.TextView

    private var resolvingAnchor: com.google.ar.core.Anchor? = null
    private var isResolving = false

    private val placedPathNodes = mutableListOf<io.github.sceneview.node.ModelNode>()

    private var navigationStartNode: Node? = null

    // Map to link Google's Cloud ID to our Node data
    private val cloudAnchorMap = mutableMapOf<String, Node>()
    private var detectedStartNode: Node? = null
    private var isSearchingForLocation = false

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
        sceneView.configureSession { session, config ->
            // This is the "Switch" that turns on Cloud Anchors
            config.cloudAnchorMode = com.google.ar.core.Config.CloudAnchorMode.ENABLED

            // Optional: Enable Autofocus for better scanning
            config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
        }
        sceneView.configureSession { session, config ->
            // This is the "Switch" that turns on Cloud Anchors
            config.cloudAnchorMode = com.google.ar.core.Config.CloudAnchorMode.ENABLED

            // Optional: Enable Autofocus for better scanning
            config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
        }

        // 1. SETUP SESSION UPDATE LOOP (For Hosting Checks)
        progressBar = findViewById(R.id.loadingProgressBar)
        statusText = findViewById(R.id.statusText)

        sceneView.onSessionUpdated = { session, frame ->
            latestFrame = frame

            // --- AUTO-LOCATION DETECTION ---
            if (isUserMode && isSearchingForLocation) {
                // Check every anchor the session is currently tracking
                val allAnchors = session.allAnchors
                for (anchor in allAnchors) {
                    // If Google found a match for one of our IDs
                    if (anchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                        val cloudId = anchor.cloudAnchorId

                        if (cloudAnchorMap.containsKey(cloudId)) {
                            // MATCH FOUND!
                            detectedStartNode = cloudAnchorMap[cloudId]
                            isSearchingForLocation = false
                            hideLoading()

                            // Success Feedback
                            runOnUiThread {
                                Toast.makeText(this, "Location found: ${detectedStartNode?.name ?: "Hallway"}", Toast.LENGTH_LONG).show()
                                // NOW show the destination picker, because we know where we are!
                                showDestinationPickerOnly()
                            }
                            break
                        }
                    }
                }
            }

            // --- CHECK HOSTING (Admin Mode) ---
            if (isHosting && pendingAnchor != null) {
                val state = pendingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isHosting = false
                    hideLoading()
                    val cloudId = pendingAnchor!!.cloudAnchorId
                    val pose = pendingAnchor!!.pose

                    // FIX: Must use runOnUiThread to show the Dialog from the AR thread
                    runOnUiThread {
                        showNameDialog(pose.tx(), pose.ty(), pose.tz(), cloudId)
                    }
                    pendingAnchor = null
                } else if (state.isError) {
                    isHosting = false
                    hideLoading()
                    runOnUiThread { Toast.makeText(this, "Hosting Error: $state", Toast.LENGTH_LONG).show() }
                    pendingAnchor = null
                }
            }

            // --- CHECK RESOLVING (User Mode) ---
            if (isResolving && resolvingAnchor != null) {
                val state = resolvingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread {
                        Toast.makeText(this, "Location Synced!", Toast.LENGTH_SHORT).show()
                        proceedToDrawPath() // Trigger the path drawing
                    }
                } else if (state.isError) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread { Toast.makeText(this, "Sync Failed: $state", Toast.LENGTH_LONG).show() }
                }
            }
        }

        // 2. MODE SETUP
        val mode = intent.getStringExtra("mode")
        if (mode == "USER") {
            isUserMode = true
            isSearchingForLocation = true
            showLoading("Scanning room... Point camera at floor.")

            sceneView.onSessionCreated = { _ ->
                // 1. Download all nodes from Firebase
                db.collection("maps").document(currentFloorId).collection("nodes").get()
                    .addOnSuccessListener { result ->
                        val allNodes = result.toObjects(Node::class.java)

                        // 2. Tell ARCore to start looking for every Cloud Anchor ID we have
                        allNodes.forEach { node ->
                            node.cloudAnchorId?.let { cloudId ->
                                cloudAnchorMap[cloudId] = node // Remember which node matches this ID
                                sceneView.session?.resolveCloudAnchor(cloudId) // Start searching
                            }
                        }
                    }
            }
        } else {
            isUserMode = false
            Toast.makeText(this, "Admin Mode", Toast.LENGTH_SHORT).show()
            // Setup Spinner
            setupFloorSpinner()
            // Load existing map
            sceneView.onSessionCreated = { _ ->
                loadExistingMap()
            }
        }

        // 3. LOAD MARKER MODEL
        loadPreviewModel()

        // 4. SET UP AR TAP LISTENER
        sceneView.setOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                if (isUserMode || !isModelLoaded) return@setOnGestureListener

                // Prevent double taps while hosting
                if (isHosting) {
                    Toast.makeText(this, "Wait, still hosting...", Toast.LENGTH_SHORT).show()
                    return@setOnGestureListener
                }

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
        // 1. Create a standard local anchor from the tap
        val localAnchor = hitResult.createAnchor()

        // 2. Create an AnchorNode (The 3D "hook" in the real world)
        val anchorNode = AnchorNode(sceneView.engine, localAnchor)

        // 3. Load and show the visual marker immediately
        lifecycleScope.launch {
            val modelInstance = sceneView.modelLoader.loadModelInstance("models/marker.glb")
            if (modelInstance != null) {
                val markerModelNode = ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 0.2f,
                    centerOrigin = Position(y = -0.5f)
                )

                // Attach visual to the anchor and add to the scene
                anchorNode.addChildNode(markerModelNode)
                sceneView.addChildNode(anchorNode)

                // Track this node so we can remove it if needed
                placedAnchorNodes.add(anchorNode)
            }
        }

        // 4. TRIGGER CLOUD HOSTING
        // We show the loading screen because this takes 5-10 seconds
        showLoading("Hosting point... Move your phone slowly around the marker.")

        try {
            val session = sceneView.session
            if (session != null) {
                pendingAnchor = session.hostCloudAnchor(localAnchor)

                if (pendingAnchor == null) {
                    hideLoading()
                    Toast.makeText(this, "Session Config Error: Cloud Anchors not enabled", Toast.LENGTH_LONG).show()
                } else {
                    isHosting = true
                }
            }
        } catch (e: Exception) {
            hideLoading()
            // This will now print the actual technical error to your Logcat
            android.util.Log.e("NaviX", "Hosting failed", e)
            Toast.makeText(this, "Error: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }

        // NOTE: We DO NOT call showNameDialog here.
        // We must wait for the SUCCESS state in the onSessionUpdated loop.
    }

    private fun showNameDialog(x: Float, y: Float, z: Float, cloudId: String) {
        val input = android.widget.EditText(this)
        input.hint = "Name (e.g. Lab 1)"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Save Cloud Anchor?")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                val finalName = if (name.isEmpty()) null else name
                val type = if (name.equals("stairs", true)) "STAIRS" else "WALKING"

                // Pass cloudId
                createAndSaveNode(x, y, z, finalName, type, cloudId)

            }
            .setNegativeButton("Cancel", null) // Note: Visual node stays, simple limitation
            .show()
    }

    private fun createAndSaveNode(x: Float, y: Float, z: Float, name: String?, type: String, cloudId: String) {
        val nodeId = "node_" + System.currentTimeMillis()
        val neighbors = mutableListOf<String>()
        if (lastNodeId != null) neighbors.add(lastNodeId!!)

        val newNode = Node(
            id = nodeId,
            x = x, y = y, z = z, // We still save these for backup
            neighborIds = neighbors,
            name = name,
            type = type,
            floorId = currentFloorId,
            cloudAnchorId = cloudId // SAVE THE ID!
        )
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

    // Temporary variables to store info while waiting for Resolve
    private var finalPath: List<Node>? = null

    private fun startNavigation(start: Node, end: Node, allNodes: List<Node>) {
        if (start.cloudAnchorId == null) {
            Toast.makeText(this, "No Cloud ID for start point!", Toast.LENGTH_SHORT).show()
            return
        }

        val pf = PathFinder()
        // Assuming findPath takes (nodes, startId, endId, isWheelchair)
        finalPath = pf.findPath(allNodes, start.id, end.id, false)

        if (finalPath != null && finalPath!!.isNotEmpty()) {
            navigationStartNode = start // Store this for the math offset later

            showLoading("Syncing location... Look at ${start.name}")

            // This tells ARCore to look for the physical spot saved by Admin
            resolvingAnchor = sceneView.session?.resolveCloudAnchor(start.cloudAnchorId)
            isResolving = true
        }
    }

    private fun proceedToDrawPath() {
        // FIX: Pass both required parameters (Path and StartNode)
        if (finalPath != null && navigationStartNode != null) {
            drawPathInAR(finalPath!!, navigationStartNode!!)
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
                    val sphereModelNode = io.github.sceneview.node.ModelNode(
                        modelInstance = modelInstance,
                        scaleToUnits = 0.05f
                    ).apply {
                        // --- THE WORLD-SHIFT MATH ---
                        // Subtract startNode coordinates to make the path relative to your current anchor
                        val offsetX = node.x - startNode.x
                        val offsetY = node.y - startNode.y
                        val offsetZ = node.z - startNode.z

                        position = io.github.sceneview.math.Position(offsetX, offsetY, offsetZ)
                    }
                    sceneView.addChildNode(sphereModelNode)
                    placedPathNodes.add(sphereModelNode) // Fixed list type
                }
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
    private fun showLoading(message: String) {
        runOnUiThread {
            progressBar.visibility = android.view.View.VISIBLE
            statusText.visibility = android.view.View.VISIBLE
            statusText.text = message
        }
    }

    private fun hideLoading() {
        runOnUiThread {
            progressBar.visibility = android.view.View.GONE
            statusText.visibility = android.view.View.GONE
        }
    }

    private fun showDestinationPickerOnly() {
        // Get all the destinations from the map we already downloaded
        val allNodes = cloudAnchorMap.values.toList()
        val destinations = allNodes.filter { !it.name.isNullOrEmpty() }
        val names = destinations.map { it.name!! }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("You are at ${detectedStartNode?.name}. Go where?")
            .setItems(names) { _, which ->
                val target = destinations[which]
                // Start navigation using the DETECTED node as the start
                startNavigation(detectedStartNode!!, target, allNodes)
            }
            .setCancelable(false)
            .show()
    }
}