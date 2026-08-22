package az.beu.gradenotifier.poll

import az.beu.gradenotifier.data.AppState
import az.beu.gradenotifier.data.CheckState
import az.beu.gradenotifier.data.InMemoryStateStore
import az.beu.gradenotifier.data.Settings
import az.beu.gradenotifier.diff.Change
import az.beu.gradenotifier.net.Portal
import az.beu.gradenotifier.net.PortalResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors test/checker.test.js. */
class GradeCheckerTest {
    private val pageHtml: String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("grades-tables.html"))
            .bufferedReader()
            .readText()

    private val fixedNow = { 1_700_000_000_000L }

    private class RecordingNotifier : ChangeNotifier {
        val batches = mutableListOf<List<Change>>()
        override suspend fun notify(changes: List<Change>) {
            batches += changes
        }
    }

    private fun portalOf(body: String) = Portal { PortalResult.Body(body) }
    private fun failingPortal(message: String) = Portal { PortalResult.Failure(message) }

    private class Harness(initial: AppState = AppState(), val body: String) {
        val store = InMemoryStateStore(initial)
        val notifier = RecordingNotifier()
        var portal: Portal = Portal { PortalResult.Body(body) }

        suspend fun run() = runCheck(portal, store, notifier, now = { 1_700_000_000_000L })
    }

    /** Blank out one mark in the stored snapshot, as if it had not been graded yet. */
    private suspend fun InMemoryStateStore.blankOneMark(label: String) = update { state ->
        val snapshot = checkNotNull(state.snapshot)
        val key = snapshot.keys.first()
        val course = snapshot.getValue(key)
        state.copy(snapshot = snapshot + (key to course.copy(marks = course.marks + (label to ""))))
    }

    @Test
    fun `first check stores a baseline and notifies nothing`() = runTest {
        val harness = Harness(body = pageHtml)
        val result = harness.run()

        assertEquals(CheckState.BASELINE, result.state)
        assertEquals(emptyList<List<Change>>(), harness.notifier.batches)
        assertEquals(24, harness.store.read().snapshot?.size)
    }

    @Test
    fun `second check notifies only what changed`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.run()
        harness.store.blankOneMark("SSI")

        val result = harness.run()
        assertEquals(CheckState.OK, result.state)
        assertEquals(1, result.changes.size)
        assertEquals(1, harness.notifier.batches.size)
        assertEquals("SSI", harness.notifier.batches[0][0].label)
    }

    @Test
    fun `an unchanged portal produces no notification`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.run()
        val result = harness.run()

        assertEquals(CheckState.OK, result.state)
        assertEquals(emptyList<Change>(), result.changes)
        assertEquals(emptyList<List<Change>>(), harness.notifier.batches)
    }

    @Test
    fun `recent changes are recorded for the grades screen`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.run()
        harness.store.blankOneMark("SSI")
        harness.run()

        val recent = harness.store.read().recentChanges
        assertEquals(1, recent.size)
        assertEquals(fixedNow(), recent[0].at)
    }

    @Test
    fun `an expired session is reported without clearing the snapshot`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.run()
        val snapshot = harness.store.read().snapshot

        harness.portal = portalOf("""<form><input type="password"></form>""")
        val result = harness.run()

        assertEquals(CheckState.LOGGED_OUT, result.state)
        assertEquals(emptyList<List<Change>>(), harness.notifier.batches)
        assertSame(snapshot, harness.store.read().snapshot)
    }

    @Test
    fun `a network failure is reported as an error`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.portal = failingPortal("offline")
        val result = harness.run()

        assertEquals(CheckState.ERROR, result.state)
        assertEquals("offline", result.message)
        assertEquals(CheckState.ERROR, harness.store.read().status?.state)
    }

    @Test
    fun `an unreadable grades page does not overwrite the snapshot`() = runTest {
        val harness = Harness(body = pageHtml)
        harness.run()
        val snapshot = harness.store.read().snapshot

        harness.portal = portalOf("<html><body>Dərs kodu but no table</body></html>")
        val result = harness.run()

        assertEquals(CheckState.ERROR, result.state)
        assertSame(snapshot, harness.store.read().snapshot)
    }

    @Test
    fun `absence alerts respect the stored setting`() = runTest {
        val harness = Harness(
            initial = AppState(settings = Settings(notifyAbsences = false)),
            body = pageHtml,
        )
        harness.run()
        harness.store.update { state ->
            val snapshot = checkNotNull(state.snapshot)
            val key = snapshot.keys.first()
            state.copy(snapshot = snapshot + (key to snapshot.getValue(key).copy(absences = "0")))
        }

        assertEquals(emptyList<Change>(), harness.run().changes)
    }

    @Test
    fun `a paused check does not touch the portal but records the pause`() = runTest {
        val harness = Harness(
            initial = AppState(settings = Settings(enabled = false)),
            body = pageHtml,
        )
        harness.portal = failingPortal("the portal must not be contacted")

        val result = harness.run()
        assertEquals(CheckState.PAUSED, result.state)
        assertEquals(CheckState.PAUSED, harness.store.read().status?.state)
    }

    @Test
    fun `a check the user asked for runs even while paused`() = runTest {
        val store = InMemoryStateStore(AppState(settings = Settings(enabled = false)))
        val result = runCheck(portalOf(pageHtml), store, RecordingNotifier(), force = true)

        assertEquals(CheckState.BASELINE, result.state)
        assertTrue((store.read().snapshot?.size ?: 0) > 0)
    }
}
