package az.beu.gradenotifier.diff

import az.beu.gradenotifier.parser.Course
import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors test/diff.test.js. */
class GradeDiffTest {
    private fun course(marks: Map<String, String>, absences: String = "4") = Course(
        semester = "2025-2026 1. semester",
        code = "CENG 203",
        section = "01",
        group = "02",
        name = "Kompüterlərin arxitekturası",
        absences = absences,
        marks = marks,
    )

    private fun snapshotOf(marks: Map<String, String>, absences: String = "4") =
        toSnapshot(listOf(course(marks, absences)))

    @Test
    fun `reports a newly entered grade`() {
        val changes = diffSnapshots(snapshotOf(mapOf("SSI" to "")), snapshotOf(mapOf("SSI" to "47")))
        assertEquals(1, changes.size)
        assertEquals(Triple("SSI", "", "47"), changes[0].let { Triple(it.label, it.from, it.to) })
    }

    @Test
    fun `reports a corrected grade`() {
        val changes = diffSnapshots(snapshotOf(mapOf("SSI" to "44")), snapshotOf(mapOf("SSI" to "47")))
        assertEquals(1, changes.size)
        assertEquals("44", changes[0].from)
    }

    @Test
    fun `reports a withdrawn grade`() {
        val changes = diffSnapshots(snapshotOf(mapOf("SSI" to "44")), snapshotOf(mapOf("SSI" to "")))
        assertEquals(1, changes.size)
        assertEquals("", changes[0].to)
    }

    @Test
    fun `stays silent when nothing changed`() {
        val snapshot = snapshotOf(mapOf("SDF1" to "9", "SSI" to "32"))
        assertEquals(emptyList<Change>(), diffSnapshots(snapshot, snapshot))
    }

    @Test
    fun `reports each changed mark separately`() {
        val changes = diffSnapshots(
            snapshotOf(mapOf("SDF1" to "9", "SDF2" to "", "SSI" to "")),
            snapshotOf(mapOf("SDF1" to "9", "SDF2" to "8", "SSI" to "32")),
        )
        assertEquals(listOf("SDF2", "SSI"), changes.map { it.label })
    }

    @Test
    fun `a newly registered course with no marks is not an update`() {
        val changes = diffSnapshots(emptyMap(), snapshotOf(mapOf("SDF1" to "", "SSI" to "")))
        assertEquals(emptyList<Change>(), changes)
    }

    @Test
    fun `a course that appears already graded is reported`() {
        val changes = diffSnapshots(emptyMap(), snapshotOf(mapOf("SDF1" to "10", "SSI" to "")))
        assertEquals(listOf("SDF1"), changes.map { it.label })
    }

    @Test
    fun `a mark column added mid-semester is reported once it has a value`() {
        val changes = diffSnapshots(
            snapshotOf(mapOf("SDF1" to "9")),
            snapshotOf(mapOf("SDF1" to "9", "LAB" to "10")),
        )
        assertEquals(listOf("LAB"), changes.map { it.label })
    }

    @Test
    fun `reports rising absences when enabled`() {
        val changes = diffSnapshots(snapshotOf(emptyMap(), "4"), snapshotOf(emptyMap(), "6"))
        assertEquals(1, changes.size)
        assertEquals(ChangeType.ABSENCE, changes[0].type)
    }

    @Test
    fun `absence alerts can be switched off`() {
        val changes = diffSnapshots(
            snapshotOf(emptyMap(), "4"),
            snapshotOf(emptyMap(), "6"),
            notifyAbsences = false,
        )
        assertEquals(emptyList<Change>(), changes)
    }

    @Test
    fun `describes changes for notification text`() {
        val mark = Change(
            type = ChangeType.MARK,
            key = "k",
            semester = "s",
            code = "CS 105",
            name = "n",
            label = "SSI",
            from = "",
            to = "47",
        )
        assertEquals("CS 105 — SSI: 47", describeChange(mark))
        assertEquals("CS 105 — SSI: 44 → 47", describeChange(mark.copy(from = "44")))
        assertEquals(
            "CS 105 — absences 4 → 6",
            describeChange(mark.copy(type = ChangeType.ABSENCE, label = "Qaib", from = "4", to = "6")),
        )
    }
}
