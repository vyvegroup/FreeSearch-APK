package com.vyvegroup.searchengine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SearchDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "freesearch.db"
        const val DATABASE_VERSION = 2
        const val TABLE_PAGES = "pages"
        const val TABLE_FTS = "pages_fts"
        const val TABLE_QUEUE = "crawl_queue"
        const val TABLE_HISTORY = "browse_history"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PAGES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT UNIQUE NOT NULL,
                title TEXT,
                content TEXT,
                snippet TEXT,
                domain TEXT,
                crawled_at INTEGER,
                content_length INTEGER DEFAULT 0
            )
        """)
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_FTS USING fts5(
                title, content, snippet, domain,
                content=$TABLE_PAGES, content_rowid=id
            )
        """)
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON $TABLE_PAGES BEGIN
                INSERT INTO $TABLE_FTS(rowid, title, content, snippet, domain)
                VALUES (new.id, new.title, new.content, new.snippet, new.domain);
            END
        """)
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON $TABLE_PAGES BEGIN
                INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, title, content, snippet, domain)
                VALUES('delete', old.id, old.title, old.content, old.snippet, old.domain);
                INSERT INTO $TABLE_FTS(rowid, title, content, snippet, domain)
                VALUES (new.id, new.title, new.content, new.snippet, new.domain);
            END
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_QUEUE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT UNIQUE NOT NULL,
                depth INTEGER DEFAULT 0,
                priority INTEGER DEFAULT 0,
                status TEXT DEFAULT 'pending',
                parent_url TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT,
                visited_at INTEGER,
                favicon TEXT
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_status ON $TABLE_QUEUE(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_time ON $TABLE_HISTORY(visited_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FTS")
            db.execSQL("DROP TRIGGER IF EXISTS pages_ai")
            db.execSQL("DROP TRIGGER IF EXISTS pages_au")
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_FTS USING fts5(
                    title, content, snippet, domain,
                    content=$TABLE_PAGES, content_rowid=id
                )
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON $TABLE_PAGES BEGIN
                    INSERT INTO $TABLE_FTS(rowid, title, content, snippet, domain)
                    VALUES (new.id, new.title, new.content, new.snippet, new.domain);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON $TABLE_PAGES BEGIN
                    INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, title, content, snippet, domain)
                    VALUES('delete', old.id, old.title, old.content, old.snippet, old.domain);
                    INSERT INTO $TABLE_FTS(rowid, title, content, snippet, domain)
                    VALUES (new.id, new.title, new.content, new.snippet, new.domain);
                END
            """)
        }
    }
}
