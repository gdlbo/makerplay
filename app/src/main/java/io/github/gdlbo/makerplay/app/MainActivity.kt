package io.github.gdlbo.makerplay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.gdlbo.makerplay.app.navigation.MakerPlayApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as MakerPlayApplication).graph
        setContent { MakerPlayApp(graph) }
    }
}