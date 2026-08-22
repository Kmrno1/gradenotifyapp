package az.beu.gradenotifier.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import az.beu.gradenotifier.R
import az.beu.gradenotifier.diff.Change
import az.beu.gradenotifier.diff.ChangeType
import az.beu.gradenotifier.diff.describeChange
import az.beu.gradenotifier.poll.ChangeNotifier
import az.beu.gradenotifier.ui.MainActivity

const val CHANNEL_GRADES = "grades"

/** Above this many changes at once, summarise instead of listing every one. */
private const val SUMMARY_THRESHOLD = 4

private const val GROUP_CHANGES = "az.beu.gradenotifier.CHANGES"
private var nextId = 1000

fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)

    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL_GRADES,
            context.getString(R.string.channel_grades),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_grades_description) },
    )
}

private fun openApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class AndroidNotifier(private val context: Context) : ChangeNotifier {
    override suspend fun notify(changes: List<Change>) {
        if (changes.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (changes.size > SUMMARY_THRESHOLD) {
            manager.post(summary(changes))
            return
        }

        changes.forEach { manager.post(single(it)) }
    }

    private fun NotificationManagerCompat.post(notification: Notification) {
        try {
            notify(nextId++, notification)
        } catch (denied: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post.
        }
    }

    private fun single(change: Change): Notification {
        val title = if (change.type == ChangeType.ABSENCE) {
            context.getString(R.string.absence_changed)
        } else {
            context.getString(R.string.grade_changed)
        }
        return base()
            .setContentTitle(title)
            .setContentText(describeChange(change))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${describeChange(change)}\n${change.name}"),
            )
            .build()
    }

    private fun summary(changes: List<Change>): Notification {
        val style = NotificationCompat.InboxStyle()
        changes.take(SUMMARY_THRESHOLD + 1).forEach { style.addLine(describeChange(it)) }
        return base()
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.changes_title,
                    changes.size,
                    changes.size,
                ),
            )
            .setContentText(changes.take(2).joinToString(", ") { describeChange(it) })
            .setStyle(style)
            .build()
    }

    private fun base() = NotificationCompat.Builder(context, CHANNEL_GRADES)
        .setSmallIcon(R.drawable.ic_stat_grades)
        .setContentIntent(openApp(context))
        .setGroup(GROUP_CHANGES)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setDefaults(NotificationCompat.DEFAULT_ALL)
}
