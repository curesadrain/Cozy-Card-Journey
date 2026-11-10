package com.cozycards.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cozycards.app.ui.CozyApp
import com.cozycards.app.ui.theme.CozyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CozyTheme { CozyApp() } }
    }

    override fun onStart() {
        super.onStart()
        container.audio.onForeground(true)
    }

    override fun onStop() {
        container.audio.onForeground(false)
        super.onStop()
    }
}
