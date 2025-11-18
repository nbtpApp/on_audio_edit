package com.lucasjosino.on_audio_edit.methods.edits

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
//import androidx.compose.animation.core.copy
//import androidx.compose.foundation.text2.input.delete
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lucasjosino.on_audio_edit.utils.checkArtworkFormat
import com.lucasjosino.on_audio_edit.utils.convertFileSize
import com.lucasjosino.on_audio_edit.utils.readBytes
import com.lucasjosino.on_audio_edit.utils.warningSizeCall
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.generic.Utils
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.StandardArtwork //もとのjaudiotagerでは削除されていたので、本家の2.2.3を導入した
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

@SuppressLint("StaticFieldLeak")
class OnArtworkEditWithByteArray10(private val context: Context, private val activity: Activity) : ViewModel() {

    // Main parameters
    private val channelError = "on_audio_error"
    private val onSharedPrefKeyUriCode = "on_audio_edit_uri"
    private var searchInsideFolders: Boolean = false

    // Check if plugin already has uri.
    private fun getUri(): String? = activity.getSharedPreferences(
        "on_audio_edit",
        Context.MODE_PRIVATE
    ).getString(onSharedPrefKeyUriCode, "")

    /**
     * ByteArrayからアートワークの編集処理を開始するメソッド
     * @param result Flutter側に処理結果を返すためのオブジェクト
     * @param call Flutter側から渡されたメソッド呼び出し情報（引数など）
     */
    fun editArtworkWithByteArray(result: MethodChannel.Result, call: MethodCall) {
        // JAudioTaggerライブラリの設定: 不要な情報を削除してファイルを書き込む
        TagOptionSingleton.getInstance().isId3v2PaddingWillShorten = true

        // バックグラウンドで重い処理を実行
        viewModelScope.launch {
            val resultEditArtwork = doEverythingInBackground(call)
            result.success(resultEditArtwork)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun doEverythingInBackground(call: MethodCall): Boolean = withContext(Dispatchers.IO) {
        // Get all information from Dart.
        val data = call.argument<String>("data")!!
        val artworkBytes = call.argument<ByteArray>("artworkBytes")
        val type = checkArtworkFormat(call.argument<Int>("type")!!)
        //val description = call.argument<String>("description")!!
        val artworkDescription = call.argument<String>("description")!! // 変数名を変更
        val size = call.argument<Int>("size")!!
        searchInsideFolders = call.argument<Boolean>("searchInsideFolders")!!

        // アートワークのバイトデータがなければ処理を中断
        if (artworkBytes == null) {
            Log.e(channelError, "Artwork bytes are null!")
            return@withContext false
        }

        // At this point, we already requested the 'special' path to user folder.
        // If not, return false and a warn.
        if (getUri() == null) {
            Log.w("on_audio_exception", "Uri to folder path doesn't exist!")
            return@withContext false
        }

        val internalData = File(data)
        val uriFolder: Uri = Uri.parse(getUri())
        val dFile = DocumentFile.fromTreeUri(context, uriFolder) ?: return@withContext false
        val file = getFile(dFile, internalData)?.uri

        if (file == null) {
            Log.w(
                "on_audio_exception",
                "File: $data not found!\n " +
                        "Call [resetComplexPermission] and let the user choose the \"Root\" folder."
            )
            return@withContext false
        }

        val pUri = file
        val temp = File.createTempFile("tmp-media", '.' + Utils.getExtension(internalData))
        Utils.copy(internalData, temp)
        temp.deleteOnExit()

        val audioFile = AudioFileIO.read(File(data))
        val audioTag = audioFile.tag

        // Delete previous artwork
        audioTag.deleteArtworkField()

        // ByteArrayから直接Artworkオブジェクトを作成する
        val artwork = StandardArtwork().apply {
            this.binaryData = artworkBytes
            this.mimeType = type
            this.pictureType = PictureTypes.DEFAULT_ID
            this.description = artworkDescription // 変更した変数名を使用
            this.height = size
            this.width = size
        }

//        // ByteArrayから直接Artworkオブジェクトを作成する
//        val artwork = StandardArtwork().apply {
//            setImageData(artworkBytes)
//            setMimeType(type)
//            setPictureType(PictureTypes.DEFAULT_ID)
//            setDescription(artworkDescription)
//            setHeight(size)
//            setWidth(size)
//        }

//        val artwork = AndroidArtwork()
//
//        artwork.setBinaryData(artworkBytes)
//        artwork.setMimeType(type)
//        artwork.setPictureType(PictureTypes.DEFAULT_ID)
//        artwork.setDescription(artworkDescription)
//        artwork.setHeight(size)
//        artwork.setWidth(size)


        audioTag.setField(artwork)
        audioFile.file = temp

        try {
            AudioFileIO.write(audioFile)
        } catch (e: Exception) {
            Log.i(channelError, "$e")
        }

        val fis = FileInputStream(temp)
        val audioContent = readBytes(fis)
        val audioSize = convertFileSize(audioFile.file.length())
        warningSizeCall(audioSize, data)

        try {
            context.contentResolver.openFileDescriptor(pUri, "rw")?.use { it ->
                FileOutputStream(it.fileDescriptor).use {
                    it.write(audioContent)
                    temp.delete()
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.i("on_audio_exception", "$e")
        } catch (f: FileNotFoundException) {
            Log.i("on_audio_FileNotFound", "$f")
        } catch (io: IOException) {
            Log.i("on_audio_IOException", "$io")
        }

        temp.delete()
        return@withContext false
    }

    private fun getFile(directory: DocumentFile, specificFile: File): DocumentFile? {
        val files = directory.listFiles()
        for (file in files) {
            val data: DocumentFile? = if (file.isDirectory) {
                if (searchInsideFolders) getFile(file, specificFile) else return null
            } else {
                if (file.name == specificFile.name) file else null
            }
            if (data != null) return data
        }
        return null
    }
}
