package com.mr.flutter.plugin.filepicker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mr.flutter.plugin.filepicker.FileUtils.maybeRenameGenericMimeDuplicate
import com.mr.flutter.plugin.filepicker.FileUtils.processFiles
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener
import java.io.IOException

class FilePickerDelegate(
    val activity: Activity,
    var pendingResult: MethodChannel.Result? = null
) : ActivityResultListener, RequestPermissionsResultListener {

    companion object {
        const val TAG = "FilePickerDelegate"
        val REQUEST_CODE = (FilePickerPlugin::class.java.hashCode() + 43) and 0x0000ffff
        val SAVE_FILE_CODE = (FilePickerPlugin::class.java.hashCode() + 83) and 0x0000ffff
        const val STORAGE_PERMISSION_CODE = 2344

        fun finishWithAlreadyActiveError(result: MethodChannel.Result) {
            result.error("already_active", "File picker is already active", null)
        }
    }

    var isMultipleSelection = false
    var loadDataToMemory = false
    var type: String? = null
    var compressionQuality = 0
    var allowedExtensions: ArrayList<String>? = null
    var eventSink: EventSink? = null
    var bytes: ByteArray? = null
    var saveFileName: String? = null
    var saveMimeType: String? = null
    var androidSafOptions: java.util.HashMap<*, *>? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    fun setEventHandler(eventSink: EventSink?) {
        this.eventSink = eventSink
    }

    fun checkStoragePermission(result: MethodChannel.Result) {
        result.success(resolveCurrentPermissionStatus(listOf("images", "video", "audio")))
    }

    fun requestStoragePermission(mediaTypes: List<String>, result: MethodChannel.Result) {
        val status = resolveCurrentPermissionStatus(mediaTypes)
        if (status == "granted") {
            result.success(status)
            return
        }
        pendingPermissionResult = result
        ActivityCompat.requestPermissions(
            activity,
            buildPermissionArray(mediaTypes),
            STORAGE_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != STORAGE_PERMISSION_CODE) return false
        val pending = pendingPermissionResult ?: return false
        pendingPermissionResult = null

        val allGranted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            pending.success("granted")
            return true
        }

        val anyPermanentlyDenied = permissions.indices.any { i ->
            grantResults[i] == PackageManager.PERMISSION_DENIED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permissions[i])
        }
        pending.success(if (anyPermanentlyDenied) "permanentlyDenied" else "denied")
        return true
    }

    private fun resolveCurrentPermissionStatus(mediaTypes: List<String>): String {
        val perms = buildPermissionArray(mediaTypes)
        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
        return if (allGranted) "granted" else "denied"
    }

    private fun buildPermissionArray(mediaTypes: List<String>): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perms = mutableListOf<String>()
            if ("images" in mediaTypes) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if ("video" in mediaTypes) perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            if ("audio" in mediaTypes) perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (perms.isEmpty()) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                perms.toTypedArray()
            }
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        return when (requestCode) {
            SAVE_FILE_CODE -> handleSaveFileResult(resultCode, data)
            REQUEST_CODE -> handleFilePickerResult(resultCode, data)
            else -> false.also {
                finishWithError(
                    "unknown_activity",
                    "Unknown activity error, please file an issue."
                )
            }
        }
    }

    private fun handleSaveFileResult(resultCode: Int, data: Intent?): Boolean {
        return when (resultCode) {
            Activity.RESULT_OK -> saveFile(data?.data)
            Activity.RESULT_CANCELED -> {
                finishWithSuccess(null)
                false
            }

            else -> false
        }
    }

    private fun saveFile(uri: Uri?): Boolean {
        uri ?: return false
        dispatchEventStatus(true)
        return try {
            val savedUri = FileUtils.writeBytesData(context = activity, uri, bytes) ?: uri
            val renamedUri = maybeRenameGenericMimeDuplicate(
                context = activity,
                uri = savedUri,
                originalFileName = saveFileName,
                mimeType = saveMimeType
            )
            finishWithSuccess(renamedUri.path)
            true
        } catch (e: IOException) {
            Log.e(TAG, "Error while saving file", e)
            finishWithError("Error while saving file", e.message)
            false
        }
    }

    private fun handleFilePickerResult(resultCode: Int, data: Intent?): Boolean {
        return when (resultCode) {
            Activity.RESULT_OK -> {
                dispatchEventStatus(true)
                processFiles(activity, data, compressionQuality, loadDataToMemory, type.orEmpty(), androidSafOptions)
                true
            }

            Activity.RESULT_CANCELED -> {
                finishWithSuccess(null)
                true
            }

            else -> false
        }
    }

    fun setPendingMethodCallResult(result: MethodChannel.Result): Boolean {
        return if (pendingResult == null) {
            pendingResult = result
            true
        } else {
            false
        }
    }

    fun finishWithSuccess(data: Any?) {
        dispatchEventStatus(false)
        pendingResult?.let {
            it.success(data?.takeIf { it is String }
                ?: (data as? ArrayList<*>)?.mapNotNull { (it as? FileInfo)?.toMap() })
            clearPendingResult()
        }
    }

    fun finishWithError(errorCode: String, errorMessage: String?) {
        dispatchEventStatus(false)
        pendingResult?.error(errorCode, errorMessage, null)
        clearPendingResult()
    }

    private fun dispatchEventStatus(status: Boolean) {
        if (eventSink != null && type != "dir") {
            Handler(Looper.getMainLooper()).post { eventSink?.success(status) }
        }
    }

    private fun clearPendingResult() {
        pendingResult = null
    }
}