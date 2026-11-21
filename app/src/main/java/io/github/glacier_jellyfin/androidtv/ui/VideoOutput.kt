package io.github.glacier_jellyfin.androidtv.ui

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import io.github.glacier_jellyfin.androidtv.core.data.settings.VideoOutput

@OptIn(UnstableApi::class)
internal fun VideoOutput.surfaceType(): Int = when (this) {
    VideoOutput.SurfaceView -> SURFACE_TYPE_SURFACE_VIEW
    VideoOutput.TextureView -> SURFACE_TYPE_TEXTURE_VIEW
}
