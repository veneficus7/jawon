package com.jawon.app.ui.scenetest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

/**
 * M5 proof-of-pipeline screen — one lit jewel case built from a primitive,
 * nothing else. Not wired into the real Wall; that's M6. No ViewModel:
 * there's no app state to share with the scene yet.
 */
@Composable
fun SceneTestScreen(onBack: () -> Unit) {
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val engine = rememberEngine()
            val materialLoader = rememberMaterialLoader(engine)
            val caseMaterial = remember(materialLoader) {
                materialLoader.createColorInstance(
                    color = Color(0xFF1B1713),
                    metallic = 0f,
                    roughness = 0.35f
                )
            }

            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                materialLoader = materialLoader,
                // Frames the case automatically — no hand-tuned camera distance needed
                // for this bootstrap proof.
                autoFitContent = true
            ) {
                // Real jewel-case proportions from ENGINEERING.md's locked prototype export:
                // 16mm width (exaggerated from real 10mm for spine legibility) x 142mm
                // height x 125mm depth. A plain box — spine/cover textures are M6.
                CubeNode(
                    size = Size(x = 0.016f, y = 0.142f, z = 0.125f),
                    materialInstance = caseMaterial
                )
            }

            Button(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
            ) { Text("Back") }
        }
    }
}
