package com.pureplayer.video

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "sources")
data class IptvSourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val serverOrM3uUrl: String,
    val username: String,
    val encryptedPassword: String,
    val lastUpdatedMs: Long,
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val radioCount: Int,
    val expDateSec: Long,
    val accountStatus: String
)

fun IptvSourceEntity.toDomain(): IptvSource = IptvSource(
    id = id,
    name = name,
    type = runCatching { IptvSourceType.valueOf(type) }.getOrDefault(IptvSourceType.M3U_URL),
    serverOrM3uUrl = serverOrM3uUrl,
    username = username,
    encryptedPassword = encryptedPassword,
    lastUpdatedMs = lastUpdatedMs,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    radioCount = radioCount,
    expDateSec = expDateSec,
    accountStatus = accountStatus
)

fun IptvSource.toEntity(): IptvSourceEntity = IptvSourceEntity(
    id = id,
    name = name,
    type = type.name,
    serverOrM3uUrl = serverOrM3uUrl,
    username = username,
    encryptedPassword = encryptedPassword,
    lastUpdatedMs = lastUpdatedMs,
    liveCount = liveCount,
    movieCount = movieCount,
    seriesCount = seriesCount,
    radioCount = radioCount,
    expDateSec = expDateSec,
    accountStatus = accountStatus
)

@Entity(
    tableName = "streams",
    indices = [
        Index(value = ["sourceId", "contentType", "categoryName"], name = "idx_streams_source_type_cat"),
        Index(value = ["sourceId", "normalizedTitle"], name = "idx_streams_norm_title"),
        Index(value = ["sourceId", "normalizedCategory"], name = "idx_streams_norm_cat"),
        Index(value = ["sourceId", "streamId"], name = "idx_streams_source_item")
    ]
)
data class IptvStreamEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val sourceId: String,
    val streamId: String,
    val contentType: String,
    val categoryId: String,
    val categoryName: String,
    val normalizedCategory: String,
    val title: String,
    val normalizedTitle: String,
    val streamUrl: String,
    val logoUrl: String,
    val rating: String,
    val plot: String,
    val releaseDate: String,
    val durationLabel: String,
    val seriesId: String,
    val seriesName: String,
    val seasonNum: Int,
    val episodeNum: Int,
    val containerExt: String,
    val httpUa: String,
    val httpRef: String
)

fun IptvStreamEntity.toDomain(): IptvStreamItem = IptvStreamItem(
    id = streamId,
    sourceId = sourceId,
    contentType = runCatching { IptvContentType.valueOf(contentType) }.getOrDefault(IptvContentType.LIVE_TV),
    categoryId = categoryId,
    categoryName = categoryName,
    title = title,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    rating = rating,
    plot = plot,
    releaseDate = releaseDate,
    durationLabel = durationLabel,
    seriesId = seriesId,
    seriesName = seriesName,
    seasonNumber = seasonNum,
    episodeNumber = episodeNum,
    containerExtension = containerExt,
    httpUserAgent = httpUa,
    httpReferrer = httpRef
)

fun IptvStreamItem.toEntity(): IptvStreamEntity = IptvStreamEntity(
    sourceId = sourceId,
    streamId = id,
    contentType = contentType.name,
    categoryId = categoryId,
    categoryName = categoryName,
    normalizedCategory = normalizedCategory,
    title = title,
    normalizedTitle = normalizedTitle,
    streamUrl = streamUrl,
    logoUrl = logoUrl,
    rating = rating,
    plot = plot,
    releaseDate = releaseDate,
    durationLabel = durationLabel,
    seriesId = seriesId,
    seriesName = seriesName,
    seasonNum = seasonNumber,
    episodeNum = episodeNumber,
    containerExt = containerExtension,
    httpUa = httpUserAgent,
    httpRef = httpReferrer
)

@Entity(
    tableName = "favorites",
    primaryKeys = ["sourceId", "itemId"],
    indices = [Index(value = ["sourceId", "addedMs"], name = "idx_fav_source_added")]
)
data class IptvFavoriteEntity(
    val sourceId: String,
    val itemId: String,
    val addedMs: Long
)

@Entity(
    tableName = "recents",
    primaryKeys = ["sourceId", "itemId"],
    indices = [Index(value = ["sourceId", "watchedMs"], name = "idx_recent_source_watched")]
)
data class IptvRecentEntity(
    val sourceId: String,
    val itemId: String,
    val watchedMs: Long
)

@Entity(
    tableName = "series_last_episode",
    primaryKeys = ["sourceId", "seriesKey"]
)
data class IptvSeriesLastEpEntity(
    val sourceId: String,
    val seriesKey: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val streamUrl: String,
    val updatedMs: Long
)

data class ContentTypeCountTuple(
    @ColumnInfo(name = "contentType") val contentType: String,
    @ColumnInfo(name = "cnt") val cnt: Int
)

data class CategorySummaryTuple(
    @ColumnInfo(name = "categoryId") val categoryId: String,
    @ColumnInfo(name = "categoryName") val categoryName: String,
    @ColumnInfo(name = "cnt") val cnt: Int
)

@Dao
interface IptvDao {

    @Query("SELECT * FROM sources ORDER BY lastUpdatedMs DESC")
    fun getAllSourcesSync(): List<IptvSourceEntity>

    @Query("SELECT COUNT(*) FROM sources")
    fun countSourcesSync(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSourcesSync(sources: List<IptvSourceEntity>)

    @Query("DELETE FROM sources")
    fun clearAllSourcesSync()

    @Query("DELETE FROM sources WHERE id = :sourceId")
    fun deleteSourceByIdSync(sourceId: String)

    @Query("SELECT COUNT(*) FROM streams WHERE sourceId = :sourceId")
    fun countStreamsForSourceSync(sourceId: String): Int

    @Query("SELECT COUNT(*) FROM streams WHERE sourceId = :sourceId AND contentType = :contentType")
    fun countStreamsByTypeSync(sourceId: String, contentType: String): Int

    @Query("SELECT COUNT(DISTINCT CASE WHEN seriesName != '' THEN seriesName ELSE title END) FROM streams WHERE sourceId = :sourceId AND contentType = 'SERIES'")
    fun countDistinctSeriesSync(sourceId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM streams WHERE sourceId = :sourceId AND streamId = :streamId)")
    fun hasStreamIdSync(sourceId: String, streamId: String): Boolean

    @Query("SELECT contentType, COUNT(*) AS cnt FROM streams WHERE sourceId = :sourceId GROUP BY contentType")
    fun getContentTypeCountsSync(sourceId: String): List<ContentTypeCountTuple>

    @Query("SELECT MIN(categoryId) AS categoryId, categoryName, COUNT(*) AS cnt FROM streams WHERE sourceId = :sourceId AND contentType = :contentType GROUP BY categoryName ORDER BY categoryName ASC")
    fun getCategoriesForTypeSync(sourceId: String, contentType: String): List<CategorySummaryTuple>

    @Query("SELECT * FROM streams WHERE sourceId = :sourceId AND contentType = :contentType AND (:isAll = 1 OR categoryName = :categoryName OR categoryId = :categoryId) ORDER BY rowId ASC LIMIT :limit OFFSET :offset")
    fun getStreamsPageSync(
        sourceId: String,
        contentType: String,
        isAll: Int,
        categoryName: String,
        categoryId: String,
        limit: Int,
        offset: Int
    ): List<IptvStreamEntity>

    @Query("""
        SELECT MIN(rowId) AS rowId, sourceId, MIN(streamId) AS streamId, contentType,
               MIN(categoryId) AS categoryId, MIN(categoryName) AS categoryName,
               MIN(normalizedCategory) AS normalizedCategory,
               (CASE WHEN seriesName != '' THEN seriesName ELSE title END) AS title,
               MIN(normalizedTitle) AS normalizedTitle, MIN(streamUrl) AS streamUrl,
               MAX(logoUrl) AS logoUrl, MAX(rating) AS rating, MAX(plot) AS plot,
               MAX(releaseDate) AS releaseDate,
               CAST(COUNT(*) AS TEXT) || ' Episodes' AS durationLabel,
               MAX(seriesId) AS seriesId,
               (CASE WHEN seriesName != '' THEN seriesName ELSE title END) AS seriesName,
               MIN(seasonNum) AS seasonNum, MIN(episodeNum) AS episodeNum,
               MIN(containerExt) AS containerExt, MIN(httpUa) AS httpUa, MIN(httpRef) AS httpRef
        FROM streams
        WHERE sourceId = :sourceId AND contentType = 'SERIES'
          AND (:isAll = 1 OR categoryName = :categoryName OR categoryId = :categoryId)
        GROUP BY (CASE WHEN seriesName != '' THEN seriesName ELSE title END)
        ORDER BY title ASC
        LIMIT :limit OFFSET :offset
    """)
    fun getGroupedM3uSeriesPageSync(
        sourceId: String,
        isAll: Int,
        categoryName: String,
        categoryId: String,
        limit: Int,
        offset: Int
    ): List<IptvStreamEntity>

    @Query("""
        SELECT * FROM streams
        WHERE sourceId = :sourceId AND contentType = 'SERIES'
          AND (LOWER(seriesName) = LOWER(:seriesName) OR LOWER(title) LIKE LOWER(:titlePrefix))
        ORDER BY seasonNum ASC, episodeNum ASC, rowId ASC
    """)
    fun getSeriesEpisodesSync(sourceId: String, seriesName: String, titlePrefix: String): List<IptvStreamEntity>

    @Query("SELECT * FROM streams WHERE sourceId = :sourceId ORDER BY rowId ASC LIMIT :limit")
    fun getInitialStreamsBoundedSync(sourceId: String, limit: Int): List<IptvStreamEntity>

    @Query("SELECT * FROM streams WHERE sourceId = :sourceId AND (normalizedTitle LIKE :pattern OR normalizedCategory LIKE :pattern) ORDER BY contentType ASC, rowId ASC LIMIT :limit")
    fun searchStreamsBoundedSync(sourceId: String, pattern: String, limit: Int): List<IptvStreamEntity>

    @Query("UPDATE streams SET contentType = :newType, categoryId = :newCatId WHERE sourceId = :sourceId AND LOWER(categoryName) = LOWER(:categoryName)")
    fun updateCategoryContentTypeSync(sourceId: String, categoryName: String, newType: String, newCatId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertStreamsBatchSync(items: List<IptvStreamEntity>)

    @Query("DELETE FROM streams WHERE sourceId = :sourceId")
    fun deleteStreamsForSourceSync(sourceId: String)

    @Query("SELECT itemId FROM favorites WHERE sourceId = :sourceId")
    fun getFavoriteItemIdsForSourceSync(sourceId: String): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE sourceId = :sourceId AND itemId = :itemId)")
    fun isFavoriteSync(sourceId: String, itemId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertFavoriteSync(fav: IptvFavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND itemId = :itemId")
    fun deleteFavoriteSync(sourceId: String, itemId: String)

    @Query("""
        SELECT COUNT(*) FROM streams s
        INNER JOIN favorites f ON s.sourceId = f.sourceId AND s.streamId = f.itemId
        WHERE f.sourceId = :sourceId AND (:contentType IS NULL OR s.contentType = :contentType)
    """)
    fun countFavoritesByTypeSync(sourceId: String, contentType: String?): Int

    @Query("""
        SELECT s.* FROM streams s
        INNER JOIN favorites f ON s.sourceId = f.sourceId AND s.streamId = f.itemId
        WHERE f.sourceId = :sourceId AND (:contentType IS NULL OR s.contentType = :contentType)
        ORDER BY f.addedMs DESC
        LIMIT :limit
    """)
    fun getFavoriteStreamsSqlSync(sourceId: String, contentType: String?, limit: Int = 500): List<IptvStreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRecentSync(recent: IptvRecentEntity)

    @Query("""
        DELETE FROM recents
        WHERE sourceId = :sourceId
          AND itemId NOT IN (
              SELECT itemId FROM recents WHERE sourceId = :sourceId ORDER BY watchedMs DESC LIMIT 80
          )
    """)
    fun trimRecentsSync(sourceId: String)

    @Query("""
        SELECT COUNT(*) FROM streams s
        INNER JOIN recents r ON s.sourceId = r.sourceId AND s.streamId = r.itemId
        WHERE r.sourceId = :sourceId AND (:contentType IS NULL OR s.contentType = :contentType)
    """)
    fun countRecentsByTypeSync(sourceId: String, contentType: String?): Int

    @Query("""
        SELECT s.* FROM streams s
        INNER JOIN recents r ON s.sourceId = r.sourceId AND s.streamId = r.itemId
        WHERE r.sourceId = :sourceId AND (:contentType IS NULL OR s.contentType = :contentType)
        ORDER BY r.watchedMs DESC
        LIMIT :limit
    """)
    fun getRecentStreamsSqlSync(sourceId: String, contentType: String?, limit: Int = 100): List<IptvStreamEntity>

    @Query("SELECT * FROM series_last_episode WHERE sourceId = :sourceId")
    fun getAllSeriesLastEpForSourceSync(sourceId: String): List<IptvSeriesLastEpEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSeriesLastEpSync(entity: IptvSeriesLastEpEntity)

    @Query("SELECT * FROM series_last_episode WHERE sourceId = :sourceId AND seriesKey = :seriesKey LIMIT 1")
    fun getSeriesLastEpSync(sourceId: String, seriesKey: String): IptvSeriesLastEpEntity?

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId")
    fun deleteFavoritesForSourceSync(sourceId: String)

    @Query("DELETE FROM recents WHERE sourceId = :sourceId")
    fun deleteRecentsForSourceSync(sourceId: String)

    @Query("DELETE FROM series_last_episode WHERE sourceId = :sourceId")
    fun deleteSeriesLastForSourceSync(sourceId: String)
}

fun IptvDao.replaceAllStreamsForSourceSync(sourceId: String, items: List<IptvStreamEntity>) {
    deleteStreamsForSourceSync(sourceId)
    val chunkSize = 1000
    var idx = 0
    while (idx < items.size) {
        val end = (idx + chunkSize).coerceAtMost(items.size)
        insertStreamsBatchSync(items.subList(idx, end))
        idx = end
    }
}

fun IptvDao.deleteAllDataForSourceSync(sourceId: String) {
    deleteSourceByIdSync(sourceId)
    deleteStreamsForSourceSync(sourceId)
    deleteFavoritesForSourceSync(sourceId)
    deleteRecentsForSourceSync(sourceId)
    deleteSeriesLastForSourceSync(sourceId)
}

@Database(
    entities = [
        IptvSourceEntity::class,
        IptvStreamEntity::class,
        IptvFavoriteEntity::class,
        IptvRecentEntity::class,
        IptvSeriesLastEpEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class IptvRoomDatabase : RoomDatabase() {
    abstract fun iptvDao(): IptvDao
}

object IptvRoomDbHolder {
    private const val DB_NAME = "pure_video_iptv_room.db"

    private val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `sources` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `serverOrM3uUrl` TEXT NOT NULL,
                    `username` TEXT NOT NULL,
                    `encryptedPassword` TEXT NOT NULL,
                    `lastUpdatedMs` INTEGER NOT NULL,
                    `liveCount` INTEGER NOT NULL,
                    `movieCount` INTEGER NOT NULL,
                    `seriesCount` INTEGER NOT NULL,
                    `radioCount` INTEGER NOT NULL,
                    `expDateSec` INTEGER NOT NULL,
                    `accountStatus` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `streams` (
                    `rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `streamId` TEXT NOT NULL,
                    `contentType` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `categoryName` TEXT NOT NULL,
                    `normalizedCategory` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `normalizedTitle` TEXT NOT NULL,
                    `streamUrl` TEXT NOT NULL,
                    `logoUrl` TEXT NOT NULL,
                    `rating` TEXT NOT NULL,
                    `plot` TEXT NOT NULL,
                    `releaseDate` TEXT NOT NULL,
                    `durationLabel` TEXT NOT NULL,
                    `seriesId` TEXT NOT NULL,
                    `seriesName` TEXT NOT NULL,
                    `seasonNum` INTEGER NOT NULL,
                    `episodeNum` INTEGER NOT NULL,
                    `containerExt` TEXT NOT NULL,
                    `httpUa` TEXT NOT NULL,
                    `httpRef` TEXT NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_streams_source_type_cat` ON `streams` (`sourceId`, `contentType`, `categoryName`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_streams_norm_title` ON `streams` (`sourceId`, `normalizedTitle`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_streams_norm_cat` ON `streams` (`sourceId`, `normalizedCategory`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_streams_source_item` ON `streams` (`sourceId`, `streamId`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `favorites` (
                    `sourceId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `addedMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `itemId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_fav_source_added` ON `favorites` (`sourceId`, `addedMs`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `recents` (
                    `sourceId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `watchedMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `itemId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_recent_source_watched` ON `recents` (`sourceId`, `watchedMs`)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `series_last_episode` (
                    `sourceId` TEXT NOT NULL,
                    `seriesKey` TEXT NOT NULL,
                    `seasonNumber` INTEGER NOT NULL,
                    `episodeNumber` INTEGER NOT NULL,
                    `episodeTitle` TEXT NOT NULL,
                    `streamUrl` TEXT NOT NULL,
                    `updatedMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `seriesKey`)
                )
            """.trimIndent())
        }
    }

    @Volatile
    private var instance: IptvRoomDatabase? = null

    fun getInstance(context: Context): IptvRoomDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                IptvRoomDatabase::class.java,
                DB_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
