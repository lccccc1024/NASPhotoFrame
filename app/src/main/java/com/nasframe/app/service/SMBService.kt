package com.nasframe.app.service

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit

class SMBService : Closeable {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var share: DiskShare? = null

    companion object {
        private const val TAG = "SMBService"
        private const val BUFFER_SIZE = 8192

        // Timeouts: fail fast on a local network (no point waiting 60s)
        private const val SOCKET_TIMEOUT_SEC = 15L
        private const val READ_TIMEOUT_SEC = 10L
        private const val WRITE_TIMEOUT_SEC = 10L

        private val IMAGE_EXTENSIONS = listOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )
        private val ROOT_PATHS = listOf("/", "")
    }

    private val smbConfig: SmbConfig = SmbConfig.builder()
        .withSoTimeout(SOCKET_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withReadTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withWriteTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withEncryptData(true)            // request SMB encryption when available (SMB 3.x)
        .build()

    fun isConnected(): Boolean {
        return try {
            share != null && connection?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun connect(
        host: String,
        shareName: String,
        username: String,
        password: String,
        domain: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            disconnect()
            client = SMBClient(smbConfig)
            connection = client?.connect(host)
            val auth = AuthenticationContext(username, password.toCharArray(), domain)
            share = connection?.authenticate(auth)?.connectShare(shareName) as? DiskShare
            if (share == null) {
                Log.e(TAG, "connectShare returned null or not a DiskShare")
                false
            } else {
                Log.d(TAG, "Connected to share: $shareName")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "connect failed to $host/$shareName", e)
            false
        }
    }

    suspend fun listImages(): List<String> = withContext(Dispatchers.IO) {
        val images = mutableListOf<String>()
        val s = share
        if (s == null) {
            Log.w(TAG, "listImages called but not connected")
            return@withContext images
        }
        try {
            for (root in ROOT_PATHS) {
                if (images.isEmpty()) {
                    try {
                        val testList = s.list(root)
                        if (testList.isNotEmpty()) {
                            Log.d(TAG, "Root path '$root' works, found ${testList.size} entries")
                            listFilesRecursive(s, root, IMAGE_EXTENSIONS, images)
                            break
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Root path '$root' failed, trying next")
                    }
                }
            }
            Log.d(TAG, "Scan complete: found ${images.size} images")
        } catch (e: Exception) {
            Log.e(TAG, "listImages failed", e)
        }
        images
    }

    private fun listFilesRecursive(share: DiskShare, path: String, extensions: List<String>, result: MutableList<String>) {
        try {
            share.list(path).forEach { info: FileIdBothDirectoryInformation ->
                val name = info.fileName
                if (name == "." || name == "..") return@forEach
                val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                if (isDir && !name.startsWith(".")) {
                    listFilesRecursive(share, "$path$name/", extensions, result)
                } else if (!isDir) {
                    val ext = name.substringAfterLast(".").lowercase()
                    if (extensions.contains(ext)) {
                        result.add("$path$name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listFilesRecursive error at path=$path", e)
        }
    }

    /**
     * Open a remote file and return its [InputStream].
     * Caller MUST close the returned stream AND the associated [SmbFile]
     * to avoid connection leaks. Use [readFileBytes] for simple one-shot reads instead.
     *
     * @return A pair of (SmbFile, InputStream), or null if the file can't be opened.
     *         Both elements must be closed by the caller via `.use { }`.
     */
    suspend fun openInputStream(filePath: String): Pair<SmbFile, InputStream>? =
        withContext(Dispatchers.IO) {
            try {
                val file = share?.openFile(
                    filePath,
                    setOf(AccessMask.GENERIC_READ),
                    null,
                    setOf(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ) ?: run {
                    Log.w(TAG, "openFile returned null: $filePath")
                    return@withContext null
                }
                file to file.inputStream
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open stream: $filePath", e)
                null
            }
        }

    /**
     * Read an entire remote file into a ByteArray.
     * Prefer [openInputStream] for large files (photos) to avoid OOM.
     */
    suspend fun readFileBytes(filePath: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val (file, stream) = openInputStream(filePath) ?: return@withContext null
            file.use {
                stream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    val outputStream = ByteArrayOutputStream()
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.toByteArray()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read file: $filePath", e)
            null
        }
    }

    fun disconnect() {
        try { share?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null
        connection = null
        client = null
    }

    override fun close() {
        disconnect()
    }
}
