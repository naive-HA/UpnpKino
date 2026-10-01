package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ProgressHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.documentfile.provider.DocumentFile
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import kotlin.math.min
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageWriter
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.transformer.SurfaceAssetLoader
import java.io.DataInputStream
import java.io.EOFException
import kotlinx.coroutines.CompletableDeferred

class TranscoderController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val TAG = "TranscoderController"
        }
    private val repo = UpnpRepository.transcoder
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    init {
        scope.launch {

        }
    }
    private fun updateSeekBarPosition(position: String, duration: String) {

    }
    fun release() {
        resetTranscoderActivity()
        scope.cancel()
    }
    private fun resetTranscoderActivity() {
        stopPlaying()
    }
    private fun stopPlaying(){
        Log.d(TAG, "stopPlaying")
        stopPlayingLocal()
    }
    private fun stopPlayingLocal(){
        repo.setSeekBarDuration("00:00:00")
        repo.setSeekBarPosition("00:00:00")
    }
}