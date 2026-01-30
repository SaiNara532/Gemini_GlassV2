package com.impairedVision

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impairedVision.ui.theme.GuideGlassTheme
import com.impairedVision.vision.MainVision // Import your Yolo activity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GuideGlassTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainMenu(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun MainMenu(modifier: Modifier = Modifier) {

    val context = LocalContext.current

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Guide Glass",
            fontSize = 32.sp,
            modifier = Modifier.padding(bottom = 48.dp)
        )


        Button(
            onClick = {
                val intent = Intent(context, MainVision::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(80.dp)
        ) {
            Text(text = "Start Vision", fontSize = 24.sp)
        }
    }
}