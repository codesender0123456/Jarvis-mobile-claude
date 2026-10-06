package com.example.actions.impl

import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoPlayerState(
    val url: String? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isMuted: Boolean = true
)

class VideoPlayerController {
    private val _state = MutableStateFlow(VideoPlayerState())
    val state: StateFlow<VideoPlayerState> = _state.asStateFlow()

    fun play(url: String, title: String = "Media Stream", muted: Boolean = true) {
        _state.value = VideoPlayerState(url = url, title = title, isPlaying = true, isMuted = muted)
    }

    fun stop() {
        _state.value = VideoPlayerState()
    }

    fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(isMuted = muted)
    }
}

class MediaVideoAction(private val controller: VideoPlayerController) : Action {

    override val name: String = "play_video"
    override val description: String = "Plays a video or media stream in the HUD with optional sound. Mic is ducked/muted during unmuted video playback."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "url" to ParamDefinition("string", "URL of direct mp4, HLS, or media stream", required = true),
        "title" to ParamDefinition("string", "Title of the video", required = false),
        "enable_sound" to ParamDefinition("boolean", "Whether to enable sound immediately (defaults to false/muted)", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val url = (args["url"] as? String)?.trim() ?: return ActionResult("Video URL missing, Sir.", isError = true)
        val title = (args["title"] as? String)?.trim() ?: "Video Feed"
        val sound = args["enable_sound"] as? Boolean ?: false

        controller.play(url = url, title = title, muted = !sound)

        return ActionResult(
            spokenResult = "Displaying $title in the HUD ${if (sound) "with audio active" else "muted" }, Sir.",
            cardData = mapOf("video_url" to url, "title" to title, "muted" to !sound)
        )
    }
}
