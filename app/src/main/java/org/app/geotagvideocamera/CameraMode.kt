package org.app.geotagvideocamera

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource

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
            imageVector = if (currentMode == CameraMode.PHOTO) {
                ImageVector.vectorResource(R.drawable.ic_videocam)
            } else {
                ImageVector.vectorResource(R.drawable.ic_camera)
            },
            contentDescription = "Toggle camera mode"
        )
    }
}