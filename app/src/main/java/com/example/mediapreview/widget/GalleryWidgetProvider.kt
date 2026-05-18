package com.example.mediapreview.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.mediapreview.MainActivity
import com.example.mediapreview.R

class GalleryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            updateWidget(context, appWidgetManager, id)
        }
    }

    companion object {
        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_gallery)

            // Click on widget → open app
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_preview_image, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_title, pendingIntent)

            // Load latest photo thumbnail from MediaStore
            try {
                val cursor = context.contentResolver.query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Images.Media._ID),
                    null, null,
                    "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        views.setImageViewUri(R.id.widget_preview_image, uri)
                    }
                }
            } catch (_: Exception) {
                // Fallback to launcher icon
            }

            manager.updateAppWidget(widgetId, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, GalleryWidgetProvider::class.java)
            )
            ids.forEach { updateWidget(context, manager, it) }
        }
    }
}

