package com.overlaytranslator.cache

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Persistent translation cache.
 *
 * Plain [SQLiteOpenHelper] on purpose — Room brings an annotation processor
 * whose compile-time query verifier fails to load sqlite-jdbc's native library
 * on NixOS. A three-column key-value store doesn't need an ORM.
 */
class TranslationCache private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE translations (
                source_hash TEXT PRIMARY KEY,
                source_text TEXT NOT NULL,
                translated  TEXT NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_updated ON translations(updated_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS translations")
        onCreate(db)
    }

    /**
     * Translation memory keyed by source text hash only — the same phrase
     * translates the same on every screen; screen/bbox in the key only
     * destroys the hit rate on animated screens.
     */
    fun get(sourceHash: String): String? {
        readableDatabase.rawQuery(
            "SELECT translated FROM translations WHERE source_hash=? LIMIT 1",
            arrayOf(sourceHash),
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun put(
        sourceHash: String,
        sourceText: String,
        translated: String,
    ) {
        val values = ContentValues().apply {
            put("source_hash", sourceHash)
            put("source_text", sourceText)
            put("translated", translated)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "translations", null, values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM translations")
    }

    fun pruneOlderThan(cutoffMillis: Long): Int {
        return writableDatabase.delete("translations", "updated_at < ?", arrayOf("$cutoffMillis"))
    }

    fun count(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM translations", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    companion object {
        const val DB_NAME = "translations.db"
        private const val DB_VERSION = 2

        @Volatile
        private var instance: TranslationCache? = null

        fun get(context: Context): TranslationCache {
            return instance ?: synchronized(this) {
                instance ?: TranslationCache(context).also { instance = it }
            }
        }
    }
}
