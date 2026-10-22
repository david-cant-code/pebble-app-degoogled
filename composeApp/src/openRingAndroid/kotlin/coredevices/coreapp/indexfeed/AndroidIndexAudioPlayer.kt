package coredevices.coreapp.indexfeed

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AndroidIndexAudioPlayer : IndexAudioPlayer {
    private val playing = MutableStateFlow<String?>(null)
    override val playingPath: StateFlow<String?> = playing
    private var player: MediaPlayer? = null

    override fun toggle(path: String) {
        if (playing.value == path) {
            stop()
            return
        }
        stop()
        val created = MediaPlayer()
        try {
            created.setDataSource(path)
            created.setOnCompletionListener { stop() }
            created.prepare()
            created.start()
        } catch (_: Exception) {
            created.release()
            return
        }
        player = created
        playing.value = path
    }

    override fun stop() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        playing.value = null
    }
}
