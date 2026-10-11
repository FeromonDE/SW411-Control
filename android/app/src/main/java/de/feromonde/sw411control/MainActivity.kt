package de.feromonde.sw411control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(7, 16, 25)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(7, 16, 25)))
        setContent { NativePanel(model) }
    }
    override fun onStart() { super.onStart(); model.start() }
    override fun onStop() { model.stop(); super.onStop() }
}
