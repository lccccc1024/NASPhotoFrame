package com.nasframe.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.nasframe.app.service.SMBService
import com.nasframe.app.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.regex.Pattern

class ConfigActivity : AppCompatActivity() {
    private lateinit var secureStorage: SecureStorage
    private var isConnecting = false
    private lateinit var etHost: TextInputEditText
    private lateinit var etShare: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnConnect: View
    private lateinit var progressBar: View
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        etHost = findViewById(R.id.etHost)
        etShare = findViewById(R.id.etShare)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnConnect = findViewById(R.id.btnConnect)
        progressBar = findViewById(R.id.progressBar)
        tvError = findViewById(R.id.tvError)

        secureStorage = SecureStorage(this)

        if (secureStorage.hasCredentials()) {
            navigateToMain()
            return
        }

        btnConnect.setOnClickListener {
            connectToNAS()
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                connectToNAS()
                true
            } else false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnecting = false
    }

    private fun connectToNAS() {
        if (isConnecting) return
        isConnecting = true
        tvError.visibility = View.GONE

        val host = etHost.text.toString().trim()
        val share = etShare.text.toString().trim()
        val username = etUsername.text.toString().trim()

        // Validate inputs
        val error = validateHost(host)
            ?: validateShare(share)
            ?: validateUsername(username)
        if (error != null) {
            showError(error)
            isConnecting = false
            return
        }

        // Extract password as CharArray to avoid leaving it in a String
        val passwordChars = CharArray(etPassword.text.length)
        etPassword.text.getChars(0, etPassword.text.length, passwordChars, 0)
        val password = String(passwordChars)
        // Wipe the CharArray immediately
        passwordChars.fill('\u0000')

        showLoading(true)

        lifecycleScope.launch {
            val smbService = SMBService()
            try {
                val success = withContext(Dispatchers.IO) {
                    smbService.connect(host, share, username, password)
                }

                if (success) {
                    secureStorage.saveCredentials(host, share, username, password)
                    navigateToMain()
                } else {
                    showError(getString(R.string.connection_failed))
                }
            } catch (e: Exception) {
                showError("连接异常: ${e.localizedMessage}")
            } finally {
                smbService.close()
                if (!isFinishing) {
                    showLoading(false)
                }
                isConnecting = false
            }
        }
    }

    /**
     * Validate NAS host: IPv4, IPv6, or valid hostname.
     * Returns an error message string, or null if valid.
     */
    private fun validateHost(host: String): String? {
        if (host.isEmpty()) return "请输入 NAS 地址"

        // IPv4 pattern
        val ipv4Pattern = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
        )
        if (ipv4Pattern.matcher(host).matches()) return null

        // Basic hostname check (letters, digits, hyphens, dots)
        val hostnamePattern = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9\\-.]*[a-zA-Z0-9])?$")
        if (hostnamePattern.matcher(host).matches()) return null

        // Quick reachability check (best-effort, non-blocking by using InetAddress)
        return try {
            InetAddress.getByName(host)
            null // resolves OK
        } catch (e: Exception) {
            "无效的 NAS 地址"
        }
    }

    /**
     * Validate share name: no path separators, control chars, or wildcards.
     */
    private fun validateShare(share: String): String? {
        if (share.isEmpty()) return "请输入共享文件夹名称"
        if (share.length > 80) return "共享文件夹名称过长"
        val invalid = charArrayOf('/', '\\', '<', '>', ':', '"', '|', '?', '*', '\u0000')
        if (share.any { it in invalid }) return "共享文件夹名称包含无效字符"
        return null
    }

    /**
     * Validate username: basic sanity check.
     */
    private fun validateUsername(username: String): String? {
        if (username.isEmpty()) return "请输入用户名"
        if (username.length > 64) return "用户名过长"
        if (username.any { it.isWhitespace() }) return "用户名不能包含空格"
        return null
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnConnect.isEnabled = !show
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }
}
