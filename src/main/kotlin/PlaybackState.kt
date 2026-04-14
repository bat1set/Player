package main

enum class PlaybackState {
    BUFFERING,
    PLAYING,
    PAUSED,
    SEEKING,
    ENDED
}

class PlaybackStateController(
    initialState: PlaybackState = PlaybackState.BUFFERING,
    initialPlayWhenReady: Boolean = true
) {
    var state: PlaybackState = initialState
        private set

    var playWhenReady: Boolean = initialPlayWhenReady
        private set

    var targetTime: Double = 0.0
        private set

    fun startBuffering(targetTime: Double, playWhenReady: Boolean = this.playWhenReady): PlaybackState {
        this.targetTime = targetTime
        this.playWhenReady = playWhenReady
        state = PlaybackState.BUFFERING
        return state
    }

    fun startSeek(targetTime: Double, playWhenReady: Boolean = this.playWhenReady): PlaybackState {
        this.targetTime = targetTime
        this.playWhenReady = playWhenReady
        state = PlaybackState.SEEKING
        return state
    }

    fun firstFrameReady(): PlaybackState {
        state = if (playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED
        return state
    }

    fun pause(): PlaybackState {
        playWhenReady = false
        if (state != PlaybackState.BUFFERING && state != PlaybackState.SEEKING && state != PlaybackState.ENDED) {
            state = PlaybackState.PAUSED
        }
        return state
    }

    fun play(): PlaybackState {
        playWhenReady = true
        if (state == PlaybackState.PAUSED) {
            state = PlaybackState.PLAYING
        }
        return state
    }

    fun end(): PlaybackState {
        playWhenReady = false
        state = PlaybackState.ENDED
        return state
    }

    fun canAdvanceClock(): Boolean = state == PlaybackState.PLAYING

    fun canPumpAudio(): Boolean = state == PlaybackState.PLAYING

    fun isWaitingForFirstFrame(): Boolean {
        return state == PlaybackState.BUFFERING || state == PlaybackState.SEEKING
    }
}
