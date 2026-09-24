package com.autumn.douyin.liquidglass.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class ModuleSettingsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        requireAuthorizedCaller()
        return ModuleSettingsStore.cursor(
            context?.let(ModuleSettingsStore::read) ?: ModuleSettings.Default
        )
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.com.autumn.douyin.liquidglass.settings"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        requireAuthorizedCaller()
        return null
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        requireAuthorizedCaller()
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        requireAuthorizedCaller()
        return 0
    }

    private fun requireAuthorizedCaller() {
        val caller = callingPackage
        check(caller == "com.ss.android.ugc.aweme" || caller == "com.autumn.douyin.liquidglass") {
            "settings are not available to $caller"
        }
    }
}
