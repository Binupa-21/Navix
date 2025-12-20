package com.navix.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fix 1: Use the correct layout for the menu
        setContentView(R.layout.activity_menu)

        // Fix 2: Removed the logic that was incorrectly copied from MainActivity.
        // This activity's only job is to launch the next one with the correct mode.

        // Admin Button -> Opens Mapper (MainActivity in ADMIN mode)
        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("mode", "ADMIN") // Pass data to tell MainActivity how to behave
            startActivity(intent)
        }

        // User Button -> Opens Navigator (MainActivity in USER mode)
        findViewById<Button>(R.id.btnUser).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("mode", "USER")
            startActivity(intent)
        }
    }
}
