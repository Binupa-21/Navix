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

    // Map to track the actual ARCore Anchors we are currently resolving/holding
    private val activeCloudAnchors = mutableMapOf<String, com.google.ar.core.Anchor>()
    private var lastProximityUpdateMillis = 0L
    private var allDownloadedNodes = listOf<Node>() // Cache to avoid constant Firebase reads

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

    // --- Navigation draw control ---
    private var pendingDrawPath = false

    // --- Cached models to avoid load race ---
    private var sphereModel: io.github.sceneview.model.ModelInstance? = null
    private var markerModelCached: io.github.sceneview.model.ModelInstance? = null


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

    private var currentPath: List<Node>? = null
    private var isNavigating = false

    private var currentPathIndex = 0
    private var lastInstructionUpdateMillis = 0L

    // Inside onCreate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. SET CONTENT VIEW FIRST (Crucial for findViewById to work)
        setContentView(R.layout.activity_main)

        // 2. INITIALIZE ALL VIEWS
        sceneView = findViewById(R.id.sceneView)
        progressBar = findViewById(R.id.loadingProgressBar)
        statusText = findViewById(R.id.statusText)
        instructionText = findViewById(R.id.instructionText)

        // 3. PRELOAD MODELS FOR PERFORMANCE
        preloadModels()

        // 4. CONFIGURE AR SESSION (Single instance)
        sceneView.configureSession { session, config ->
            config.cloudAnchorMode = com.google.ar.core.Config.CloudAnchorMode.ENABLED
            config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
            // Add this to help Google "see" the floor better
            config.planeFindingMode = com.google.ar.core.Config.PlaneFindingMode.HORIZONTAL
        }

        // 5. MAIN UPDATE LOOP (Checking Cloud States & UI)
        sceneView.onSessionUpdated = { session, frame ->
            latestFrame = frame

            // --- PROXIMITY MANAGER (Runs every 2 seconds) ---
            val currentTime = System.currentTimeMillis()
// ONLY run proximity checks if we aren't busy resolving the start location
            if (currentTime - lastProximityUpdateMillis > 2000 && allDownloadedNodes.isNotEmpty() && !isResolving && !isSearchingForLocation) {
                lastProximityUpdateMillis = currentTime
                updateProximityAnchors(sceneView.session!!, frame.camera.pose)
            }

            // A. AUTO-LOCATION DETECTION
            if (isSearchingForLocation) {
                // We check our active map instead of just session.allAnchors for better control
                for ((cloudId, anchor) in activeCloudAnchors) {
                    if (anchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                        if (cloudAnchorMap.containsKey(cloudId)) {
                            detectedStartNode = cloudAnchorMap[cloudId]

                            // --- ADD THIS CRITICAL LINE ---
                            resolvingAnchor = anchor
                            // ------------------------------

                            isSearchingForLocation = false
                            runOnUiThread {
                                hideLoading()
                                onLocationSynced()
                            }
                            break
                        }
                    }
                }
            }

            // B. CHECK HOSTING (Admin Mode)
            if (isHosting && pendingAnchor != null) {
                val state = pendingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    val cloudId = pendingAnchor!!.cloudAnchorId
                    val pose = pendingAnchor!!.pose
                    isHosting = false

                    // Add to active tracking
                    activeCloudAnchors[cloudId] = pendingAnchor!!

                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Cloud Sync Successful!", Toast.LENGTH_SHORT).show()
                        showNameDialog(pose.tx(), pose.ty(), pose.tz(), cloudId)
                    }
                    pendingAnchor = null
                } else if (state.isError) {
                    isHosting = false
                    val error = state.name
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Hosting Failed: $error", Toast.LENGTH_LONG).show()
                    }
                    pendingAnchor?.detach()
                    pendingAnchor = null
                }
            }

            // C. CHECK RESOLVING (Navigation Mode)
            // Inside your sceneView.onSessionUpdated loop:

// C. CHECK RESOLVING (Specific Navigation Sync)
            if (isResolving && resolvingAnchor != null) {
                val state = resolvingAnchor?.cloudAnchorState ?: return@onSessionUpdated
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread {
                        Toast.makeText(this, "Location Verified!", Toast.LENGTH_SHORT).show()
                        // Step 2: Now ask where they are going
                        showDestinationPickerOnly()
                    }
                } else if (state.isError) {
                    isResolving = false
                    hideLoading()
                    runOnUiThread {
                        Toast.makeText(this, "Could not verify location. Try again.", Toast.LENGTH_LONG).show()
                        showStartSelectionPicker() // Restart flow
                    }
                }
            }

            updateStatusMessage()
            if (isNavigating && finalPath != null && !isSearchingForLocation) {
                updateLiveInstructions()
            }
        }

        // 6. RUN LOGIC SETUP
        setupMode()         // Downloads DB data
        setupFloorSpinner() // Initializes Dropdown
        loadPreviewModel()  // Prepares Admin Marker

        // 7. TAP GESTURE (Admin Mapping)
        sceneView.setOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
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
        val session = sceneView.session ?: return

        if (isHosting) return

        // FIX: Check Mapping Quality. Hosting usually fails if quality is INSUFFICIENT
        val quality = session.estimateFeatureMapQualityForHosting(hitResult.hitPose)
        if (quality == com.google.ar.core.Session.FeatureMapQuality.INSUFFICIENT) {
            Toast.makeText(this, "Low mapping quality. Scan the area more!", Toast.LENGTH_LONG).show()
            // We continue, but this is the likely cause of "Resource Exhausted"
        }

        val localAnchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(sceneView.engine, localAnchor)

        lifecycleScope.launch {
            sceneView.modelLoader.loadModelInstance("models/marker.glb")?.let {
                val markerModelNode = ModelNode(
                    modelInstance = it,
                    scaleToUnits = 0.2f,
                    centerOrigin = Position(y = -0.5f)
                )
                anchorNode.addChildNode(markerModelNode)
                sceneView.addChildNode(anchorNode)
                placedPathNodes.add(anchorNode)
            }
        }

        showLoading("Hosting to Cloud...")

        try {
            isHosting = true
            pendingAnchor = session.hostCloudAnchor(localAnchor)
            if (pendingAnchor == null) {
                isHosting = false
                hideLoading()
                Toast.makeText(this, "Queue Full or Internal Error (Null Anchor)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            isHosting = false
            hideLoading()
            Toast.makeText(this, "Hosting Error: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun createAndSaveNode(x: Float, y: Float, z: Float, name: String?, type: String, cloudId: String) {        val nodeId = "node_" + System.currentTimeMillis()
        val neighbors = mutableListOf<String>()
        lastNodeId?.let { neighbors.add(it) }

        // --- THE ENGINE FIX ---
        // If we have synced to a cloud anchor, we adjust the incoming coordinates
        // to match the Cloud Anchor's coordinate space.
        var finalX = x
        var finalY = y
        var finalZ = z

        // This is only needed if you are mapping relative to a PREVIOUSLY resolved anchor
        // For a 10-week project, the simplest way is to always "Start Mapping"
        // at the entrance (0,0,0) and use that as the absolute master.

        val newNode = Node(
            id = nodeId,
            x = finalX, y = finalY, z = finalZ,
            neighborIds = neighbors,
            name = name?.ifEmpty { null },
            type = "WALKING",
            floorId = currentFloorId,
            cloudAnchorId = cloudId
        )

        saveNode(newNode)
    }

    private fun saveNode(newNode: Node) {
        // 1. Save the new node to Firebase first
        db.collection("maps").document(currentFloorId)
            .collection("nodes").document(newNode.id).set(newNode)
            .addOnSuccessListener {

                // 2. Logic to "Link" this node to the previous one
                if (lastNodeId != null) {
                    val prevDocRef = db.collection("maps").document(currentFloorId)
                        .collection("nodes").document(lastNodeId!!)

                    // Safety check: Make sure the previous node wasn't deleted manually in the console
                    prevDocRef.get().addOnSuccessListener { document ->
                        if (document.exists()) {
                            // Update the previous node to point to this new one
                            prevDocRef.update("neighborIds", FieldValue.arrayUnion(newNode.id))
                                .addOnSuccessListener {
                                    lastNodeId = newNode.id
                                    runOnUiThread {
                                        Toast.makeText(this, "Node Linked Successfully", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        } else {
                            // Previous node is missing from DB, start a new chain
                            lastNodeId = newNode.id
                            runOnUiThread {
                                Toast.makeText(this, "Chain broken. Starting new path from here.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    // This is the first node of the session
                    lastNodeId = newNode.id
                    runOnUiThread {
                        Toast.makeText(this, "First node of the floor saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    Toast.makeText(this, "Database Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
        // 1. Calculate path in background
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val pf = PathFinder()
            val path = pf.findPath(allNodes, start.id, end.id, false)

            runOnUiThread {
                if (path.isNotEmpty()) {
                    // 2. Set State Variables
                    navigationStartNode = start
                    currentPath = path // <--- Save the path for drift correction logic
                    isNavigating = true

                    // 3. Draw the dots
                    drawPathInAR(path, start)

                    Toast.makeText(this@MainActivity, "Path generated. Follow the dots!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Error: No path found in database.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun proceedToDrawPath() {
        // FIX: Pass both required parameters (Path and StartNode)
        if (finalPath != null && navigationStartNode != null) {
            drawPathInAR(finalPath!!, navigationStartNode!!)
        }
    }


    private fun drawPathInAR(path: List<Node>, startNode: Node) {
        currentPathIndex = 0
        val anchor = resolvingAnchor ?: run {
            runOnUiThread { Toast.makeText(this, "Rescan floor to align path.", Toast.LENGTH_SHORT).show() }
            return
        }

        clearPath()

        // 1. Create the physical reference point
        val worldAnchorNode = AnchorNode(sceneView.engine, anchor)
        sceneView.addChildNode(worldAnchorNode)
        placedPathNodes.add(worldAnchorNode)

        lifecycleScope.launch {
            // 2. Load models once (Use cached variables if available)
            val sphereInstance = sphereModel ?: sceneView.modelLoader.loadModelInstance("models/sphere.glb")
            val markerInstance = markerModelCached ?: sceneView.modelLoader.loadModelInstance("models/marker.glb")

            if (sphereInstance != null && markerInstance != null) {

                // --- MATH LOGIC START: Calculate breadcrumb points ---
                data class PathPoint(val x: Float, val y: Float, val z: Float)
                val pointsList = mutableListOf<PathPoint>()

                for (i in 0 until path.size - 1) {
                    val a = path[i]
                    val b = path[i + 1]

                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val dz = b.z - a.z
                    val distance = kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                    val interval = 0.5f // Dot every 50cm
                    val steps = (distance / interval).toInt().coerceAtLeast(1)

                    for (j in 0..steps) {
                        val t = j.toFloat() / steps
                        // Offset math relative to startNode
                        pointsList.add(PathPoint(
                            (a.x + t * dx) - startNode.x,
                            (a.y + t * dy) - startNode.y,
                            (a.z + t * dz) - startNode.z
                        ))
                    }
                }
                // --- MATH LOGIC END ---

                // 3. DRAW LOOP
                for (point in pointsList) {
                    val breadcrumb = ModelNode(
                        modelInstance = sphereInstance,
                        scaleToUnits = 0.03f, // 3cm size
                        autoAnimate = true
                    ).apply {
                        // Lift 5cm off floor to prevent flickering
                        position = Position(point.x, point.y + 0.05f, point.z)
                        isEditable = false
                    }
                    worldAnchorNode.addChildNode(breadcrumb)
                }

                // 4. DRAW DESTINATION PIN
                val lastNode = path.last()
                val destinationNode = ModelNode(
                    modelInstance = markerInstance,
                    scaleToUnits = 0.1f, // 10cm size
                    autoAnimate = true,
                    centerOrigin = Position(y = -0.5f) // Pin base to floor
                ).apply {
                    position = Position(
                        lastNode.x - startNode.x,
                        lastNode.y - startNode.y + 0.1f,
                        lastNode.z - startNode.z
                    )
                    isEditable = false
                }
                worldAnchorNode.addChildNode(destinationNode)

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Path drawn! Follow the trail.", Toast.LENGTH_SHORT).show()
                }
            }
            // ... at the end of the breadcrumb loops ...
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Path Drawn.", Toast.LENGTH_SHORT).show()
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
        // 1. Get ALL nodes we downloaded for this floor
        // We will fetch them directly from the database list to be safe
        db.collection("maps").document(currentFloorId).collection("nodes").get()
            .addOnSuccessListener { result ->
                val allNodes = result.toObjects(Node::class.java)

                // 2. Filter: Only nodes with a name that are NOT the one we are standing on
                val destinations = allNodes.filter {
                    !it.name.isNullOrEmpty() && it.id != detectedStartNode?.id
                }

                if (destinations.isEmpty()) {
                    runOnUiThread {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("No other destinations")
                            .setMessage("I found '$currentFloorId', but you haven't mapped any other named rooms on this floor yet.")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    return@addOnSuccessListener
                }

                // 3. Show the list
                val names = destinations.map { it.name!! }.toTypedArray()
                val currentLocName = detectedStartNode?.name ?: "Unknown"

                runOnUiThread {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Located: $currentLocName")
                        .setItems(names) { _, which ->
                            val targetNode = destinations[which]
                            startNavigation(detectedStartNode!!, targetNode, allNodes)
                        }
                        .setNegativeButton("Rescan") { _, _ ->
                            isSearchingForLocation = true
                            showLoading("Scanning...")
                        }
                        .show()
                }
            }
    }

    private fun showStartSelectionPicker() {
        val namedNodes = allDownloadedNodes.filter { !it.name.isNullOrEmpty() && !it.cloudAnchorId.isNullOrBlank() }

        if (namedNodes.isEmpty()) {
            Toast.makeText(this, "No anchors found to sync with.", Toast.LENGTH_LONG).show()
            return
        }

        val names = namedNodes.map { it.name!! }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Step 1: Where are you standing?")
            .setItems(names) { _, which ->
                val startNode = namedNodes[which]
                navigationStartNode = startNode

                // Start searching for THIS SPECIFIC spot
                isResolving = true
                showLoading("Scan the floor near ${startNode.name}...")

                val anchor = sceneView.session?.resolveCloudAnchor(startNode.cloudAnchorId!!)
                if (anchor != null) {
                    resolvingAnchor = anchor
                }
            }
            .setCancelable(false)
            .show()
    }

    private fun setupMode() {
        val mode = intent.getStringExtra("mode")
        isUserMode = (mode == "USER")

        sceneView.onSessionCreated = { _ ->
            // Fetch data for the CURRENT floor
            db.collection("maps").document(currentFloorId).collection("nodes").get()
                .addOnSuccessListener { result ->
                    allDownloadedNodes = result.toObjects(Node::class.java)

                    if (allDownloadedNodes.isEmpty()) {
                        hideLoading()
                        if (!isUserMode) runOnUiThread { instructionText.text = "New Floor: Tap to start mapping." }
                    } else {
                        if (isUserMode) {
                            // USER MODE: Ask "Where are you?" immediately
                            runOnUiThread { showStartSelectionPicker() }
                        } else {
                            // ADMIN MODE: Resolve existing anchors to sync the world
                            allDownloadedNodes.forEach { node ->
                                node.cloudAnchorId?.let { id ->
                                    cloudAnchorMap[id] = node
                                    val anchor = sceneView.session?.resolveCloudAnchor(id)
                                    if (anchor != null) activeCloudAnchors[id] = anchor
                                }
                            }
                            loadExistingMap()
                            isSearchingForLocation = true
                            showLoading("Scanning to sync with $currentFloorId...")
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
                val selectedFloor = floors[pos]
                if (currentFloorId != selectedFloor) {
                    currentFloorId = selectedFloor
                    // RESET STATE FOR NEW FLOOR
                    lastNodeId = null
                    cloudAnchorMap.clear()
                    activeCloudAnchors.values.forEach { it.detach() }
                    activeCloudAnchors.clear()
                    allDownloadedNodes = emptyList()
                    clearPath()

                    // RE-RUN SETUP FOR THE NEW FLOOR
                    setupMode()
                    Toast.makeText(this@MainActivity, "Switched to $currentFloorId", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateStatusMessage() {
        runOnUiThread {
            val count = cloudAnchorMap.size
            when {
                isNavigating -> {
                    instructionText.text = "Navigation Active. Follow the path."
                }
                isSearchingForLocation -> {
                    if (count == 0) {
                        instructionText.text = "Map empty. Switch to Admin mode to add nodes."
                    } else {
                        instructionText.text = "Scanning for $count known points..."
                    }
                }
                isHosting -> {
                    instructionText.text = "Saving location to Cloud..."
                }
                isResolving -> {
                    instructionText.text = "Calculating precise path position..."
                }
                else -> {
                    instructionText.text = "System Ready."
                }
            }
        }
    }

    private fun onLocationSynced() {
        val nodeName = detectedStartNode?.name ?: "Mapped Path"
        isSearchingForLocation = false

        // ALL UI code must be inside this block
        runOnUiThread {
            hideLoading()
            if (isUserMode) {
                Toast.makeText(this@MainActivity, "Located: $nodeName", Toast.LENGTH_SHORT).show()
                showDestinationPickerOnly()
            } else {
                lastNodeId = detectedStartNode?.id
                Toast.makeText(this@MainActivity, "Map aligned to $nodeName", Toast.LENGTH_SHORT).show()
                loadExistingMap()
            }
        }
    }

    private fun preloadModels() {
        lifecycleScope.launch {
            sphereModel = sceneView.modelLoader.loadModelInstance("models/sphere.glb")
            markerModelCached = sceneView.modelLoader.loadModelInstance("models/marker.glb")
        }
    }

    private fun calculateDistanceToNode(cameraPose: com.google.ar.core.Pose, node: Node, startNode: Node): Float {
        // We must use the same "World Shift Math" we used to draw the path
        val nodeRelX = node.x - startNode.x
        val nodeRelY = node.y - startNode.y
        val nodeRelZ = node.z - startNode.z

        return Math.sqrt(
            Math.pow((cameraPose.tx() - nodeRelX).toDouble(), 2.0) +
                    Math.pow((cameraPose.ty() - nodeRelY).toDouble(), 2.0) +
                    Math.pow((cameraPose.tz() - nodeRelZ).toDouble(), 2.0)
        ).toFloat()
    }

    private fun checkArrivalAtLift(path: List<Node>, startNode: Node) {
        // Only check if we are currently navigating
        if (!isNavigating) return

        val cameraPose = latestFrame?.camera?.pose ?: return

        // Look for the "LIFT" node in our current path
        val liftNode = path.find { it.type == "LIFT" || it.name?.contains("Lift", true) == true } ?: return

        val distance = calculateDistanceToNode(cameraPose, liftNode, startNode)

        // If within 1.5 meters of the lift
        if (distance < 1.5f) {
            // Stop navigation logic so the popup doesn't keep appearing
            isNavigating = false

            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Change Floor")
                    .setMessage("You have reached the lift. Please go to the next floor and press 'Arrived' to resync.")
                    .setCancelable(false)
                    .setPositiveButton("Arrived") { _, _ ->
                        // This resets the app to scanning mode for the new floor
                        // (You might need to manually set currentFloorId here)
                        setupMode()
                    }
                    .show()
            }
        }
    }

    private fun getDistanceToNode(cameraPose: com.google.ar.core.Pose, targetNode: Node, startNode: Node): Float {
        // Calculate the target node's position in the current AR world space
        val worldTargetX = targetNode.x - startNode.x
        val worldTargetY = targetNode.y - startNode.y
        val worldTargetZ = targetNode.z - startNode.z

        val dx = cameraPose.tx() - worldTargetX
        val dy = cameraPose.ty() - worldTargetY
        val dz = cameraPose.tz() - worldTargetZ

        return Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun updateLiveInstructions() {
        val path = finalPath ?: return
        val startNode = navigationStartNode ?: return
        val cameraPose = latestFrame?.camera?.pose ?: return

        if (currentPathIndex >= path.size) {
            runOnUiThread { instructionText.text = "You have arrived at your destination!" }
            return
        }

        val targetNode = path[currentPathIndex]
        val distance = getDistanceToNode(cameraPose, targetNode, startNode)

        // 1. Check if user reached the current breadcrumb (within 0.8 meters)
        if (distance < 0.8f) {
            currentPathIndex++ // Move to next dot
            // Remove the dot from the floor as the user passes it (Optional visual polish)
            if (placedPathNodes.isNotEmpty() && currentPathIndex < placedPathNodes.size) {
                // sceneView.removeChildNode(placedPathNodes[currentPathIndex - 1])
            }
        }

        // 2. Logic for text instructions
        runOnUiThread {
            val remainingDistance = getDistanceToNode(cameraPose, path.last(), startNode)

            when {
                targetNode.type == "LIFT" -> {
                    instructionText.text = "Enter the Lift (Distance: ${String.format("%.1f", distance)}m)"
                }
                targetNode.type == "STAIRS" -> {
                    instructionText.text = "Climb stairs carefully (Distance: ${String.format("%.1f", distance)}m)"
                }
                currentPathIndex == path.size - 1 -> {
                    instructionText.text = "Arriving at ${targetNode.name} in ${String.format("%.1f", distance)}m"
                }
                else -> {
                    instructionText.text = "Follow path: ${String.format("%.1f", remainingDistance)}m to go"
                }
            }
        }
    }


    private fun updateProximityAnchors(session: com.google.ar.core.Session, cameraPose: com.google.ar.core.Pose) {
        // 1. Filter nodes that actually have a Cloud ID and aren't empty
        val validNodes = allDownloadedNodes.filter {
            !it.cloudAnchorId.isNullOrBlank()
        }

        // 2. Calculate distances
        val sortedNodes = validNodes.map { node ->
            val dx = cameraPose.tx() - node.x
            val dy = cameraPose.ty() - node.y
            val dz = cameraPose.tz() - node.z
            val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
            node to dist
        }.sortedBy { it.second }

        // 3. Take the closest 20 (Let's start smaller than 30 to be safe)
        val closestIds = sortedNodes.take(20).mapNotNull { it.first.cloudAnchorId }.toSet()

        // 4. Detach anchors that are no longer nearby
        val iterator = activeCloudAnchors.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!closestIds.contains(entry.key)) {
                entry.value.detach()
                iterator.remove()
            }
        }

        // 5. CRITICAL FIX: Only resolve if NOT already in the map
        closestIds.forEach { cloudId ->
            // Check if we are already tracking OR already resolving this ID
            val isAlreadyTracking = activeCloudAnchors.containsKey(cloudId)

            if (!isAlreadyTracking) {
                try {
                    val newAnchor = session.resolveCloudAnchor(cloudId)
                    if (newAnchor != null) {
                        activeCloudAnchors[cloudId] = newAnchor
                    }
                } catch (e: Exception) {
                    // Network or queue busy
                }
            }
        }
    }





}