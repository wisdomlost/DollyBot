package com.example.dollybot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.dollybot.ui.DollyBotApp
import com.example.dollybot.ui.theme.DollybotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DollybotTheme {
                DollyBotApp()
            }
        }
    }
}
