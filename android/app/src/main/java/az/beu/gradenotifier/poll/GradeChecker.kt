package az.beu.gradenotifier.poll

import az.beu.gradenotifier.data.AppState
import az.beu.gradenotifier.data.CheckState
import az.beu.gradenotifier.data.StateStore
import az.beu.gradenotifier.data.Status
import az.beu.gradenotifier.diff.Change
import az.beu.gradenotifier.diff.diffSnapshots
import az.beu.gradenotifier.diff.toSnapshot
import az.beu.gradenotifier.net.Portal
import az.beu.gradenotifier.net.PortalResult
import az.beu.gradenotifier.parser.Course
import az.beu.gradenotifier.parser.isLoggedOut
import az.beu.gradenotifier.parser.parseGrades

/** Kept for the app's "recent activity" list. */
private const val MAX_RECENT_CHANGES = 30

data class CheckResult(
    val state: CheckState,
    val changes: List<Change> = emptyList(),
    val courses: List<Course> = emptyList(),
    val message: String = "",
)

/** Delivers notifications for the changes found by a check. */
fun interface ChangeNotifier {
    suspend fun notify(changes: List<Change>)
}

/**
 * Run one check.
 *
 * The first successful check only records a baseline: without a previous
 * snapshot every existing mark would look new and bury the user in
 * notifications for grades they already know about.
 *
 * @param force run even while checking is paused, for a check the user asked for.
 */
suspend fun runCheck(
    portal: Portal,
    store: StateStore,
    notifier: ChangeNotifier,
    force: Boolean = false,
    now: () -> Long = System::currentTimeMillis,
): CheckResult {
    val state = store.read()

    if (!state.settings.enabled && !force) {
        return store.finish(CheckResult(CheckState.PAUSED, message = "Checking is paused"), now)
    }

    val body = when (val result = portal.fetchGrades()) {
        is PortalResult.Failure ->
            return store.finish(CheckResult(CheckState.ERROR, message = result.message), now)
        is PortalResult.Body -> result.text
    }

    if (isLoggedOut(body)) {
        return store.finish(
            CheckResult(CheckState.LOGGED_OUT, message = "Session expired — sign in again"),
            now,
        )
    }

    val courses = parseGrades(body)
    if (courses.isEmpty()) {
        return store.finish(
            CheckResult(CheckState.ERROR, message = "Grades page loaded but no courses could be read"),
            now,
        )
    }

    val snapshot = toSnapshot(courses)

    if (state.snapshot == null) {
        val result = CheckResult(CheckState.BASELINE, courses = courses)
        store.update {
            it.copy(
                snapshot = snapshot,
                courses = courses,
                status = Status(now(), CheckState.BASELINE),
            )
        }
        return result
    }

    val changes = diffSnapshots(state.snapshot, snapshot, state.settings.notifyAbsences)
    val stamped = changes.map { it.copy(at = now()) }

    store.update {
        it.copy(
            snapshot = snapshot,
            courses = courses,
            recentChanges = (stamped + it.recentChanges).take(MAX_RECENT_CHANGES),
            status = Status(now(), CheckState.OK, changeCount = changes.size),
        )
    }

    if (stamped.isNotEmpty()) notifier.notify(stamped)

    return CheckResult(CheckState.OK, changes = stamped, courses = courses)
}

/**
 * Record an outcome that carries no new grades. The stored snapshot is left
 * alone so that a failed or signed-out poll cannot cause the next successful
 * one to re-announce grades the user has already seen.
 */
private suspend fun StateStore.finish(result: CheckResult, now: () -> Long): CheckResult {
    update { state: AppState ->
        state.copy(status = Status(now(), result.state, result.message))
    }
    return result
}
