package com.optimizer.namaz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setContent {

            MaterialTheme {

                var trigger by remember {
                    mutableStateOf(0)
                }

                LaunchedEffect(Unit) {

                    while (true) {

                        DataEngine.update()

                        delay(1000)

                        trigger++
                    }
                }

                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {

                    trigger

                    DataEngine.state?.let {

                        DashboardRenderer.draw(
                            drawContext.canvas.nativeCanvas,
                            size.width.toInt(),
                            size.height.toInt(),
                            it
                        )
                    }
                }
            }
        }
    }
}
