package com.aai.steel.objecthunt

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aai.steel.objecthunt.data.PigeonDao
import com.aai.steel.objecthunt.data.PigeonDatabase
import com.aai.steel.objecthunt.data.PigeonEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for Room layer - PigeonDao + PigeonDatabase
 * Tests max 20 limit enforcement and oldest deletion
 *
 * Uses in-memory DB + Robolectric for JVM tests
 */
@RunWith(RobolectricTestRunner::class)
class PigeonDaoTest {

    private lateinit var db: PigeonDatabase
    private lateinit var dao: PigeonDao
    private val fakeImage = ByteArray(10) { it.toByte() } // small fake JPEG

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PigeonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.pigeonDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createEntity(
        id: Long = 0,
        timestamp: Long = System.currentTimeMillis(),
        type: String? = "Rock Pigeon"
    ): PigeonEntity {
        return PigeonEntity(
            id = id,
            timestamp = timestamp,
            pigeonType = type,
            confidence = 0.9f,
            features = "grey feathers",
            pigeonLocationInImage = "center",
            city = "San Francisco",
            description = "Test pigeon",
            rawResponse = "HAS_PIGEON: YES",
            imageBytes = fakeImage
        )
    }

    @Test
    fun insertAndGetAll() = runTest {
        val entity = createEntity()
        dao.insert(entity)

        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("Rock Pigeon", all[0].pigeonType)
        assertEquals("San Francisco", all[0].city)
    }

    @Test
    fun countReturnsCorrectNumber() = runTest {
        dao.insert(createEntity(timestamp = 1000))
        dao.insert(createEntity(timestamp = 2000))
        assertEquals(2, dao.count())
    }

    @Test
    fun getOldest_returnsEarliestTimestamp() = runTest {
        dao.insert(createEntity(timestamp = 3000, type = "Newest"))
        dao.insert(createEntity(timestamp = 1000, type = "Oldest"))
        dao.insert(createEntity(timestamp = 2000, type = "Middle"))

        val oldest = dao.getOldest()
        assertNotNull(oldest)
        assertEquals("Oldest", oldest?.pigeonType)
        assertEquals(1000L, oldest?.timestamp)
    }

    @Test
    fun deleteOldestN_removesCorrectNumber() = runTest {
        dao.insert(createEntity(timestamp = 1000))
        dao.insert(createEntity(timestamp = 2000))
        dao.insert(createEntity(timestamp = 3000))

        dao.deleteOldestN(2)

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals(3000L, remaining[0].timestamp)
    }

    @Test
    fun insertWithLimit_enforcesMax20() = runTest {
        // Insert 20 entries
        for (i in 1..20) {
            dao.insertWithLimit(createEntity(timestamp = i.toLong(), type = "Pigeon $i"), limit = 20)
        }
        assertEquals(20, dao.count())

        // Insert 21st - should delete oldest and keep 20
        dao.insertWithLimit(createEntity(timestamp = 21, type = "Pigeon 21"), limit = 20)

        assertEquals(20, dao.count())
        val all = dao.getAll()
        // Oldest (timestamp=1) should be gone, newest should exist
        assertFalse(all.any { it.pigeonType == "Pigeon 1" })
        assertTrue(all.any { it.pigeonType == "Pigeon 21" })
    }

    @Test
    fun insertWithLimit_deletesExactNumberWhenExceeding() = runTest {
        // Insert 25 without limit check first (via insert)
        for (i in 1..25) {
            dao.insert(createEntity(timestamp = i.toLong(), type = "Pigeon $i"))
        }
        assertEquals(25, dao.count())

        // Now insert with limit 20 - should delete 6 oldest (25 - 20 + 1 = 6)
        dao.insertWithLimit(createEntity(timestamp = 26, type = "Pigeon 26"), limit = 20)

        assertEquals(20, dao.count())
        val all = dao.getAll()
        // Should have timestamps 7..26
        val timestamps = all.map { it.timestamp }.sorted()
        assertEquals((7L..26L).toList(), timestamps)
    }

    @Test
    fun getAllFlow_emitsUpdates() = runTest {
        dao.insert(createEntity(type = "First"))
        val firstEmission = dao.getAllFlow().first()
        assertEquals(1, firstEmission.size)

        dao.insert(createEntity(type = "Second"))
        val secondEmission = dao.getAllFlow().first()
        assertEquals(2, secondEmission.size)
    }

    @Test
    fun deleteById_removesOnlyThatEntry() = runTest {
        val id1 = dao.insert(createEntity(type = "ToDelete"))
        val id2 = dao.insert(createEntity(type = "ToKeep"))

        dao.deleteById(id1)

        val remaining = dao.getAll()
        assertEquals(1, remaining.size)
        assertEquals("ToKeep", remaining[0].pigeonType)
    }

    @Test
    fun deleteAll_clearsDatabase() = runTest {
        dao.insert(createEntity())
        dao.insert(createEntity())
        assertEquals(2, dao.count())

        dao.deleteAll()
        assertEquals(0, dao.count())
    }
}
