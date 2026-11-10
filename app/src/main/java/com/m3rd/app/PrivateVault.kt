package com.m3rd.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.max

/** عنصر داخل الخزنة الخاصة. الاسم والنوع محفوظان مشفّرين داخل الفهرس. */
data class VaultEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val addedAt: Long
) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/**
 * خزنة خاصة: الملفات تُنسخ إلى مساحة التطبيق الخاصة وتُشفَّر بمفتاح AES-256-GCM
 * محفوظ في Android Keystore (لا يخرج من الجهاز). التشفير على شكل أجزاء (256KB)
 * حتى لا تستهلك الفيديوهات الكبيرة ذاكرة.
 */
object PrivateVault {
    private const val KEY_ALIAS = "mango_vault_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val CHUNK = 256 * 1024
    private const val IV_LEN = 12
    private const val THUMB_PX = 360
    private const val MAX_IMAGE_BYTES = 48L * 1024 * 1024

    private val lock = Any()
    @Volatile private var cachedKey: SecretKey? = null

    // ---------------------------------------------------------------- مفتاح
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        var k = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (k == null) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            k = kg.generateKey()
        }
        cachedKey = k
        return k!!
    }

    // ------------------------------------------------------------- تشفير
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    private fun encryptStream(input: InputStream, out: OutputStream): Long {
        val dos = DataOutputStream(BufferedOutputStream(out))
        val buf = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            val ct = c.doFinal(buf, 0, n)
            dos.writeInt(iv.size + ct.size)
            dos.write(iv)
            dos.write(ct)
            total += n
        }
        dos.flush()
        return total
    }

    private fun decryptStream(input: InputStream, out: OutputStream) {
        val din = DataInputStream(input)
        while (true) {
            val n = try {
                din.readInt()
            } catch (e: EOFException) {
                break
            }
            if (n <= IV_LEN || n > CHUNK + 64) throw IOException("corrupt vault chunk")
            val iv = ByteArray(IV_LEN)
            din.readFully(iv)
            val ct = ByteArray(n - IV_LEN)
            din.readFully(ct)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            out.write(c.doFinal(ct))
        }
    }

    private fun encryptBytes(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        encryptStream(data.inputStream(), bos)
        return bos.toByteArray()
    }

    private fun decryptBytes(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        decryptStream(data.inputStream(), bos)
        return bos.toByteArray()
    }

    // -------------------------------------------------------------- ملفات
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "vault")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun tmpDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "vault_tmp")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun indexFile(ctx: Context) = File(dir(ctx), "index.enc")

    // -------------------------------------------------------------- الفهرس
    fun list(ctx: Context): List<VaultEntry> = synchronized(lock) { readIndex(ctx) }

    private fun readIndex(ctx: Context): MutableList<VaultEntry> {
        val f = indexFile(ctx)
        val result = ArrayList<VaultEntry>()
        if (!f.exists()) return result
        try {
            val json = String(decryptBytes(f.readBytes()), Charsets.UTF_8)
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    VaultEntry(
                        o.getString("id"),
                        o.optString("name", "item"),
                        o.optString("mime", "image/jpeg"),
                        o.optLong("size", 0L),
                        o.optLong("at", 0L)
                    )
                )
            }
        } catch (e: Throwable) {
            // فهرس تالف: نرجع قائمة فارغة بدل الانهيار
        }
        return result
    }

    private fun writeIndex(ctx: Context, items: List<VaultEntry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name).put("mime", e.mime)
                    .put("size", e.size).put("at", e.addedAt)
            )
        }
        val enc = encryptBytes(arr.toString().toByteArray(Charsets.UTF_8))
        val tmp = File(dir(ctx), "index.tmp")
        FileOutputStream(tmp).use { it.write(enc) }
        if (!tmp.renameTo(indexFile(ctx))) {
            indexFile(ctx).delete()
            tmp.renameTo(indexFile(ctx))
        }
    }

    // ------------------------------------------------------------- إضافة
    fun add(ctx: Context, uri: Uri, displayName: String, mimeType: String): VaultEntry? =
        synchronized(lock) { addLocked(ctx, uri, displayName, mimeType) }

    private fun addLocked(ctx: Context, uri: Uri, displayName: String, mimeType: String): VaultEntry? {
        run {
            val id = UUID.randomUUID().toString()
            val dataFile = File(dir(ctx), "$id.enc")
            try {
                val mime = if (mimeType.isBlank()) "image/jpeg" else mimeType
                val isVideo = mime.startsWith("video/")
                var size = 0L
                val input = ctx.contentResolver.openInputStream(uri) ?: return null
                input.use { ins ->
                    FileOutputStream(dataFile).use { o -> size = encryptStream(ins, o) }
                }
                val thumb = makeThumb(ctx, uri, isVideo)
                if (thumb != null) {
                    FileOutputStream(File(dir(ctx), "$id.thb")).use { it.write(encryptBytes(thumb)) }
                }
                val entry = VaultEntry(id, displayName, mime, size, System.currentTimeMillis())
                val items = readIndex(ctx)
                items.add(0, entry)
                writeIndex(ctx, items)
                return entry
            } catch (e: Throwable) {
                dataFile.delete()
                File(dir(ctx), "$id.thb").delete()
                return null
            }
        }
    }

    // -------------------------------------------------------------- مصغّرات
    private fun sampleFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    private fun exifDegrees(exif: ExifInterface): Int {
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotate(src: Bitmap, deg: Int): Bitmap {
        val m = Matrix()
        m.postRotate(deg.toFloat())
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val big = max(src.width, src.height)
        if (big <= maxDim) return src
        val f = maxDim.toFloat() / big
        return Bitmap.createScaledBitmap(
            src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true
        )
    }

    private fun makeThumb(ctx: Context, uri: Uri, isVideo: Boolean): ByteArray? {
        try {
            var bmp: Bitmap? = null
            if (isVideo) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    bmp = r.getFrameAtTime(0)
                } finally {
                    try { r.release() } catch (_: Throwable) {}
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, THUMB_PX)
                }
                bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                val deg = ctx.contentResolver.openInputStream(uri)?.use { exifDegrees(ExifInterface(it)) } ?: 0
                if (bmp != null && deg != 0) bmp = rotate(bmp, deg)
            }
            if (bmp == null) return null
            val scaled = scaleDown(bmp, THUMB_PX)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            return bos.toByteArray()
        } catch (e: Throwable) {
            return null
        }
    }

    fun thumbnail(ctx: Context, e: VaultEntry): Bitmap? {
        try {
            val f = File(dir(ctx), "${e.id}.thb")
            if (!f.exists()) return null
            val bytes = decryptBytes(f.readBytes())
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (t: Throwable) {
            return null
        }
    }

    /** صورة كاملة (للعرض) بحد أقصى للأبعاد. */
    fun loadImage(ctx: Context, e: VaultEntry, maxDim: Int): Bitmap? {
        try {
            val f = File(dir(ctx), "${e.id}.enc")
            if (!f.exists() || e.size > MAX_IMAGE_BYTES) return null
            val bos = ByteArrayOutputStream()
            FileInputStream(f).use { decryptStream(it, bos) }
            val bytes = bos.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxDim)
            }
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val deg = try { exifDegrees(ExifInterface(bytes.inputStream())) } catch (_: Throwable) { 0 }
            if (deg != 0) bmp = rotate(bmp, deg)
            return bmp
        } catch (t: Throwable) {
            return null
        }
    }

    // ------------------------------------------------------- فك التشفير مؤقتاً
    private fun extFor(mime: String): String = when {
        mime.contains("mp4") -> "mp4"
        mime.contains("webm") -> "webm"
        mime.contains("3gpp") -> "3gp"
        mime.contains("png") -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        mime.startsWith("video/") -> "mp4"
        else -> "jpg"
    }

    fun exportToTemp(ctx: Context, e: VaultEntry): File? {
        try {
            val src = File(dir(ctx), "${e.id}.enc")
            if (!src.exists()) return null
            val out = File(tmpDir(ctx), "${e.id}.${extFor(e.mime)}")
            if (out.exists() && out.length() > 0) return out
            FileInputStream(src).use { ins -> FileOutputStream(out).use { o -> decryptStream(ins, o) } }
            return out
        } catch (t: Throwable) {
            return null
        }
    }

    fun clearTemp(ctx: Context) {
        try {
            tmpDir(ctx).listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) {}
    }

    // ---------------------------------------------------------- استعادة / حذف
    fun restoreToGallery(ctx: Context, e: VaultEntry): Boolean {
        try {
            val src = File(dir(ctx), "${e.id}.enc")
            if (!src.exists()) return false
            val video = e.isVideo
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val safeName = if (e.name.contains('.')) e.name else e.name + "." + extFor(e.mime)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, e.mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val root = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/Mango Gallery")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = ctx.contentResolver.insert(base, values) ?: return false
            try {
                val os = ctx.contentResolver.openOutputStream(uri) ?: return false
                os.use { o -> FileInputStream(src).use { ins -> decryptStream(ins, o) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    ctx.contentResolver.update(uri, done, null, null)
                }
            } catch (t: Throwable) {
                try { ctx.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                return false
            }
            delete(ctx, e)
            return true
        } catch (t: Throwable) {
            return false
        }
    }

    fun delete(ctx: Context, e: VaultEntry) {
        synchronized(lock) {
            File(dir(ctx), "${e.id}.enc").delete()
            File(dir(ctx), "${e.id}.thb").delete()
            File(tmpDir(ctx), "${e.id}.${extFor(e.mime)}").delete()
            val items = readIndex(ctx)
            items.removeAll { it.id == e.id }
            writeIndex(ctx, items)
        }
    }
}
