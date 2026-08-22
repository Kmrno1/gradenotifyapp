package az.beu.gradenotifier.poll

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import az.beu.gradenotifier.data.CheckState
import az.beu.gradenotifier.data.MIN_INTERVAL_MINUTES
import az.beu.gradenotifier.data.PrefsStateStore
import az.beu.gradenotifier.net.HttpPortal
import az.beu.gradenotifier.notify.AndroidNotifier
import java.util.concurrent.TimeUnit

const val POLL_WORK_NAME = "beu-grade-poll"

/**
 * The background poll.
 *
 * Failures return success rather than retry: the next periodic run is the
 * retry, and WorkManager's exponential backoff would otherwise push the
 * interval out much further than the user asked for.
 */
class GradeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = PrefsStateStore(applicationContext)
        runCheck(
            portal = HttpPortal(applicationContext),
            store = store,
            notifier = AndroidNotifier(applicationContext),
        )
        return Result.success()
    }
}

/**
 * (Re)schedule the periodic poll. Called on app start and whenever the
 * interval changes, so the request is replaced rather than kept.
 */
fun schedulePolling(context: Context, intervalMinutes: Int) {
    val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()

    val request = PeriodicWorkRequestBuilder<GradeWorker>(interval, TimeUnit.MINUTES)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        POLL_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

fun cancelPolling(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(POLL_WORK_NAME)
}

/** Apply the scheduling implied by the stored settings. */
suspend fun applySchedule(context: Context, store: PrefsStateStore) {
    val settings = store.read().settings
    if (settings.enabled) schedulePolling(context, settings.intervalMinutes) else cancelPolling(context)
}

/** A check triggered from the UI, which runs even while polling is paused. */
suspend fun checkNow(context: Context, store: PrefsStateStore): CheckState =
    runCheck(
        portal = HttpPortal(context),
        store = store,
        notifier = AndroidNotifier(context),
        force = true,
    ).state
