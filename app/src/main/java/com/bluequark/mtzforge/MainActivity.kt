package com.bluequark.mtzforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.bluequark.mtzforge.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val blue = Color(0xFF1D78EF)
            val scheme = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color(0xFF4F9BFF), secondary = Color(0xFFFFD60A), background = Color(0xFF0E0E10), surface = Color(0xFF0E0E10))
            else lightColorScheme(primary = blue, secondary = Color(0xFFB38F00))
            MaterialTheme(colorScheme = scheme) {
                MainScreen()
            }
        }
    }
}
