package com.navix.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore
import io.github.sceneview.ar.ArSceneView
// We removed CubeNode imports to fix your error

class MainActivity : AppCompatActivity() {

    lateinit var sceneView: ArSceneView
    val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sceneView = findViewById(R.id.sceneView)

        // The Listener for Version 0.10.0
        sceneView.onTapAr = { hitResult, _ ->

            // 1. Create an Anchor (An invisible point in the real world)
            val anchor = hitResult.createAnchor()

            // 2. Get the Coordinates (x, y, z)
            val pose = anchor.pose
            val x = pose.tx()
            val y = pose.ty()
            val z = pose.tz()

            // 3. Create the Data Object
            val nodeId = "node_" + System.currentTimeMillis()
            val newNode = com.navix.app.Node(
                id = nodeId,
                x = x,
                y = y,
                z = z
            )

            // 4. Upload to Firebase
            uploadNodeToCloud(newNode)

            // 5. Show a Popup so you know it worked
            Toast.makeText(this, "Saved Point: $x, $y, $z", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadNodeToCloud(node: com.navix.app.Node) {
        db.collection("maps")
            .document("floor_1")
            .collection("nodes")
            .document(node.id)
            .set(node)
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }
}