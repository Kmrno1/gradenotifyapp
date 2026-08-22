package az.beu.gradenotifier.data

import az.beu.gradenotifier.diff.Change
import az.beu.gradenotifier.parser.Course
import kotlinx.serialization.Serializable

enum class CheckState { OK, BASELINE, LOGGED_OUT, ERROR, PAUSED }

@Serializable
data class Settings(
    /**
     * Minutes between polls. WorkManager will not run periodic work more often
     * than every 15 minutes, so that is the floor, and Doze can stretch any
     * interval further.
     */
    val intervalMinutes: Int = MIN_INTERVAL_MINUTES,
    val notifyAbsences: Boolean = true,
    val enabled: Boolean = true,
)

const val MIN_INTERVAL_MINUTES = 15
val INTERVAL_CHOICES = listOf(15, 30, 60)

@Serializable
data class Status(
    val at: Long,
    val state: CheckState,
    val message: String = "",
    val changeCount: Int = 0,
)

@Serializable
data class AppState(
    /** Absent until the first successful check records a baseline. */
    val snapshot: Map<String, Course>? = null,
    /** Latest parsed courses, kept for the grades screen. */
    val courses: List<Course> = emptyList(),
    val recentChanges: List<Change> = emptyList(),
    val status: Status? = null,
    val settings: Settings = Settings(),
)
