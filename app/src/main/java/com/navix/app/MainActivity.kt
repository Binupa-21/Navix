package com.navix.app

// Ensure this imports YOUR data class, not the library's Node class
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

        // CHECK MODE
        val mode = intent.getStringExtra("mode")
        if (mode == "USER") {
            isUserMode = true
            Toast.makeText(this, "User Mode: Navigation Only", Toast.LENGTH_SHORT).show()

            // If User Mode, run navigation immediately (or wait for a UI selection)
            sceneView.postDelayed({ testNavigation() }, 3000)
        } else {
            Toast.makeText(this, "Admin Mode: Tap to Map", Toast.LENGTH_SHORT).show()
        }

        sceneView = findViewById(R.id.sceneView)

        // 1. SETUP THE MASTER MODEL
        // We load this ONCE when the app starts.
        modelNode = ArModelNode(sceneView.engine).apply {
            scale = Position(0.2f, 0.2f, 0.2f)
            loadModelGlbAsync(
                glbFileLocation = "models/marker.glb", // ADD "models/"
                centerOrigin = Position(y = -0.5f),
                onLoaded = {
                    isModelLoaded = true
                    Toast.makeText(this@MainActivity, "Success: Marker Loaded!", Toast.LENGTH_SHORT).show()
                },
                onError = { exception ->
                    // THIS WILL TELL YOU THE PROBLEM
                    Toast.makeText(this@MainActivity, "Model Error: ${exception.message}", Toast.LENGTH_LONG).show()
                    // Check Logcat for "NaviX" to see full details
                    android.util.Log.e("NaviX", "Failed to load model", exception)
                }
            )
        }

        // 2. HANDLE TAPS
        sceneView.onTapAr = { hitResult, _ ->
            if (!isUserMode) {
                if (isModelLoaded) {
                    // A. Create the Anchor (The invisible real-world hook)
                    val anchor = hitResult.createAnchor()

                    // B. Visuals: Clone the master model and attach to anchor
                    val visualNode = modelNode.clone()
                    visualNode.anchor = anchor
                    sceneView.addChild(visualNode)

                    // C. Data: Get Coordinates
                    val pose = anchor.pose
                    val x = pose.tx()
                    val y = pose.ty()
                    val z = pose.tz()

                    // D. Data: Create the Node Object
                    val nodeId = "node_" + System.currentTimeMillis()

                    val currentNeighbors = mutableListOf<String>()
                    if (lastNodeId != null) {
                        currentNeighbors.add(lastNodeId!!)
                    }

                    // FIX: Use "neighborIds" to match your data class property
                    val newNode = Node(
                        id = nodeId,
                        x = x,
                        y = y,
                        z = z,
                        neighbors = currentNeighbors
                    )

                    // E. Logic: Save to Cloud and Link
                    chainAndUpdateNode(newNode)

                    Toast.makeText(this@MainActivity, "Point Added", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Loading 3D Model... Wait a second.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun chainAndUpdateNode(newNode: Node) {
        // 1. Save the NEW Node (It already points backward to the previous node)
        db.collection("maps").document("floor_1")
            .collection("nodes").document(newNode.id)
            .set(newNode)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
            }

        // 2. Update the PREVIOUS Node to point forward to this new node
        if (lastNodeId != null) {
            db.collection("maps").document("floor_1")
                .collection("nodes").document(lastNodeId!!)
                // FIX: Use "neighborIds" to match your data class property
                .update("neighborIds", FieldValue.arrayUnion(newNode.id))
        }

        // 3. Update the tracker so the next tap connects to this one
        lastNodeId = newNode.id
    }
    fun testNavigation() {
        Toast.makeText(this, "Downloading Map...", Toast.LENGTH_SHORT).show()

        // 1. Download ALL nodes from Firebase
        db.collection("maps").document("floor_1").collection("nodes")
            .get()
            .addOnSuccessListener { result ->
                val allNodes = result.toObjects(Node::class.java)

                if (allNodes.size < 2) {
                    Toast.makeText(this, "Not enough nodes mapped yet!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                // 2. PICK START AND END
                // For testing: Start at the first node you ever mapped, End at the last one.
                val startNode = allNodes[0]
                val endNode = allNodes[allNodes.size - 1]

                // 3. RUN THE BRAIN
                val pathFinder = PathFinder()
                val path = pathFinder.findPath(allNodes, startNode.id, endNode.id)

                if (path.isNotEmpty()) {
                    // 4. DRAW THE RESULT
                    drawPathInAR(path)
                } else {
                    Toast.makeText(this, "No path found between these points.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Download Failed", Toast.LENGTH_SHORT).show()
            }
    }



    fun drawPathInAR(path: List<com.navix.app.Node>) {
        // 1. Loop through the calculated path
        for (node in path) {

            // 2. Create a node for the dot
            val pathDot = ArModelNode(sceneView.engine).apply {
                // FIX: Set scale manually like you did in onCreate
                scale = Position(0.05f, 0.05f, 0.05f)

                // FIX: Use the correct parameters for version 0.10.0
                loadModelGlbAsync(
                    glbFileLocation = "models/sphere.glb", // ADD "models/"
                    centerOrigin = Position(y = 0.0f),
                    onLoaded = {
                        // Optional: You can do something here when it loads
                    }
                )

                // 3. Set Position
                position = Position(node.x, node.y, node.z)
            }

            // 4. Add to Scene
            sceneView.addChild(pathDot)
        }
        Toast.makeText(this, "Navigation Started! Follow the path.", Toast.LENGTH_LONG).show()
    }
}
