package org.app.geotagvideocamera

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class CameraMode {
    PHOTO, VIDEO
}

@Composable
fun CameraModeToggle(
    currentMode: CameraMode,
    onModeChanged: (CameraMode) -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = {
            val newMode = if (currentMode == CameraMode.PHOTO) CameraMode.VIDEO else CameraMode.PHOTO
            onModeChanged(newMode)
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (currentMode == CameraMode.PHOTO) Icons.Filled.Videocam else Icons.Filled.PhotoCamera,
            contentDescription = "Toggle camera mode"
        )
    }
}