package com.navix.app.ui

import com.navix.app.R
import com.navix.app.data.Node
import com.navix.app.navigation.PathFinder

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.exceptions.ResourceExhaustedException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.*

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
    private var pendingFloorId: String? = null

    // Cloud Anchor resolving can easily exhaust ARCore resources if we try to resolve too many at once.
    private val resolvedCloudAnchorIds = mutableSetOf<String>()
    private var cloudResolveJob: Job? = null

    private lateinit var instructionText: TextView
    private lateinit var distanceText: TextView
    private lateinit var instructionIcon: ImageView
    private lateinit var instructionCard: MaterialCardView

    // Inside MainActivity class
    private var currentFloorId = "floor_1" // Default

    // --- Navigation draw control ---
    private var pendingDrawPath = false

    // --- Cached models to avoid load race ---
    private var sphereModel: io.github.sceneview.model.ModelInstance? = null
    private var markerModelCached: io.github.sceneview.model.ModelInstance? = null


    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var statusText: TextView

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
        distanceText = findViewById(R.id.distanceText)
        instructionIcon = findViewById(R.id.instructionIcon)
        instructionCard = findViewById(R.id.instructionCard)

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
                    val floorIdForNode = pendingFloorId ?: currentFloorId
                    isHosting = false

                    // Add to active tracking
                    activeCloudAnchors[cloudId] = pendingAnchor!!

                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Cloud Sync Successful!", Toast.LENGTH_SHORT).show()
                        showNameDialog(pose.tx(), pose.ty(), pose.tz(), cloudId, floorIdForNode)
                    }
                    pendingAnchor = null
                    pendingFloorId = null
                } else if (state.isError) {
                    isHosting = false
                    val error = state.name
                    runOnUiThread {
                        hideLoading()
                        Toast.makeText(this, "Hosting Failed: $error", Toast.LENGTH_LONG).show()
                    }
                    pendingAnchor?.detach()
                    pendingAnchor = null
                    pendingFloorId = null
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

            if (isNavigating && currentPath != null && navigationStartNode != null) {
                checkArrivalAtLift(currentPath!!, navigationStartNode!!)
            }

            // D. UPDATE THE TOP INSTRUCTION TEXT
            updateStatusMessage()

            if (isNavigating && currentPath != null && !isSearchingForLocation) {

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
        // Capture the floor at the moment the user places the node.
        // This prevents accidental saves to a different floor if the spinner is changed mid-hosting.
        pendingFloorId = currentFloorId

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

    private fun showNameDialog(
        x: Float,
        y: Float,
        z: Float,
        cloudId: String,
        floorIdForNode: String
    ) {
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
                createAndSaveNode(x, y, z, finalName, type, cloudId, floorIdForNode)

            }
            .setNegativeButton("Cancel", null) // Note: Visual node stays, simple limitation
            .show()
    }

    private fun createAndSaveNode(
        x: Float,
        y: Float,
        z: Float,
        name: String?,
        type: String,
        cloudId: String,
        floorIdForNode: String
    ) {
        val nodeId = "node_" + System.currentTimeMillis()
        val neighbors = mutableListOf<String>()
        lastNodeId?.let { neighbors.add(it) }

        // --- THE ENGINE FIX ---
        // If we have synced to a cloud anchor, we adjust the incoming coordinates
        // to match the Cloud Anchor's coordinate space.
        val finalX = x
        val finalY = y
        val finalZ = z

        // This is only needed if you are mapping relative to a PREVIOUSLY resolved anchor
        // For a 10-week project, the simplest way is to always "Start Mapping"
        // at the entrance (0,0,0) and use that as the absolute master.

        val newNode = Node(
            id = nodeId,
            x = finalX, y = finalY, z = finalZ,
            neighborIds = neighbors,
            name = name?.ifEmpty { null },
            type = type,
            floorId = floorIdForNode,
            cloudAnchorId = cloudId
        )

        saveNode(newNode)
    }

    private fun saveNode(newNode: Node) {
        // 1. Save the new node to Firebase first
        db.collection("maps").document(newNode.floorId)
            .collection("nodes").document(newNode.id).set(newNode)
            .addOnSuccessListener {

                // 2. Logic to "Link" this node to the previous one
                if (lastNodeId != null) {
                    val prevDocRef = db.collection("maps").document(newNode.floorId)
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
        if (currentPath != null && navigationStartNode != null) {
            drawPathInAR(currentPath!!, navigationStartNode!!)
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

            // 5. DRAW THE TRAIL (The Breadcrumbs)
            for (i in 0 until path.size - 1) {
                val nodeA = path[i]
                val nodeB = path[i + 1]

                val dx = nodeB.x - nodeA.x
                val dy = nodeB.y - nodeA.y
                val dz = nodeB.z - nodeA.z
                val distance = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

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
    private fun loadExistingMap(floorId: String = currentFloorId) {
        Toast.makeText(this, "Loading map for $floorId...", Toast.LENGTH_SHORT).show()
        val expectedFloorId = floorId

        db.collection("maps").document(floorId)
            .collection("nodes").get()
            .addOnSuccessListener { result ->
                // If the user switched floors while this request was in-flight, ignore this response.
                if (expectedFloorId != currentFloorId) return@addOnSuccessListener

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
                                    showDeleteVirtualNodeDialog(node.id, markerNode, floorId)
                                }
                                true
                            }

                            sceneView.addChildNode(markerNode)
                            // Track these "virtual" nodes so clearPath() removes them when changing floors.
                            placedPathNodes.add(markerNode)
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
    private fun showDeleteVirtualNodeDialog(nodeId: String, nodeToDelete: ModelNode, floorId: String = currentFloorId) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Node?")
            .setMessage("Remove this saved node?")
            .setPositiveButton("Delete") { _, _ ->
                db.collection("maps").document(floorId)
                    .collection("nodes").document(nodeId)
                    .delete()
                    .addOnSuccessListener {
                        sceneView.removeChildNode(nodeToDelete)
                        placedPathNodes.remove(nodeToDelete)
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

        // 1. Reset State
        lastNodeId = null
        cloudAnchorMap.clear()
        cloudResolveJob?.cancel()
        cloudResolveJob = null
        resolvedCloudAnchorIds.clear()
        clearPath()

        // Default behavior: Start by searching for a physical sync point
        isSearchingForLocation = true
        showLoading("Syncing with Cloud...")

        if (isUserMode) {
            showUserInstructions()
        }

        // 2. Fetch and resolve Cloud Anchors for the selected floor.
        // `onSessionCreated` may only fire once, so we call this immediately and also when the session is created.
        sceneView.onSessionCreated = { _ ->
            fetchAndResolveForFloor(currentFloorId)
        }
        fetchAndResolveForFloor(currentFloorId)
    }

    private fun fetchAndResolveForFloor(floorId: String) {
        val requestedFloorId = floorId

        db.collection("maps").document(floorId).collection("nodes").get()
            .addOnSuccessListener { result ->
                // Ignore stale responses if the user switched floors while this request was in-flight.
                if (requestedFloorId != currentFloorId) return@addOnSuccessListener

                val allNodes = result.toObjects(Node::class.java)

                if (allNodes.isEmpty()) {
                    // --- CASE A: BRAND NEW FLOOR ---
                    hideLoading()

                    if (!isUserMode) {
                        // ADMIN: Unlock immediately and give specific instruction
                        isSearchingForLocation = false
                        runOnUiThread {
                            instructionCard.visibility = android.view.View.VISIBLE
                            instructionText.text = "New floor detected. Tap to place the first anchor."
                            Toast.makeText(this, "Empty Floor: Start mapping anywhere.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // USER: Cannot navigate on an empty floor
                        runOnUiThread {
                            instructionCard.visibility = android.view.View.VISIBLE
                            instructionText.text = "Error: No map data exists for this floor."
                            Toast.makeText(this, "Please ask an admin to map this area.", Toast.LENGTH_LONG).show()
                        }
                    }
                    return@addOnSuccessListener
                }

                // --- CASE B: EXISTING MAP ---
                runOnUiThread {
                    Toast.makeText(this, "Map Loaded. Scan environment to align.", Toast.LENGTH_SHORT).show()
                }

                // Register all Cloud IDs for the AR engine to look for.
                // IMPORTANT: Do NOT resolve them all at once; ARCore throws ResourceExhaustedException.
                val cloudIdsToResolve = allNodes
                    .mapNotNull { it.cloudAnchorId }
                    .distinct()

                // Update our lookup map so onSessionUpdated can match resolved anchors.
                cloudAnchorMap.clear()
                cloudIdsToResolve.forEach { cloudId ->
                    allNodes.firstOrNull { it.cloudAnchorId == cloudId }?.let { node ->
                        cloudAnchorMap[cloudId] = node
                    }
                }

                // Resolve in a small, rate-limited loop to avoid ARCore resource exhaustion.
                cloudResolveJob?.cancel()
                cloudResolveJob = lifecycleScope.launch(Dispatchers.Main) {
                    val maxToResolve = 30 // cap to keep ARCore stable; adjust if needed
                    val ids = cloudIdsToResolve.take(maxToResolve)

                    for (cloudId in ids) {
                        if (!isActive) break
                        if (resolvedCloudAnchorIds.contains(cloudId)) continue

                        try {
                            val session = sceneView.session
                            if (session == null) break
                            session.resolveCloudAnchor(cloudId)
                            resolvedCloudAnchorIds.add(cloudId)
                        } catch (e: ResourceExhaustedException) {
                            isSearchingForLocation = false
                            hideLoading()
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Cloud sync is busy. Try rescanning.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            break
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Cloud anchor resolve error: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        delay(400)
                    }
                }
            }
            .addOnFailureListener { e ->
                if (requestedFloorId != currentFloorId) return@addOnFailureListener

                hideLoading()
                isSearchingForLocation = false
                runOnUiThread {
                    instructionCard.visibility = android.view.View.VISIBLE
                    instructionText.text = "Connection Error."
                    Toast.makeText(this, "Firebase Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun showUserInstructions() {
        runOnUiThread {
            val dialogView = layoutInflater.inflate(R.layout.dialog_user_instructions, null)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            // Make background transparent so our custom gradient corners show
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<Button>(R.id.btnGotIt).setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()

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
            instructionCard.visibility = android.view.View.VISIBLE
            distanceText.visibility = android.view.View.GONE
            instructionIcon.setImageResource(android.R.drawable.ic_menu_directions)

            when {
                isNavigating -> {
                    distanceText.visibility = android.view.View.VISIBLE
                }
                isSearchingForLocation -> {
                    if (count == 0) {
                        instructionText.text = "Map empty. Switch to Admin mode."
                    } else {
                        if (isUserMode) {
                            instructionText.text = "Point camera at surroundings to sync."
                        } else {
                            instructionText.text = "Scanning for $count points..."
                        }
                    }
                }
                isHosting -> {
                    instructionText.text = "Saving location..."
                }
                isResolving -> {
                    instructionText.text = "Calculating path..."
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

        return sqrt(
            (cameraPose.tx() - nodeRelX).toDouble().pow(2.0) +
                    (cameraPose.ty() - nodeRelY).toDouble().pow(2.0) +
                    (cameraPose.tz() - nodeRelZ).toDouble().pow(2.0)
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

        return sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
    }

    private fun updateLiveInstructions() {
        val path = currentPath ?: return
        val startNode = navigationStartNode ?: return
        val cameraPose = latestFrame?.camera?.pose ?: return

        // 1. Check if arrived at final destination
        if (currentPathIndex >= path.size - 1) {
            val finalDistance = getDistanceToNode(cameraPose, path.last(), startNode)
            if (finalDistance < 0.8f) {
                runOnUiThread {
                    instructionText.text = "Arrived!"
                    distanceText.text = "0.0m to destination"
                    instructionIcon.setImageResource(android.R.drawable.checkbox_on_background)
                    instructionIcon.rotation = 0f
                }
                return
            }
        }

        // 2. Advance waypoint if we are close to current target
        var targetNode = path[currentPathIndex]
        var distanceToTarget = getDistanceToNode(cameraPose, targetNode, startNode)

        while (distanceToTarget < 1.2f && currentPathIndex < path.size - 1) {
            currentPathIndex++
            targetNode = path[currentPathIndex]
            distanceToTarget = getDistanceToNode(cameraPose, targetNode, startNode)
        }

        // 3. Logic for directions based on camera facing vs target direction
        runOnUiThread {
            val totalRemaining = getDistanceToNode(cameraPose, path.last(), startNode)
            distanceText.text = "${String.format("%.1f", totalRemaining)}m to destination"

            // Get Camera Forward Vector (ARCore: -Z is forward in local space)
            val cameraZ = cameraPose.zAxis
            val forwardX = -cameraZ[0]
            val forwardZ = -cameraZ[2]
            val cameraYaw = atan2(forwardZ.toDouble(), forwardX.toDouble())

            // Get Vector from Camera to Target Node
            val toTargetX = (targetNode.x - startNode.x) - cameraPose.tx()
            val toTargetZ = (targetNode.z - startNode.z) - cameraPose.tz()
            val targetYaw = atan2(toTargetZ.toDouble(), toTargetX.toDouble())

            // Calculate relative angle (-180 to 180)
            var relativeAngle = Math.toDegrees(targetYaw - cameraYaw).toFloat()
            while (relativeAngle > 180) relativeAngle -= 360f
            while (relativeAngle < -180) relativeAngle += 360f

            when {
                targetNode.type == "LIFT" && distanceToTarget < 2.0f -> {
                    instructionText.text = "Enter Lift"
                    instructionIcon.setImageResource(android.R.drawable.ic_menu_directions)
                    instructionIcon.rotation = 0f
                }
                relativeAngle > 45f && relativeAngle < 135f -> {
                    instructionText.text = "Turn Right"
                    instructionIcon.setImageResource(R.drawable.ic_turn_right)
                    instructionIcon.rotation = 0f
                }
                relativeAngle < -45f && relativeAngle > -135f -> {
                    instructionText.text = "Turn Left"
                    instructionIcon.setImageResource(R.drawable.ic_turn_left)
                    instructionIcon.rotation = 0f
                }
                abs(relativeAngle) >= 135f -> {
                    instructionText.text = "Turn Around"
                    instructionIcon.setImageResource(android.R.drawable.ic_menu_revert)
                    instructionIcon.rotation = 180f
                }
                else -> {
                    instructionText.text = "Go Forward"
                    instructionIcon.setImageResource(android.R.drawable.ic_menu_directions)
                    instructionIcon.rotation = 0f
                }
            }
        }
    }
}