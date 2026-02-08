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
    private var pendingAnchor: com.google.ar.core.Anchor? = null
    private var isHosting = false

    private lateinit var instructionText: android.widget.TextView

    // Inside MainActivity class
    private var currentFloorId = "floor_1" // Default

    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var statusText: android.widget.TextView

    private var resolvingAnchor: com.google.ar.core.Anchor? = null
    private var isResolving = false

    // Change this at the top of MainActivity
    private val placedPathNodes = mutableListOf<io.github.sceneview.node.Node>()
    private var navigationStartNode: Node? = null

    // Map to link Google's Cloud ID to our Node data
    private val cloudAnchorMap = mutableMapOf<String, Node>()
    private var detectedStartNode: Node? = null
    private var isSearchingForLocation = false

    // Inside onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. SET CONTENT VIEW FIRST (Crucial!)
        setContentView(R.layout.activity_main)

        // 2. INITIALIZE ALL VIEWS (Must happen after setContentView)
        sceneView = findViewById(R.id.sceneView)
        progressBar = findViewById(R.id.loadingProgressBar)
        statusText = findViewById(R.id.statusText)
        instructionText = findViewById(R.id.instructionText)

        // 3. CONFIGURE AR SESSION (Enable Cloud Anchors)
        sceneView.configureSession { _, config ->
            config.cloudAnchorMode = com.google.ar.core.Config.CloudAnchorMode.ENABLED
            config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
        }

        // 4. MAIN UPDATE LOOP (Checking Cloud States & UI messages)
        sceneView.onSessionUpdated = { session, frame ->
            latestFrame = frame

            // A. AUTO-LOCATION DETECTION (Syncing Admin or User to the floor)
            if (isSearchingForLocation) {
                val allAnchors = session.allAnchors
                for (anchor in allAnchors) {
                    if (anchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                        val cloudId = anchor.cloudAnchorId

                        if (cloudAnchorMap.containsKey(cloudId)) {
                            // PHYSICAL MATCH FOUND!
                            detectedStartNode = cloudAnchorMap[cloudId]
                            isSearchingForLocation = false
                            hideLoading()

                            runOnUiThread {
                                if (isUserMode) {
                                    Toast.makeText(this, "Location found: ${detectedStartNode?.name ?: "Hallway"}", Toast.LENGTH_LONG).show()
                                    showDestinationPickerOnly()
                                } else {
                                    lastNodeId = detectedStartNode?.id
                                    loadExistingMap()
                                    Toast.makeText(this, "Map Synced! Ready to continue.", Toast.LENGTH_LONG).show()
                                }
                            }
                            break
                        }
                    }
                }
            }

            // B. CHECK HOSTING (Admin Mode Saving)
            if (isHosting && pendingAnchor != null) {
                val state = pendingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isHosting = false
                    hideLoading()
                    val cloudId = pendingAnchor!!.cloudAnchorId
                    val pose = pendingAnchor!!.pose
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

            // C. CHECK RESOLVING (Specific Navigation Sync)
            if (isResolving && resolvingAnchor != null) {
                val state = resolvingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread {
                        Toast.makeText(this, "Destination Sync Successful!", Toast.LENGTH_SHORT).show()
                        proceedToDrawPath()
                    }
                } else if (state.isError) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread { Toast.makeText(this, "Sync Failed: $state", Toast.LENGTH_LONG).show() }
                }
            }

            // D. UPDATE THE TOP INSTRUCTION TEXT
            updateStatusMessage()
        }

        // 5. RUN LOGIC SETUP
        setupMode() // Handles Database fetch
        setupFloorSpinner() // Initializes Dropdown
        loadPreviewModel() // Loads 3D Marker

        // 6. TAP GESTURE (For Admin Mapping)
        sceneView.setOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                // Logic to prevent tapping while searching or in User mode
                if (isUserMode || !isModelLoaded || isSearchingForLocation || isHosting) return@setOnGestureListener

                val frame = latestFrame ?: return@setOnGestureListener
                val hitResults = frame.hitTest(motionEvent)

                for (hit in hitResults) {
                    val trackable = hit.trackable
                    if (trackable is com.google.ar.core.Plane &&
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
        val localAnchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(sceneView.engine, localAnchor)

        lifecycleScope.launch {
            sceneView.modelLoader.loadModelInstance("models/marker.glb")?.let {
                val markerModelNode = ModelNode(
                    modelInstance = it,
                    autoAnimate = true,
                    scaleToUnits = 0.2f, // 20cm marker
                    centerOrigin = Position(y = -0.5f)
                )
                anchorNode.addChildNode(markerModelNode)
                sceneView.addChildNode(anchorNode)

                // TRACKING: Add to our unified list for clearing
                placedPathNodes.add(anchorNode)
            }
        }

        showLoading("Uploading to Google Cloud... Walk in a slow circle.")
        try {
            pendingAnchor = sceneView.session?.hostCloudAnchor(localAnchor)
            isHosting = (pendingAnchor != null)
        } catch (e: Exception) {
            runOnUiThread {
                hideLoading()
                Toast.makeText(this, "Cloud Anchor System Busy", Toast.LENGTH_SHORT).show()
            }
        }
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


    private fun drawPathInAR(path: List<Node>, startNode: Node) {
        clearPath() // Wipe previous path

        lifecycleScope.launch {
            // 1. Pre-load all three models once to keep the app fast
            val sphereModel = sceneView.modelLoader.loadModelInstance("models/sphere.glb")
            val markerModel = sceneView.modelLoader.loadModelInstance("models/marker.glb")
            val arrowModel = sceneView.modelLoader.loadModelInstance("models/arrow.glb") // Optional

            if (sphereModel != null && markerModel != null) {
                path.forEachIndexed { index, node ->

                    // 2. Decide which model to use
                    val isDestination = (index == path.size - 1)
                    val isEveryFifth = (index % 5 == 0 && index != 0)

                    // Pick the model instance
                    val selectedInstance = when {
                        isDestination -> markerModel
                        isEveryFifth && arrowModel != null -> arrowModel
                        else -> sphereModel
                    }

                    // 3. Create the node
                    val pathNode = ModelNode(
                        modelInstance = selectedInstance,
                        // Make destination much larger (25cm) than breadcrumbs (5cm)
                        scaleToUnits = if (isDestination) 0.25f else 0.05f
                    ).apply {
                        // World Shift Math
                        val offsetX = node.x - startNode.x
                        val offsetY = node.y - startNode.y
                        val offsetZ = node.z - startNode.z
                        position = Position(offsetX, offsetY, offsetZ)

                        // 4. Orientation (If it's an arrow, make it look forward)
                        if (isEveryFifth && !isDestination) {
                            // Advanced: You could calculate rotation here to point to next node
                            // For now, keeping it simple
                        }
                    }

                    sceneView.addChildNode(pathNode)
                    placedPathNodes.add(pathNode)
                }

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Navigation Started!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearPath() {
        // Remove every node we tracked from the 3D scene
        placedPathNodes.forEach { node ->
            sceneView.removeChildNode(node)
        }
        // Empty the list so it's ready for the next path
        placedPathNodes.clear()
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

    private fun setupMode() {
        val mode = intent.getStringExtra("mode")
        isUserMode = (mode == "USER")

        // BOTH modes now start by searching for physical anchors
        isSearchingForLocation = true
        showLoading("Scanning environment to sync map...")

        sceneView.onSessionCreated = { _ ->
            // Download the floor map immediately
            db.collection("maps").document(currentFloorId).collection("nodes").get()
                .addOnSuccessListener { result ->
                    val allNodes = result.toObjects(Node::class.java)

                    if (allNodes.isEmpty()) {
                        // BRAND NEW MAP: Nothing to find.
                        if (!isUserMode) {
                            isSearchingForLocation = false
                            hideLoading()
                            Toast.makeText(this, "New floor! Start tapping to map.", Toast.LENGTH_SHORT).show()
                        } else {
                            hideLoading()
                            Toast.makeText(this, "Error: No map exists for this floor.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // EXISTING MAP: Tell ARCore to find every cloud ID we have in Firebase
                        allNodes.forEach { node ->
                            node.cloudAnchorId?.let { cloudId ->
                                cloudAnchorMap[cloudId] = node
                                sceneView.session?.resolveCloudAnchor(cloudId)
                            }
                        }
                    }
                }
        }
    }

    private fun setupFloorSpinner() {
        val floorSpinner = findViewById<android.widget.Spinner>(R.id.floorSpinner) ?: return
        if (isUserMode) {
            findViewById<android.view.View>(R.id.adminControls)?.visibility = android.view.View.GONE
            return
        }
        val floors = arrayOf("floor_1", "floor_2", "floor_3")
        floorSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, floors)
        floorSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, p1: android.view.View?, pos: Int, p3: Long) {
                if (currentFloorId != floors[pos]) {
                    currentFloorId = floors[pos]
                    lastNodeId = null
                    cloudAnchorMap.clear()
                    clearPath()
                    setupMode() // Refresh data for new floor
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateStatusMessage() {
        runOnUiThread {
            when {
                isSearchingForLocation -> instructionText.text = "Point camera at a door or known marker to sync."
                isHosting -> instructionText.text = "Google is learning this spot... walk in a slow circle."
                isResolving -> instructionText.text = "Finding your location... hold still."
                isUserMode && !isSearchingForLocation -> instructionText.text = "Follow the green path to your destination."
                !isUserMode && !isSearchingForLocation -> instructionText.text = "Tap floor to place a new node."
                else -> instructionText.text = "Move phone slowly to scan floor."
            }
        }
    }


}