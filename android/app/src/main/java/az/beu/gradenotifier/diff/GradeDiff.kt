package az.beu.gradenotifier.diff

import az.beu.gradenotifier.parser.Course
import az.beu.gradenotifier.parser.courseKey
import kotlinx.serialization.Serializable

/**
 * Comparison of two grade snapshots.
 *
 * A snapshot maps courseKey -> course record, so a poll only has to look up
 * the previous value of each mark.
 */

enum class ChangeType { MARK, ABSENCE }

@Serializable
data class Change(
    val type: ChangeType,
    val key: String,
    val semester: String,
    val code: String,
    val name: String,
    val label: String,
    val from: String,
    val to: String,
    val at: Long = 0L,
)

/** Index a parsed course list by course key. */
fun toSnapshot(courses: List<Course>): Map<String, Course> =
    courses.associateBy { courseKey(it) }

/**
 * Compare snapshots and describe what a teacher changed.
 *
 * A mark going from empty to a value is a change like any other: that is the
 * common case of a newly entered grade, so it must not be filtered out.
 */
fun diffSnapshots(
    previous: Map<String, Course>,
    current: Map<String, Course>,
    notifyAbsences: Boolean = true,
): List<Change> {
    val changes = mutableListOf<Change>()

    for ((key, course) in current) {
        val before = previous[key]

        for ((label, value) in course.marks) {
            val oldValue = before?.marks?.get(label).orEmpty()
            if (oldValue == value) continue
            // A course appearing with no marks yet is registration, not grading.
            if (before == null && value.isEmpty()) continue

            changes += Change(
                type = ChangeType.MARK,
                key = key,
                semester = course.semester,
                code = course.code,
                name = course.name,
                label = label,
                from = oldValue,
                to = value,
            )
        }

        if (notifyAbsences && before != null && course.absences != before.absences) {
            changes += Change(
                type = ChangeType.ABSENCE,
                key = key,
                semester = course.semester,
                code = course.code,
                name = course.name,
                label = "Qaib",
                from = before.absences,
                to = course.absences,
            )
        }
    }

    return changes
}

/** Human readable one-liner for a change, used as notification text. */
fun describeChange(change: Change): String = when {
    change.type == ChangeType.ABSENCE ->
        "${change.code} — absences ${change.from.ifEmpty { "0" }} → ${change.to.ifEmpty { "0" }}"
    change.from.isEmpty() -> "${change.code} — ${change.label}: ${change.to}"
    else -> "${change.code} — ${change.label}: ${change.from} → ${change.to}"
}
