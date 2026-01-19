package com.example.navix

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
// ... other imports

class MapActivity : AppCompatActivity() { // OR class MapFragment : Fragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        
        // ... your existing map setup code ...
    }


    // --- PASTE THE CODE FROM STEP 2 HERE (Inside the class, but outside other functions) ---

    fun showDeleteConfirmationDialog(nodeId: String, nodeName: String) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Delete Node")
        builder.setMessage("Are you sure you want to delete '$nodeName'? This cannot be undone.")

        builder.setPositiveButton("Delete") { dialog, _ ->
            deleteNodeFromFirebase(nodeId)
            dialog.dismiss()
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.dismiss()
        }

        val alert = builder.create()
        alert.show()
    }

    private fun deleteNodeFromFirebase(nodeId: String) {
        // Ensure you have imported NodeManager or the object created in Step 1
        NodeManager.deleteNode(nodeId,
            onSuccess = {
                Toast.makeText(this, "Node deleted successfully", Toast.LENGTH_SHORT).show()
                // Optional: Code to remove marker from map UI immediately
            },
            onFailure = { e ->
                Toast.makeText(this, "Error deleting node: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // --- END OF PASTE ---
}

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        // ... map initialization code ...
    }

    // --- LOOK FOR THIS FUNCTION ---
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        // ... your existing code to add markers ...

        // --- PASTE STEP 3 HERE ---
        mMap.setOnMarkerClickListener { marker ->
            val nodeId = marker.tag as? String
            val nodeTitle = marker.title ?: "Unknown Node"

            if (nodeId != null) {
                showDeleteConfirmationDialog(nodeId, nodeTitle)
            }
            true
        }
        // --- END OF PASTE ---
    }
}