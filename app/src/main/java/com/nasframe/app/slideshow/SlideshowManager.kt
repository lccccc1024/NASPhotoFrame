package com.nasframe.app.slideshow

import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable
import com.nasframe.app.service.SMBService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlideshowManager(
    private val smbService: SMBService,
    private val imageView: ImageView,
    private val intervalMs: Long = 10000,
    private val lifecycleScope: CoroutineScope
) {
    private val handler = Handler(Looper.getMainLooper())
    private var images: List<String> = emptyList()
    private var currentIndex = 0
    private var isPlaying = false
    private var loadJob: Job? = null
    private var consecutiveFailures = 0

    var onError: ((String) -> Unit)? = null

    companion object {
        private const val TAG = "SlideshowManager"
        private const val MAX_CONSECUTIVE_FAILURES = 3
        // Max decode resolution for 4K displays
        private const val MAX_DECODE_WIDTH = 3840
        private const val MAX_DECODE_HEIGHT = 2160
    }

    private val runnable = object : Runnable {
        override fun run() {
            if (isPlaying && images.isNotEmpty()) {
                showNextImage()
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    fun setImages(imageList: List<String>) {
        images = imageList.shuffled()
        currentIndex = 0
    }

    fun start() {
        if (images.isEmpty()) return
        isPlaying = true
        consecutiveFailures = 0
        handler.removeCallbacks(runnable)
        showCurrentImage()
        handler.postDelayed(runnable, intervalMs)
    }

    fun stop() {
        isPlaying = false
        handler.removeCallbacks(runnable)
    }

    fun pause() {
        stop()
    }

    fun resume() {
        if (images.isEmpty()) return
        isPlaying = true
        consecutiveFailures = 0
        handler.removeCallbacks(runnable)
        showCurrentImage()
        handler.postDelayed(runnable, intervalMs)
    }

    private fun showCurrentImage() {
        if (currentIndex < images.size) {
            loadImage(images[currentIndex])
        }
    }

    fun showNextImage() {
        if (images.isEmpty()) return
        currentIndex = (currentIndex + 1) % images.size
        loadImage(images[currentIndex])
    }

    fun showPreviousImage() {
        if (images.isEmpty()) return
        currentIndex = if (currentIndex <= 0) images.size - 1 else currentIndex - 1
        loadImage(images[currentIndex])
    }

    fun togglePause(): Boolean {
        if (isPlaying) {
            pause()
            return false
        } else {
            resume()
            return true
        }
    }

    fun isPaused(): Boolean = !isPlaying

    fun getCurrentIndex(): Int = currentIndex

    private fun loadImage(path: String) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val imageBytes = withContext(Dispatchers.IO) {
                smbService.readFileBytes(path)
            }
            if (!isActive) return@launch
            if (imageBytes == null) {
                consecutiveFailures++
                val msg = "读取失败: ${path.substringAfterLast("/")}"
                Log.w(TAG, msg)
                onError?.invoke(msg)
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    onError?.invoke("连续失败，已暂停")
                    pause()
                }
                return@launch
            }
            Glide.with(imageView.context)
                .load(imageBytes)
                .override(MAX_DECODE_WIDTH, MAX_DECODE_HEIGHT)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .skipMemoryCache(false)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        consecutiveFailures++
                        val msg = "解码失败: ${path.substringAfterLast("/")}"
                        Log.w(TAG, msg, e)
                        onError?.invoke(msg)
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            onError?.invoke("连续解码失败，已暂停")
                            pause()
                        }
                        // Return true to consume the error — keeps the previous image visible
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        consecutiveFailures = 0
                        return false
                    }
                })
                .into(imageView)
        }
    }

    fun release() {
        stop()
        loadJob?.cancel()
    }
}
