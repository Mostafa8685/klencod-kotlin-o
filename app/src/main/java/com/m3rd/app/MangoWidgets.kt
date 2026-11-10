package com.m3rd.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random

/**
 * ودجت ذكريات السحابة لمانجو درايف (Mango Drive Cloud Memories Widget)
 */
class MangoPhotoWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.m3rd.app.WIDGET_REFRESH"
        private const val MAX_ROWS = 400

        private fun hasMediaPermission(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            } else {
                ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }

        private class Pick(
            val uri: Uri,
            val year: Int,
            val memory: Boolean,
            val totalPhotos: Int,
            val bucketName: String
        )

        private fun pickPhoto(ctx: Context): Pick? {
            val proj = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val today = Calendar.getInstance()
            val ids = ArrayList<Long>()
            val buckets = ArrayList<String>()
            var totalCount = 0
            try {
                ctx.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )?.use { c ->
                    totalCount = c.count
                    var rows = 0
                    while (c.moveToNext() && rows < MAX_ROWS) {
                        rows++
                        val id = c.getLong(0)
                        val bName = c.getString(3) ?: "سحابة الكاميرا"
                        ids.add(id)
                        buckets.add(bName)
                    }
                }
            } catch (_: Throwable) {
                return null
            }
            val rnd = Random()
            return if (ids.isNotEmpty()) {
                val k = rnd.nextInt(ids.size)
                Pick(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ids[k]),
                    today.get(Calendar.YEAR),
                    false,
                    totalCount,
                    buckets[k]
                )
            } else null
        }

        private fun loadThumb(ctx: Context, uri: Uri, px: Int): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun rounded(src: Bitmap, radiusPx: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), radiusPx, radiusPx, p)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(src, 0f, 0f, p)
            return out
        }

        private fun openAppIntent(ctx: Context, code: Int): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun updateOne(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.mango_widget_photo)
            val refresh = Intent(ctx, MangoPhotoWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                PendingIntent.getBroadcast(ctx, 7, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(ctx, 1))

            val dateStr = try {
                SimpleDateFormat("EEEE، d MMMM", Locale("ar")).format(Date())
            } catch (_: Throwable) {
                "اليوم"
            }
            views.setTextViewText(R.id.widget_date_badge, dateStr)

            val cloudCount = try { TelegramCloudEngine.loadCloudFiles(ctx).size } catch (_: Throwable) { 0 }

            if (!hasMediaPermission(ctx)) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "مانجو درايف ∞")
                views.setTextViewText(R.id.widget_subcaption, "سحابة تيليجرام غير المحدودة ($cloudCount ملف محفوظ)")
                mgr.updateAppWidget(id, views)
                return
            }
            val pick = pickPhoto(ctx)
            val bmp = if (pick != null) loadThumb(ctx, pick.uri, 520) else null

            if (pick == null || bmp == null) {
                views.setImageViewBitmap(R.id.widget_image, null)
                views.setTextViewText(R.id.widget_caption, "مانجو درايف ∞ (PRO)")
                views.setTextViewText(R.id.widget_subcaption, "$cloudCount ملف محفوظ في سحابة تيليجرام غير المحدودة")
            } else {
                val r = 26f * ctx.resources.displayMetrics.density * (bmp.width / 360f)
                views.setImageViewBitmap(R.id.widget_image, rounded(bmp, r))
                views.setTextViewText(R.id.widget_caption, "مانجو درايف • ${pick.bucketName}")
                views.setTextViewText(R.id.widget_subcaption, "سحابة غير محدودة ∞ • $cloudCount ملف سحابي محفوظ")
            }
            mgr.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateOne(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, MangoPhotoWidgetProvider::class.java))
            onUpdate(context, mgr, ids)
        }
    }
}

/**
 * ودجت مركز التحكم السحابي لمانجو درايف (Mango Drive Smart Cloud Hub)
 */
class MangoQuickWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val foldersIntent = PendingIntent.getActivity(
            context, 21,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", 1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val mainCloudIntent = PendingIntent.getActivity(
            context, 22,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", 0)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val mediaIntent = PendingIntent.getActivity(
            context, 24,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_tab", 2)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )
        val vaultIntent = PendingIntent.getActivity(
            context, 23,
            Intent(context, VaultActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            flags
        )

        val cloudCount = try { TelegramCloudEngine.loadCloudFiles(context).size } catch (_: Throwable) { 0 }
        val folderCount = try { TelegramCloudEngine.loadFolders(context).size } catch (_: Throwable) { 0 }
        val vaultCount = try { PrivateVault.list(context).size } catch (_: Throwable) { 0 }

        for (id in appWidgetIds) {
            val v = RemoteViews(context.packageName, R.layout.mango_widget_quick)
            v.setTextViewText(
                R.id.quick_stats_header,
                "مانجو درايف ∞ • $cloudCount ملف سحابي • $folderCount مجلدات • الخزنة: $vaultCount"
            )
            v.setTextViewText(R.id.quick_tier_badge, "∞ PRO")
            v.setOnClickPendingIntent(R.id.quick_camera, foldersIntent)
            v.setOnClickPendingIntent(R.id.quick_gallery, mainCloudIntent)
            v.setOnClickPendingIntent(R.id.quick_studio, mediaIntent)
            v.setOnClickPendingIntent(R.id.quick_vault, vaultIntent)
            appWidgetManager.updateAppWidget(id, v)
        }
    }
}
