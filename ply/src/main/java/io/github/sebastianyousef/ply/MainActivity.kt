package io.github.sebastianyousef.ply

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.sebastianyousef.keel.ui.KeelTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KeelTheme {
                io.github.sebastianyousef.ply.ui.PlyApp()
            }
        }
    }
}
