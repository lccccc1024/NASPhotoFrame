package com.nasframe.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.nasframe.app.network.NetworkMonitor
import com.nasframe.app.service.SMBService
import com.nasframe.app.slideshow.SlideshowManager
import com.nasframe.app.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), NetworkMonitor.Listener {
    private var smbService: SMBService? = null
    private var slideshowManager: SlideshowManager? = null
    private var networkMonitor: NetworkMonitor? = null
    private var needReconnect = false
    private var totalImages = 0
    private var imagePaths: List<String> = emptyList()
    private val secureStorage by lazy { SecureStorage(this) }
    private val overlayHideRunnable = Runnable {
        findViewById<TextView>(R.id.tvOverlay)?.visibility = View.GONE
    }
    private var reconnectJob: Job? = null

    companion object {
        private const val OVERLAY_HIDE_DELAY_MS = 2500L
        private const val KEY_NEED_RECONNECT = "needReconnect"
        private const val KEY_TOTAL_IMAGES = "totalImages"
        private const val KEY_CURRENT_INDEX = "currentIndex"
        private const val KEY_IMAGE_PATHS = "imagePaths"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        setupClickToToggle()

        if (savedInstanceState != null) {
            restoreState(savedInstanceState)
        } else {
            startSlideshow()
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                slideshowManager?.pause()
                networkMonitor?.unregister()
            }

            override fun onResume(owner: LifecycleOwner) {
                if (networkMonitor == null) {
                    networkMonitor = NetworkMonitor(this@MainActivity)
                }
                networkMonitor?.register(this@MainActivity)
                slideshowManager?.resume()
                hideSystemUi()
            }
        })
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun setupClickToToggle() {
        findViewById<View>(R.id.ivPhoto).setOnClickListener {
            togglePause()
        }
        findViewById<View>(R.id.ivPhoto).setOnLongClickListener {
            secureStorage.clearCredentials()
            val intent = Intent(this, ConfigActivity::class.java)
            startActivity(intent)
            finish()
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                slideshowManager?.let { m ->
                    m.showNextImage()
                    if (totalImages > 0) {
                        showOverlay("${m.getCurrentIndex() + 1} / $totalImages")
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                slideshowManager?.let { m ->
                    m.showPreviousImage()
                    if (totalImages > 0) {
                        showOverlay("${m.getCurrentIndex() + 1} / $totalImages")
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                togglePause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePause()
                true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun togglePause() {
        slideshowManager?.let { m ->
            val nowPlaying = m.togglePause()
            if (nowPlaying) {
                showOverlay("▶ 播放中")
            } else {
                showOverlay("⏸ 已暂停")
            }
        }
    }

    private fun showOverlay(text: String) {
        val overlay = findViewById<TextView>(R.id.tvOverlay)
        overlay.text = text
        overlay.visibility = View.VISIBLE
        overlay.removeCallbacks(overlayHideRunnable)
        overlay.postDelayed(overlayHideRunnable, OVERLAY_HIDE_DELAY_MS)
    }

    private fun startSlideshow() {
        val host = secureStorage.getHost()
        val share = secureStorage.getShare()
        val username = secureStorage.getUsername()
        val password = secureStorage.getPassword()

        if (host == null || share == null || username == null) {
            showError(getString(R.string.connection_failed))
            return
        }

        lifecycleScope.launch {
            val service = SMBService()
            smbService = service
            val connected = withContext(Dispatchers.IO) {
                service.connect(host, share, username, password ?: "")
            }

            if (connected) {
                loadImages()
            } else {
                service.close()
                smbService = null
                showError(getString(R.string.connection_failed))
            }
        }
    }

    private fun loadImages() {
        lifecycleScope.launch {
            val service = smbService ?: return@launch
            val images = withContext(Dispatchers.IO) {
                service.listImages()
            }

            findViewById<View>(R.id.progressBar).visibility = View.GONE

            if (images.isEmpty()) {
                showError(getString(R.string.no_images))
                return@launch
            }

            imagePaths = images
            totalImages = images.size
            slideshowManager = SlideshowManager(
                service,
                findViewById(R.id.ivPhoto),
                10000,
                lifecycleScope
            ).also { mgr ->
                mgr.onError = { msg ->
                    showOverlay(msg)
                }
            }
            slideshowManager?.setImages(images)
            slideshowManager?.start()
            showOverlay("$totalImages 张图片，开始轮播")
        }
    }

    private fun showError(message: String) {
        findViewById<View>(R.id.progressBar).visibility = View.GONE
        val tvStatus = findViewById<android.widget.TextView>(R.id.tvStatus)
        tvStatus.text = message
        tvStatus.visibility = View.VISIBLE
    }

    override fun onNetworkLost() {
        needReconnect = true
        slideshowManager?.pause()
        showError(getString(R.string.network_lost))
    }

    override fun onNetworkAvailable() {
        if (needReconnect) {
            reconnectToNas()
            return
        }
        slideshowManager?.resume()
        findViewById<View>(R.id.tvStatus).visibility = View.GONE
    }

    override fun onReconnectFailed() {
        showError(getString(R.string.connection_failed))
    }

    private fun reconnectToNas() {
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            val host = secureStorage.getHost() ?: return@launch
            val share = secureStorage.getShare() ?: return@launch
            val username = secureStorage.getUsername() ?: return@launch
            val password = secureStorage.getPassword()

            smbService?.close()
            val service = SMBService()
            smbService = service

            val connected = withContext(Dispatchers.IO) {
                service.connect(host, share, username, password ?: "")
            }

            if (connected) {
                needReconnect = false
                loadImages()
            } else {
                service.close()
                smbService = null
                onReconnectFailed()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_NEED_RECONNECT, needReconnect)
        outState.putInt(KEY_TOTAL_IMAGES, totalImages)
        outState.putInt(KEY_CURRENT_INDEX, slideshowManager?.getCurrentIndex() ?: 0)
        outState.putStringArrayList(KEY_IMAGE_PATHS, ArrayList(imagePaths))
    }

    private fun restoreState(savedInstanceState: Bundle) {
        needReconnect = savedInstanceState.getBoolean(KEY_NEED_RECONNECT)
        totalImages = savedInstanceState.getInt(KEY_TOTAL_IMAGES)
        imagePaths = savedInstanceState.getStringArrayList(KEY_IMAGE_PATHS) ?: emptyList()

        // smbService is always null after config change (closed in onDestroy),
        // so always fall back to a fresh connection + scan
        startSlideshow()
    }

    override fun onDestroy() {
        findViewById<TextView>(R.id.tvOverlay)?.removeCallbacks(overlayHideRunnable)
        reconnectJob?.cancel()
        networkMonitor?.unregister()
        networkMonitor = null
        slideshowManager?.release()
        slideshowManager = null
        smbService?.close()
        smbService = null
        super.onDestroy()
    }
}
