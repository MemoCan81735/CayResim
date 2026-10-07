package app.cayresim.core.data.series

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "series")
data class SeriesRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Formatversion je Zeile (R26). */
    val format: Int = FORMAT,
) { companion object { const val FORMAT = 1 } }

@Entity(
    tableName = "series_photo",
    primaryKeys = ["seriesId", "uri"],
    foreignKeys = [ForeignKey(entity = SeriesRow::class, parentColumns = ["id"], childColumns = ["seriesId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("seriesId")],
)
data class SeriesPhotoRow(val seriesId: Long, val uri: String, val takenAtMillis: Long)


@Dao
interface SeriesDao {
    @Query("SELECT * FROM series ORDER BY id")
    fun observeSeries(): Flow<List<SeriesRow>>

    @Query("SELECT * FROM series_photo")
    fun observePhotos(): Flow<List<SeriesPhotoRow>>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun series(id: Long): SeriesRow?

    @Query("SELECT * FROM series_photo WHERE seriesId = :id ORDER BY takenAtMillis")
    suspend fun photos(id: Long): List<SeriesPhotoRow>

    @Insert suspend fun insert(row: SeriesRow): Long

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPhoto(row: SeriesPhotoRow)

    @Query("DELETE FROM series WHERE id = :id") suspend fun delete(id: Long): Int

    @Transaction
    suspend fun addPhotoChecked(row: SeriesPhotoRow, max: Int): Int {
        val existing = photos(row.seriesId)
        if (existing.any { it.uri == row.uri }) return 1
        if (existing.size >= max) return 2
        insertPhoto(row); return 0
    }
}

@Database(entities = [SeriesRow::class, SeriesPhotoRow::class], version = 1, exportSchema = true)
abstract class SeriesDatabase : RoomDatabase() {
    abstract fun dao(): SeriesDao

    companion object { const val NAME = "series.db" }
}
