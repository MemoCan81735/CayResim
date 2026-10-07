package app.cayresim.core.data.series

import androidx.test.core.app.ApplicationProvider
import app.cayresim.core.boundary.SeriesFailure
import app.cayresim.core.boundary.SeriesResult
import app.cayresim.core.boundary.contract.SeriesBoundaryContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Derselbe Serien-Vertrag wie beim Fake, hier gegen echtes Room (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSeriesAdapterTest {
    private fun adapter() = RoomSeriesAdapter.inMemory(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)

    @Test fun `Room erfuellt den Serien-Vertrag`() = runTest {
        SeriesBoundaryContract.all.forEach { (_, case) -> case(adapter()) }
    }

    @Test fun `Loeschen einer Serie loescht ihre Fotos mit`() = runTest {
        val a = adapter(); val id = (a.create("A") as SeriesResult.Ok).series.id
        a.addPhoto(id, "u", 1); a.delete(id)
        val again = (a.create("A") as SeriesResult.Ok).series.id
        assertIs<SeriesResult.Ok>(a.addPhoto(again, "u", 1))
    }

    @Test fun `Volle Serie wird gemeldet`() = runTest {
        val a = adapter(); val id = (a.create("Voll") as SeriesResult.Ok).series.id
        repeat(2_000) { a.addPhoto(id, "u$it", it.toLong()) }
        assertEquals(SeriesResult.Failed(SeriesFailure.FULL), a.addPhoto(id, "x", 1))
    }
}
