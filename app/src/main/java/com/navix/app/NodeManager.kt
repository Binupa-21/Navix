package com.example.navix

import com.google.firebase.database.FirebaseDatabase
import com.google.android.gms.tasks.Task

object NodeManager {

    // Reference to your "nodes" path in Firebase
    // specific path depends on your database structure (e.g., "nodes" or "locations")
    private val databaseRef = FirebaseDatabase.getInstance().getReference("nodes")

    /**
     * Deletes a node by its unique ID.
     * @param nodeId The unique key of the node (e.g., "-OA8s9d8s9d8...")
     * @param onSuccess Callback for successful deletion
     * @param onFailure Callback for failed deletion
     */
    fun deleteNode(nodeId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (nodeId.isEmpty()) return

        // removeValue() is the specific command to delete data in Realtime Database
        databaseRef.child(nodeId).removeValue()
            .addOnSuccessListener {
                onSuccess()
            }
            .addOnFailureListener { exception ->
                onFailure(exception)
            }
    }
}