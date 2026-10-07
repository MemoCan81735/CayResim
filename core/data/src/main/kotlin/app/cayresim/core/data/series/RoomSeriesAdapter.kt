package app.cayresim.core.data.series

import android.content.Context
import androidx.room.Room
import app.cayresim.core.boundary.IoDispatcher
import app.cayresim.core.boundary.SeriesBoundary
import app.cayresim.core.boundary.SeriesFailure
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.SeriesSnapshot
import app.cayresim.core.entity.SeriesEntity
import app.cayresim.core.entity.SeriesPhoto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Serien in Room; Regeln kommen aus SeriesEntity, Fehler werden Werte (R24). */
@Singleton
class RoomSeriesAdapter internal constructor(
    private val db: SeriesDatabase,
    private val io: CoroutineDispatcher,
) : SeriesBoundary {

    @Inject constructor(@ApplicationContext context: Context, @IoDispatcher io: CoroutineDispatcher) :
        this(Room.databaseBuilder(context, SeriesDatabase::class.java, SeriesDatabase.NAME).build(), io)

    private val dao get() = db.dao()

    override fun series(): Flow<List<SeriesSnapshot>> =
        combine(dao.observeSeries(), dao.observePhotos()) { rows, photos ->
            val byId = photos.groupBy { it.seriesId }
            rows.mapNotNull { r ->
                SeriesEntity.create(r.id, r.name, byId[r.id].orEmpty().map { SeriesPhoto(it.uri, it.takenAtMillis) })?.toSnapshot()
            }
        }.flowOn(io)

    override suspend fun create(name: String): SeriesResult = withContext(io) {
        val entity = SeriesEntity.create(0, name) ?: return@withContext SeriesResult.Failed(SeriesFailure.INVALID_NAME)
        runCatching {
            val id = dao.insert(SeriesRow(name = entity.name))
            SeriesResult.Ok(SeriesSnapshot(id, entity.name, emptyList()))
        }.getOrElse { SeriesResult.Failed(SeriesFailure.STORAGE) }
    }

    override suspend fun addPhoto(seriesId: Long, uri: String, takenAtMillis: Long): SeriesResult = withContext(io) {
        runCatching {
            val row = dao.series(seriesId) ?: return@withContext SeriesResult.Failed(SeriesFailure.NOT_FOUND)
            when (dao.addPhotoChecked(SeriesPhotoRow(seriesId, uri, takenAtMillis), SeriesEntity.MAX_PHOTOS)) {
                1 -> SeriesResult.Failed(SeriesFailure.DUPLICATE_PHOTO)
                2 -> SeriesResult.Failed(SeriesFailure.FULL)
                else -> {
                    val photos = dao.photos(seriesId).map { SeriesPhoto(it.uri, it.takenAtMillis) }
                    val e = SeriesEntity.create(row.id, row.name, photos) ?: return@withContext SeriesResult.Failed(SeriesFailure.STORAGE)
                    SeriesResult.Ok(e.toSnapshot())
                }
            }
        }.getOrElse { SeriesResult.Failed(SeriesFailure.STORAGE) }
    }

    override suspend fun delete(seriesId: Long): Boolean = withContext(io) {
        runCatching { dao.delete(seriesId) > 0 }.getOrDefault(false)
    }

    private fun SeriesEntity.toSnapshot() = SeriesSnapshot(id, name, photos.map { it.uri })

    internal companion object {
        fun inMemory(context: Context, io: CoroutineDispatcher) =
            RoomSeriesAdapter(Room.inMemoryDatabaseBuilder(context, SeriesDatabase::class.java).allowMainThreadQueries().build(), io)
    }
}
