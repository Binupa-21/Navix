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
        sceneView.configureSession { _, config ->
            config.cloudAnchorMode = com.google.ar.core.Config.CloudAnchorMode.ENABLED
            config.focusMode = com.google.ar.core.Config.FocusMode.AUTO
        }

        // 5. MAIN UPDATE LOOP (Checking Cloud States & UI)
        sceneView.onSessionUpdated = { session, frame ->
            latestFrame = frame

            // A. AUTO-LOCATION DETECTION (Admin or User Sync)
            if (isSearchingForLocation) {
                val allAnchors = session.allAnchors
                for (anchor in allAnchors) {
                    if (anchor.cloudAnchorState == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                        val cloudId = anchor.cloudAnchorId
                        if (cloudAnchorMap.containsKey(cloudId)) {
                            // MATCH FOUND!
                            detectedStartNode = cloudAnchorMap[cloudId]

                            // CRITICAL: You must save the anchor that Google just found!
                            resolvingAnchor = anchor

                            isSearchingForLocation = false
                            runOnUiThread {
                                hideLoading()
                                onLocationSynced() // This will now trigger the picker
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
                    val cloudId = pendingAnchor!!.cloudAnchorId
                    val pose = pendingAnchor!!.pose
                    isHosting = false

                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Cloud Sync Successful!", Toast.LENGTH_SHORT).show()
                        showNameDialog(pose.tx(), pose.ty(), pose.tz(), cloudId)
                    }
                    pendingAnchor = null
                } else if (state.isError) {
                    isHosting = false
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Hosting Failed: $state", Toast.LENGTH_LONG).show()
                    }
                    pendingAnchor = null
                }
            }

            // C. CHECK RESOLVING (Specific Navigation Start)
            if (isResolving && resolvingAnchor != null) {
                val state = resolvingAnchor!!.cloudAnchorState
                if (state == com.google.ar.core.Anchor.CloudAnchorState.SUCCESS) {
                    isResolving = false
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Path Origin Locked!", Toast.LENGTH_SHORT).show()
                        proceedToDrawPath()
                    }
                } else if (state.isError) {
                    isResolving = false
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Sync Failed. Rescanning...", Toast.LENGTH_LONG).show()
                        isSearchingForLocation = true // Fallback to auto-scan
                    }
                }
            }

            // D. UPDATE THE TOP INSTRUCTION TEXT
            updateStatusMessage()
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
        val localAnchor = hitResult.createAnchor()
        val anchorNode = AnchorNode(sceneView.engine, localAnchor)

        lifecycleScope.launch {
            sceneView.modelLoader.loadModelInstance("models/marker.glb")?.let {
                val markerModelNode = ModelNode(
                    modelInstance = it,
                    autoAnimate = true,           // Must be Boolean
                    scaleToUnits = 0.2f,          // Must be Float
                    centerOrigin = Position(y = -0.5f) // Must be Position
                )
                anchorNode.addChildNode(markerModelNode)
                sceneView.addChildNode(anchorNode)

                // TRACKING: Add to our unified list for clearing
                placedPathNodes.add(anchorNode)
            }
        }

        showLoading("Uploading spatial map to Google Cloud Brain...")
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
        // 1. Safety Check: We MUST have a physical origin anchor
        val anchor = resolvingAnchor ?: run {
            runOnUiThread { Toast.makeText(this, "Rescan the floor to align path.", Toast.LENGTH_SHORT).show() }
            return
        }

        // 2. Clear old visuals
        clearPath()

        // 3. Create the Physical "Hook" at your feet (the resolved anchor)
        val worldAnchorNode = AnchorNode(sceneView.engine, anchor)
        sceneView.addChildNode(worldAnchorNode)
        placedPathNodes.add(worldAnchorNode)

        lifecycleScope.launch {
            // 4. DRAW THE DESTINATION PIN FIRST (Load once)
            val markerInstance = sceneView.modelLoader.loadModelInstance("models/marker.glb")
            if (markerInstance != null) {
                val lastNode = path.last()
                val destinationMarker = ModelNode(
                    modelInstance = markerInstance,
                    scaleToUnits = 0.25f,
                    centerOrigin = Position(y = -0.5f)
                ).apply {
                    position = Position(
                        lastNode.x - startNode.x,
                        lastNode.y - startNode.y, // Removed +0.1f offset to sit on floor
                        lastNode.z - startNode.z
                    )
                    isEditable = false
                }
                worldAnchorNode.addChildNode(destinationMarker)
            }

            // 5. DRAW THE TRAIL (The Breadcrumbs)
            for (i in 0 until path.size - 1) {
                val nodeA = path[i]
                val nodeB = path[i + 1]

                val dx = nodeB.x - nodeA.x
                val dy = nodeB.y - nodeA.y
                val dz = nodeB.z - nodeA.z
                val distance = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

                // Draw a dot every 50cm
                val interval = 0.5f
                val pointsCount = (distance / interval).toInt().coerceAtLeast(1)

                for (j in 0..pointsCount) {
                    val t = j.toFloat() / pointsCount.toFloat()

                    val relX = (nodeA.x + t * dx) - startNode.x
                    val relY = (nodeA.y + t * dy) - startNode.y
                    val relZ = (nodeA.z + t * dz) - startNode.z

                    // --- CRITICAL FIX START ---
                    // We must load a NEW instance for every single dot.
                    // SceneView caches the file in memory, so this is fast.
                    val sphereInstance = sceneView.modelLoader.loadModelInstance("models/sphere.glb")

                    if (sphereInstance != null) {
                        val breadcrumb = ModelNode(
                            modelInstance = sphereInstance,
                            scaleToUnits = 0.05f,
                            centerOrigin = Position(y = 0f)
                        ).apply {
                            // Raise 5cm off floor to prevent flickering
                            position = Position(relX, relY + 0.05f, relZ)
                            isEditable = false
                        }
                        worldAnchorNode.addChildNode(breadcrumb)
                    }
                    // --- CRITICAL FIX END ---
                }
            }

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

    private fun setupMode() {
        val mode = intent.getStringExtra("mode")
        isUserMode = (mode == "USER")

        // 1. Reset State
        lastNodeId = null
        cloudAnchorMap.clear()
        clearPath()

        // Default behavior: Start by searching for a physical sync point
        isSearchingForLocation = true
        showLoading("Syncing with Cloud...")

        // 2. Wait for AR Session
        sceneView.onSessionCreated = { _ ->

            // 3. Fetch data from Firebase
            db.collection("maps").document(currentFloorId).collection("nodes").get()
                .addOnSuccessListener { result ->
                    val allNodes = result.toObjects(Node::class.java)

                    if (allNodes.isEmpty()) {
                        // --- CASE A: BRAND NEW FLOOR ---
                        hideLoading()

                        if (!isUserMode) {
                            // ADMIN: Unlock immediately and give specific instruction
                            isSearchingForLocation = false
                            runOnUiThread {
                                instructionText.text = "New floor detected. Tap to place the first anchor."
                                Toast.makeText(this, "Empty Floor: Start mapping anywhere.", Toast.LENGTH_LONG).show()
                            }
                        } else {
                            // USER: Cannot navigate on an empty floor
                            runOnUiThread {
                                instructionText.text = "Error: No map data exists for this floor."
                                Toast.makeText(this, "Please ask an admin to map this area.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        // --- CASE B: EXISTING MAP ---
                        runOnUiThread {
                            Toast.makeText(this, "Map Loaded. Scan environment to align.", Toast.LENGTH_SHORT).show()
                        }

                        // Register all Cloud IDs for the AR engine to look for
                        allNodes.forEach { node ->
                            node.cloudAnchorId?.let { cloudId ->
                                cloudAnchorMap[cloudId] = node
                                sceneView.session?.resolveCloudAnchor(cloudId)
                            }
                        }

                        // If Admin, show existing markers so they see the current layout
                        if (!isUserMode) {
                            loadExistingMap()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    hideLoading()
                    isSearchingForLocation = false
                    runOnUiThread {
                        instructionText.text = "Connection Error."
                        Toast.makeText(this, "Firebase Error: ${e.message}", Toast.LENGTH_SHORT).show()
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

        if (isUserMode) {
            // STOP the scanning logic
            isSearchingForLocation = false

            runOnUiThread {
                hideLoading()
                // Don't just show a toast, show the destination picker immediately!
                showDestinationPickerOnly()
            }
        } else {
            // Admin mode logic remains the same
            lastNodeId = detectedStartNode?.id
            runOnUiThread {
                hideLoading()
                Toast.makeText(this, "Map aligned to $nodeName", Toast.LENGTH_SHORT).show()
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





}