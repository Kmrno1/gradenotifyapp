package az.beu.gradenotifier.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors test/parser.test.js so the Kotlin port is held to the same
 * behaviour as the extension's parser, against the same fixtures.
 */
class GradesParserTest {
    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader()
            .readText()

    private val pageHtml = fixture("grades-tables.html")
    private val ajaxJson = fixture("grades-response.json")

    @Test
    fun `parses every course row across all semesters`() {
        val courses = parseGrades(pageHtml)
        assertEquals(24, courses.size)
        assertEquals(4, courses.map { it.semester }.toSet().size)
    }

    @Test
    fun `reads course metadata`() {
        val course = parseGrades(pageHtml).first { it.code == "CS 105" }
        assertEquals("2024-2025 1. semester", course.semester)
        assertEquals("Kompüter elmlərinə giriş", course.name)
        assertEquals("8", course.ects)
    }

    @Test
    fun `maps marks by header label, not by column index`() {
        val courses = parseGrades(pageHtml)
        // Older semesters carry DVM; from 2025-2026 it is replaced by SEM.
        val older = courses.first { it.code == "CS 105" }
        assertTrue("DVM" in older.marks)
        assertFalse("SEM" in older.marks)

        val newer = courses.first { it.code == "CENG 203" }
        assertTrue("SEM" in newer.marks)
        assertFalse("DVM" in newer.marks)
    }

    @Test
    fun `LAB belongs to the semester, and is blank for courses without a lab`() {
        val courses = parseGrades(pageHtml)
        // LAB appears in the 2025-2026 1st semester header, so every course in
        // that table carries the column even without a lab component.
        assertNotEquals("", courses.first { it.code == "CENG 205" }.marks["LAB"])
        assertEquals("", courses.first { it.code == "CENG 203" }.marks["LAB"])
    }

    @Test
    fun `represents an ungraded mark as an empty string`() {
        // Re-exam columns are blank for anyone who did not sit one.
        assertTrue(parseGrades(pageHtml).all { it.marks["ƏI"] == "" })
    }

    @Test
    fun `unwraps the JSON envelope returned by the semester selector`() {
        assertTrue(unwrapResponse(ajaxJson).startsWith("<div id=\"divShowStudGrades\""))
        assertEquals(parseGrades(pageHtml), parseGrades(ajaxJson))
    }

    @Test
    fun `passes through a body that is not JSON`() {
        assertEquals("<table></table>", unwrapResponse("<table></table>"))
    }

    @Test
    fun `returns no courses for unrelated html rather than throwing`() {
        assertEquals(emptyList<Course>(), parseGrades("<html><body>hello</body></html>"))
    }

    @Test
    fun `detects the login page`() {
        assertTrue(isLoggedOut("""<form><input type="password" name="pw"></form>"""))
        assertFalse(isLoggedOut(pageHtml))
        // The envelope escapes non-ASCII, so the header is only visible once
        // the DATA field has been decoded.
        assertFalse(isLoggedOut(ajaxJson))
    }

    @Test
    fun `course key distinguishes sections of the same course`() {
        val base = Course(semester = "2025-2026 1. semester", code = "CS 105", section = "01", group = "02")
        assertNotEquals(courseKey(base), courseKey(base.copy(section = "02")))
        assertEquals(courseKey(base), courseKey(base.copy()))
    }

    @Test
    fun `picks the newest semester by its label`() {
        val courses = parseGrades(pageHtml)
        assertEquals("2025-2026 2. semester", latestSemester(courses))
        assertEquals(null, latestSemester(emptyList()))
    }
}
