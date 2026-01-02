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
    lateinit var modelNode: ArModelNode // The Master Model

    val db = FirebaseFirestore.getInstance()

    private var lastNodeId: String? = null
    private var isModelLoaded = false
    private var isUserMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)

        // 1. CHECK MODE (Admin vs User)
        val mode = intent.getStringExtra("mode")
        if (mode == "USER") {
            isUserMode = true
            Toast.makeText(this, "User Mode", Toast.LENGTH_SHORT).show()

            // Show picker after 2 seconds
            sceneView.postDelayed({
                showDestinationPicker()
            }, 2000)
        } else {
            Toast.makeText(this, "Admin Mode: Tap to Map", Toast.LENGTH_SHORT).show()
        }

        // 2. SETUP THE MASTER MODEL (Marker)
        modelNode = ArModelNode(sceneView.engine).apply {
            scale = Position(0.5f, 0.5f, 0.5f)

            // FIX: Pointing to the "models" folder
            loadModelGlbAsync(
                glbFileLocation = "models/marker.glb",
                centerOrigin = Position(y = -0.5f),
                onLoaded = {
                    isModelLoaded = true
                    // Only show this toast in Admin mode to avoid clutter
                    if (!isUserMode) {
                        Toast.makeText(this@MainActivity, "Marker Loaded!", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { e ->
                    Toast.makeText(this@MainActivity, "Error loading marker: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }

        // 3. HANDLE TAPS
        sceneView.onTapAr = { hitResult, _ ->
            // We only allow placing points if we are in ADMIN mode
            if (!isUserMode) {
                if (isModelLoaded) {
                    // A. Create Anchor
                    val anchor = hitResult.createAnchor()

                    // B. Visuals
                    val visualNode = modelNode.clone()
                    visualNode.anchor = anchor
                    sceneView.addChild(visualNode)

                    // C. Data
                    // C. Data
                    val pose = anchor.pose
                    val x = pose.tx()
                    val y = pose.ty()
                    val z = pose.tz()

                    // CALL THE DIALOG instead of saving immediately
                    showNameDialog(x, y, z)

                    // D. Logic

                } else {
                    Toast.makeText(this@MainActivity, "Loading Model...", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Function to show a popup asking for a name
    private fun showNameDialog(x: Float, y: Float, z: Float) {
        val input = android.widget.EditText(this)
        input.hint = "Name (e.g. Lab 1) or leave empty"

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Add Node")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()

                // If text is empty, it's just a hallway (null name)
                // If text is "stairs", mark type as STAIRS
                val nodeName = if (name.isEmpty()) null else name
                val nodeType = if (name.equals("stairs", ignoreCase = true)) "STAIRS" else "WALKING"

                createAndSaveNode(x, y, z, nodeName, nodeType)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Move the node creation logic here
    private fun createAndSaveNode(x: Float, y: Float, z: Float, name: String?, type: String) {
        val nodeId = "node_" + System.currentTimeMillis()
        val currentNeighbors = mutableListOf<String>()
        if (lastNodeId != null) {
            currentNeighbors.add(lastNodeId!!)
        }

        val newNode = Node(
            id = nodeId, x = x, y = y, z = z,
            neighborIds = currentNeighbors,
            name = name,
            type = type
        )

        chainAndUpdateNode(newNode)
        Toast.makeText(this, "Saved: ${name ?: "Path"}", Toast.LENGTH_SHORT).show()
    }

    private fun chainAndUpdateNode(newNode: Node) {
        db.collection("maps").document("floor_1")
            .collection("nodes").document(newNode.id)
            .set(newNode)

        if (lastNodeId != null) {
            db.collection("maps").document("floor_1")
                .collection("nodes").document(lastNodeId!!)
                .update("neighborIds", FieldValue.arrayUnion(newNode.id))
        }
        lastNodeId = newNode.id
    }

    private fun showDestinationPicker() {
        Toast.makeText(this, "Loading Destinations...", Toast.LENGTH_SHORT).show()

        // 1. Get ALL nodes
        db.collection("maps").document("floor_1").collection("nodes")
            .get()
            .addOnSuccessListener { result ->
                val allNodes = result.toObjects(Node::class.java)

                // 2. Filter only nodes that have a Name
                val destinations = allNodes.filter { it.name != null && it.name!!.isNotEmpty() }

                if (destinations.isEmpty()) {
                    Toast.makeText(this, "No named destinations found!", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                // 3. Show list in a Popup
                val names = destinations.map { it.name!! }.toTypedArray()

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Where do you want to go?")
                    .setItems(names) { _, which ->
                        // User clicked an item
                        val targetNode = destinations[which]
                        val startNode = allNodes.first() // ASSUMPTION: User is at the entrance (First node mapped)

                        startNavigation(startNode, targetNode, allNodes)
                    }
                    .show()
            }
    }

    private fun startNavigation(start: Node, end: Node, allNodes: List<Node>) {
        val pf = PathFinder()
        // You can hardcode false for now, or add a toggle switch in the UI later
        val path = pf.findPath(allNodes, start.id, end.id, isWheelchair = false)
        if (path.isNotEmpty()) {
            drawPathInAR(path)
        } else {
            Toast.makeText(this, "No path found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawPathInAR(path: List<com.navix.app.Node>) {
        for (node in path) {
            val pathDot = ArModelNode(sceneView.engine).apply {
                scale = Position(0.1f, 0.1f, 0.1f)

                // FIX: Pointing to the "models" folder
                loadModelGlbAsync(
                    glbFileLocation = "models/sphere.glb",
                    centerOrigin = Position(y = 0.0f),
                    onLoaded = {}
                )
                position = Position(node.x, node.y, node.z)
            }
            sceneView.addChild(pathDot)
        }
        Toast.makeText(this, "Follow the path!", Toast.LENGTH_LONG).show()
    }
}