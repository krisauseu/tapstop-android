package de.kf.tapstop

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle

/** Read-only post-update check. No editor fixtures, preference writes or secret output. */
internal fun Instrumentation.runPreservedDataChecks() {
    try {
        val prefs = targetContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val settings = Settings(targetContext)
        var checkedKeys = 0
        for (provider in Provider.rewriteProviders) {
            val name = if (provider == Provider.OPENAI) "api_key" else "groq_api_key"
            if (!prefs.getString(name, null).isNullOrBlank()) {
                check(settings.apiKey(provider).isNotBlank()) { "Existing cloud key is not decryptable" }
                checkedKeys++
            }
        }
        check(checkedKeys > 0) { "No existing cloud keys found" }
        val dictionaryEntries = PersonalDictionary.entries(settings.personalDictionary).size
        // An empty personal dictionary is valid; the update must preserve either state.
        val count = SQLiteDatabase.openDatabase(targetContext.getDatabasePath("usage.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            check(db.version == 1) { "Unexpected usage schema version" }
            db.rawQuery("SELECT COUNT(*) FROM dictations", null).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        }
        check(count > 0) { "Existing statistics are empty" }
        finish(Activity.RESULT_OK, Bundle().apply {
            putString("stream", "PASS: preserved data; provider=${settings.provider.name}; decryptable_cloud_keys=$checkedKeys; dictionary_entries=$dictionaryEntries; usage_rows=$count; schema=1\n")
        })
    } catch (_: Exception) {
        finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAILED: preserved-data check (no contents logged)\n") })
    }
}
