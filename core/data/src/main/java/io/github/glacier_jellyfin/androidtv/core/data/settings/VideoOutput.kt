package io.github.glacier_jellyfin.androidtv.core.data.settings

import kotlinx.serialization.Serializable

/** Fullscreen video output on this TV, kept outside the profile settings copied between devices. */
@Serializable
enum class VideoOutput { SurfaceView, TextureView }
