package com.vyvegroup.searchengine.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SearchDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "freesearch.db"
        const val DATABASE_VERSION = 3

        // Pages table
        const val TABLE_PAGES = "pages"
        const val COL_PAGE_ID = "id"
        const val COL_PAGE_URL = "url"
        const val COL_PAGE_TITLE = "title"
        const val COL_PAGE_CONTENT = "content"
        const val COL_PAGE_SNIPPET = "snippet"
        const val COL_PAGE_DOMAIN = "domain"
        const val COL_PAGE_CRAWLED_AT = "crawled_at"
        const val COL_PAGE_CONTENT_LENGTH = "content_length"
        const val COL_PAGE_HTTP_STATUS = "http_status"
        const val COL_PAGE_CONTENT_TYPE = "content_type"

        // FTS5 virtual table
        const val TABLE_FTS = "pages_fts"

        // Crawl queue
        const val TABLE_QUEUE = "crawl_queue"
        const val COL_QUEUE_ID = "id"
        const val COL_QUEUE_URL = "url"
        const val COL_QUEUE_DEPTH = "depth"
        const val COL_QUEUE_PRIORITY = "priority"
        const val COL_QUEUE_STATUS = "status"
        const val COL_QUEUE_ADDED_AT = "added_at"
        const val COL_QUEUE_PARENT_URL = "parent_url"

        // Crawl stats
        const val TABLE_STATS = "crawl_stats"
        const val COL_STATS_ID = "id"
        const val COL_STATS_TOTAL_CRAWLED = "total_crawled"
        const val COL_STATS_TOTAL_INDEXED = "total_indexed"
        const val COL_STATS_TOTAL_ERRORS = "total_errors"
        const val COL_STATS_START_TIME = "start_time"
        const val COL_STATS_LAST_CRAWL = "last_crawl"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Pages table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_PAGES (
                $COL_PAGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PAGE_URL TEXT UNIQUE NOT NULL,
                $COL_PAGE_TITLE TEXT,
                $COL_PAGE_CONTENT TEXT,
                $COL_PAGE_SNIPPET TEXT,
                $COL_PAGE_DOMAIN TEXT,
                $COL_PAGE_CRAWLED_AT INTEGER,
                $COL_PAGE_CONTENT_LENGTH INTEGER DEFAULT 0,
                $COL_PAGE_HTTP_STATUS INTEGER DEFAULT 200,
                $COL_PAGE_CONTENT_TYPE TEXT DEFAULT 'text/html'
            )
        """)

        // FTS5 full-text search table
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_FTS 
            USING fts5(
                $COL_PAGE_TITLE,
                $COL_PAGE_CONTENT,
                $COL_PAGE_SNIPPET,
                $COL_PAGE_DOMAIN,
                content=$TABLE_PAGES,
                content_rowid=$COL_PAGE_ID
            )
        """)

        // Triggers to keep FTS in sync
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON $TABLE_PAGES BEGIN
                INSERT INTO $TABLE_FTS(rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                VALUES (new.$COL_PAGE_ID, new.$COL_PAGE_TITLE, new.$COL_PAGE_CONTENT, new.$COL_PAGE_SNIPPET, new.$COL_PAGE_DOMAIN);
            END
        """)

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON $TABLE_PAGES BEGIN
                INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                VALUES('delete', old.$COL_PAGE_ID, old.$COL_PAGE_TITLE, old.$COL_PAGE_CONTENT, old.$COL_PAGE_SNIPPET, old.$COL_PAGE_DOMAIN);
            END
        """)

        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON $TABLE_PAGES BEGIN
                INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                VALUES('delete', old.$COL_PAGE_ID, old.$COL_PAGE_TITLE, old.$COL_PAGE_CONTENT, old.$COL_PAGE_SNIPPET, old.$COL_PAGE_DOMAIN);
                INSERT INTO $TABLE_FTS(rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                VALUES (new.$COL_PAGE_ID, new.$COL_PAGE_TITLE, new.$COL_PAGE_CONTENT, new.$COL_PAGE_SNIPPET, new.$COL_PAGE_DOMAIN);
            END
        """)

        // Crawl queue
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_QUEUE (
                $COL_QUEUE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_QUEUE_URL TEXT UNIQUE NOT NULL,
                $COL_QUEUE_DEPTH INTEGER DEFAULT 0,
                $COL_QUEUE_PRIORITY INTEGER DEFAULT 0,
                $COL_QUEUE_STATUS TEXT DEFAULT 'pending',
                $COL_QUEUE_ADDED_AT INTEGER,
                $COL_QUEUE_PARENT_URL TEXT
            )
        """)

        // Stats table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_STATS (
                $COL_STATS_ID INTEGER PRIMARY KEY CHECK ($COL_STATS_ID = 1),
                $COL_STATS_TOTAL_CRAWLED INTEGER DEFAULT 0,
                $COL_STATS_TOTAL_INDEXED INTEGER DEFAULT 0,
                $COL_STATS_TOTAL_ERRORS INTEGER DEFAULT 0,
                $COL_STATS_START_TIME INTEGER,
                $COL_STATS_LAST_CRAWL INTEGER
            )
        """)

        db.execSQL("INSERT OR IGNORE INTO $TABLE_STATS ($COL_STATS_ID) VALUES (1)")

        // Indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pages_url ON $TABLE_PAGES($COL_PAGE_URL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pages_domain ON $TABLE_PAGES($COL_PAGE_DOMAIN)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pages_crawled ON $TABLE_PAGES($COL_PAGE_CRAWLED_AT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_status ON $TABLE_QUEUE($COL_QUEUE_STATUS)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_queue_priority ON $TABLE_QUEUE($COL_QUEUE_PRIORITY DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_FTS")
            db.execSQL("DROP TRIGGER IF EXISTS pages_ai")
            db.execSQL("DROP TRIGGER IF EXISTS pages_ad")
            db.execSQL("DROP TRIGGER IF EXISTS pages_au")
            // Recreate FTS
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE_FTS 
                USING fts5(
                    $COL_PAGE_TITLE,
                    $COL_PAGE_CONTENT,
                    $COL_PAGE_SNIPPET,
                    $COL_PAGE_DOMAIN,
                    content=$TABLE_PAGES,
                    content_rowid=$COL_PAGE_ID
                )
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON $TABLE_PAGES BEGIN
                    INSERT INTO $TABLE_FTS(rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                    VALUES (new.$COL_PAGE_ID, new.$COL_PAGE_TITLE, new.$COL_PAGE_CONTENT, new.$COL_PAGE_SNIPPET, new.$COL_PAGE_DOMAIN);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON $TABLE_PAGES BEGIN
                    INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                    VALUES('delete', old.$COL_PAGE_ID, old.$COL_PAGE_TITLE, old.$COL_PAGE_CONTENT, old.$COL_PAGE_SNIPPET, old.$COL_PAGE_DOMAIN);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON $TABLE_PAGES BEGIN
                    INSERT INTO $TABLE_FTS($TABLE_FTS, rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                    VALUES('delete', old.$COL_PAGE_ID, old.$COL_PAGE_TITLE, old.$COL_PAGE_CONTENT, old.$COL_PAGE_SNIPPET, old.$COL_PAGE_DOMAIN);
                    INSERT INTO $TABLE_FTS(rowid, $COL_PAGE_TITLE, $COL_PAGE_CONTENT, $COL_PAGE_SNIPPET, $COL_PAGE_DOMAIN)
                    VALUES (new.$COL_PAGE_ID, new.$COL_PAGE_TITLE, new.$COL_PAGE_CONTENT, new.$COL_PAGE_SNIPPET, new.$COL_PAGE_DOMAIN);
                END
            """)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_PAGES ADD COLUMN $COL_PAGE_CONTENT_TYPE TEXT DEFAULT 'text/html'")
        }
    }
}
