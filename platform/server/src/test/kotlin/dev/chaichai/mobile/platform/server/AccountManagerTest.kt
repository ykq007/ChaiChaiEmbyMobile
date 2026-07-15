package dev.chaichai.mobile.platform.server

import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.SignOutState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountManagerTest {
    @Test
    fun `sign out attempts final sync then removes scoped pending work cache and credentials`() = runTest {
        val fixture = Fixture(pending = false)
        fixture.manager(this).requestSignOut()
        advanceUntilIdle()

        assertEquals(listOf(fixture.scope), fixture.progress.synced)
        assertEquals(listOf(fixture.scope), fixture.progress.cleared)
        assertTrue(fixture.vault.restore() == null)
        assertTrue(fixture.signedOut)
    }

    @Test
    fun `unsynchronized progress requires confirmation and cancellation preserves private data`() = runTest {
        val fixture = Fixture(pending = true)
        val manager = fixture.manager(this)
        manager.requestSignOut()
        advanceUntilIdle()

        assertTrue(manager.signOutState.value is SignOutState.ConfirmationRequired)
        manager.cancelSignOut()
        assertEquals(SignOutState.Idle, manager.signOutState.value)
        assertFalse(fixture.vault.restore() == null)
        assertTrue(fixture.progress.cleared.isEmpty())
    }

    @Test
    fun `confirmed progress loss cleans only the signed in server user scope`() = runTest {
        val fixture = Fixture(pending = true)
        val manager = fixture.manager(this)
        manager.requestSignOut()
        advanceUntilIdle()
        manager.confirmProgressLoss()
        advanceUntilIdle()

        assertEquals(listOf(fixture.scope), fixture.progress.cleared)
        assertEquals(SignOutState.SignedOut, manager.signOutState.value)
    }

    private class Fixture(pending: Boolean) {
        val scope = HomeScope("server-a", "user-a")
        val vault = FakeVault()
        val progress = FakeProgress(pending)
        var signedOut = false
        fun manager(coroutineScope: kotlinx.coroutines.CoroutineScope) = AccountManager(
            coroutineScope, vault, progress,
            ServerPrivateDataCleaner(
                InMemoryHomeCache(), InMemoryMovieCache(), InMemorySeriesCache(), InMemorySearchCache(),
            ),
            { signedOut = true },
        )
    }

    private class FakeProgress(var pending: Boolean) : ProgressAccountSync {
        val synced = mutableListOf<HomeScope>()
        val cleared = mutableListOf<HomeScope>()
        override suspend fun finalSync(scope: HomeScope): Boolean { synced += scope; return !pending }
        override suspend fun hasPending(scope: HomeScope) = pending
        override suspend fun clear(scope: HomeScope) { cleared += scope; pending = false }
    }

    private class FakeVault : SessionVault {
        private var value: StoredSession? = StoredSession(
            ServerAddress.parse("https://example.test") .let { (it as AddressValidation.Valid).address },
            "server-a", "user-a", "User", AccessToken.fromRaw("token"), null, "Server",
        )
        override fun restore() = value
        override fun save(session: StoredSession) { value = session }
        override fun clear() { value = null }
    }
}
