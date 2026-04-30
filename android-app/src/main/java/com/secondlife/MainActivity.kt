package com.secondlife

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.secondlife.inference.SecondLifeViewModel
import com.secondlife.tts.TTSManager
import com.secondlife.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val viewModel: SecondLifeViewModel by viewModels()
    private lateinit var ttsManager: TTSManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        ttsManager = TTSManager(this)
        
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(viewModel, ttsManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized) {
            ttsManager.release()
        }
    }
}
