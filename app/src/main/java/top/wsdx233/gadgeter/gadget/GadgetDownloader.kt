package top.wsdx233.gadgeter.gadget

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GadgetDownloader {
    
    suspend fun downloadGadget(context: Context, arch: String, version: String, onProgress: (String, Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val abi = when (arch) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> "arm64"
        }
        
        val url = "https://github.com/frida/frida/releases/download/$version/frida-gadget-$version-android-$abi.so.xz"
        val cacheDir = File(context.cacheDir, "gadgets")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        val targetSo = File(cacheDir, "libfrida-gadget-$abi.so")
        if (targetSo.exists()) {
            onProgress("Gadget already cached: ${targetSo.name}", 1.0f)
            return@withContext targetSo
        }

        val downloadedXz = File(cacheDir, "libfrida-gadget-$abi.so.xz")

        onProgress("Downloading Frida Gadget $version for $abi...", 0.0f)
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()
    
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                onProgress("HTTP Error: ${connection.responseCode}", 0f)
                return@withContext null
            }
    
            val totalSize = connection.contentLength
            var downloaded = 0
            
            connection.inputStream.use { input ->
                FileOutputStream(downloadedXz).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (totalSize > 0 && downloaded % (1024 * 1024) == 0) { // log every MB
                            val fraction = downloaded.toFloat() / totalSize.toFloat()
                            onProgress("Downloading... ${downloaded / 1024 / 1024} MB / ${totalSize / 1024 / 1024} MB", fraction)
                        }
                    }
                }
            }
            
            onProgress("Extracting XZ file...", 1.0f)
            XZInputStream(downloadedXz.inputStream()).use { xzInput ->
                FileOutputStream(targetSo).use { soOutput ->
                    xzInput.copyTo(soOutput)
                }
            }
            // Cleanup
            downloadedXz.delete()
            onProgress("Gadget downloaded and extracted successfully.", 1.0f)
            targetSo
        } catch (e: Exception) {
            e.printStackTrace()
            onProgress("Error downloading gadget: ${e.message}", 0f)
            if (downloadedXz.exists()) downloadedXz.delete()
            null
        }
    }
}
