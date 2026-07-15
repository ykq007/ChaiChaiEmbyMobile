package dev.chaichai.mobile.platform.server

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.chaichai.mobile.core.contracts.HomeScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressOutboxPersistenceTest {
    @Test
    fun pendingProgressSurvivesDatabaseRestartAndRemainsServerUserScoped() {
        runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "progress-test-${System.nanoTime()}.db"
        val a = HomeScope("server-a", "user-a")
        val b = HomeScope("server-b", "user-b")
        try {
            val firstDatabase = Room.databaseBuilder(context, ProgressOutboxDatabase::class.java, databaseName).build()
            try {
                val database = firstDatabase
                val store = RoomProgressOutbox(database.dao())
                store.save(progress(a, 100, 20))
                repeat(3) { store.failed(store.pending(a).single()) }
                store.save(progress(a, 120, 25))
                store.save(progress(a, 80, 10))
                store.save(progress(b, 200, 30))
            } finally { firstDatabase.close() }

            val secondDatabase = Room.databaseBuilder(context, ProgressOutboxDatabase::class.java, databaseName).build()
            try {
                val database = secondDatabase
                val restarted = RoomProgressOutbox(database.dao())
                assertEquals(listOf(120L), restarted.pending(a).map { it.positionTicks })
                assertEquals(3, restarted.pending(a).single().attempts)
                assertEquals(listOf(200L), restarted.pending(b).map { it.positionTicks })
                restarted.clear(a)
                assertTrue(restarted.pending(a).isEmpty())
                assertEquals(1, restarted.pending(b).size)
            } finally { secondDatabase.close() }
        } finally {
            context.deleteDatabase(databaseName)
        }
        }
    }

    private fun progress(scope: HomeScope, position: Long, recordedAt: Long) = PendingProgress(
        scope, "movie", "source", "session", PlaybackMethod.DirectPlay, 1_000, position,
        false, PlaybackProgressEvent.TimeUpdate, recordedAt,
    )
}
