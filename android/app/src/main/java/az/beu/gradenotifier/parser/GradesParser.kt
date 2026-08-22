package az.beu.gradenotifier.parser

import kotlinx.serialization.Serializable

/**
 * Parsing of the BEU student portal grade table (my.beu.edu.az?mod=grades).
 *
 * The portal serves grades either as a full HTML page or, when the semester
 * selector is used, as a {"CODE":"1","DATA":"<html fragment>"} envelope.
 * Both forms are accepted here.
 *
 * Parsing is regex based rather than DOM based so that it stays a pure Kotlin
 * function, testable on the JVM without an emulator.
 */

/** One course row, with its marks kept as a label -> value map. */
@Serializable
data class Course(
    val semester: String,
    val code: String,
    val section: String = "",
    val group: String = "",
    val lab: String = "",
    val name: String = "",
    val ects: String = "",
    val absences: String = "",
    val marks: Map<String, String> = emptyMap(),
)

/** Header labels describing the course itself rather than a mark. */
private val META_COLUMNS = setOf("Dərs kodu", "N", "M", "L", "Dərsin adı", "AKTS", "Qaib")

private const val COURSE_CODE_HEADER = "Dərs kodu"

private val SEMESTER_HEADING = Regex("""<b>\s*([\d-]+\s+\d+\.\s*semester)\s*</b>""")
private val ROW = Regex("""<tr[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
private val CELL = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
private val TAG = Regex("""<[^>]+>""")
private val NAMED_ENTITY = Regex("""&(?:amp|lt|gt|quot|#39|nbsp);""")
private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
private val WHITESPACE = Regex("""\s+""")
private val PASSWORD_FIELD = Regex("""type=["']password["']""", RegexOption.IGNORE_CASE)
private val LOGIN_MODULE = Regex("""mod=login""", RegexOption.IGNORE_CASE)
private val JSON_DATA_KEY = Regex(""""DATA"\s*:\s*"""")

private val HTML_ENTITIES = mapOf(
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&nbsp;" to " ",
)

private fun decodeEntities(text: String): String {
    val named = NAMED_ENTITY.replace(text) { HTML_ENTITIES[it.value] ?: it.value }
    return NUMERIC_ENTITY.replace(named) { it.groupValues[1].toInt().toChar().toString() }
}

/** Strip tags and collapse whitespace to get a cell's visible text. */
private fun cellText(cellHtml: String): String =
    WHITESPACE.replace(decodeEntities(TAG.replace(cellHtml, "")).replace('\u00a0', ' '), " ").trim()

private fun rowCells(rowHtml: String): List<String> =
    CELL.findAll(rowHtml).map { cellText(it.groupValues[1]) }.toList()

/**
 * Decode the JSON string escapes the portal applies to the HTML it embeds in
 * the DATA field. Only the escapes PHP's json_encode can emit are handled.
 */
private fun unescapeJsonString(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i + 1 >= raw.length) {
            out.append(c)
            i += 1
            continue
        }
        when (val escape = raw[i + 1]) {
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'u' -> {
                if (i + 5 < raw.length) {
                    val hex = raw.substring(i + 2, i + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                }
                out.append(escape)
            }
            else -> out.append(escape)
        }
        i += 2
    }
    return out.toString()
}

/**
 * Unwrap the AJAX envelope when given a JSON response body, otherwise return
 * the body unchanged.
 */
fun unwrapResponse(body: String): String {
    if (!body.trimStart().startsWith("{")) return body
    val key = JSON_DATA_KEY.find(body) ?: return ""

    // Scanned rather than matched with a regex: the DATA field is a whole HTML
    // page, and an alternation over escapes blows the regex engine's stack.
    val start = key.range.last + 1
    var end = start
    while (end < body.length) {
        when (body[end]) {
            '\\' -> end += 2
            '"' -> return unescapeJsonString(body.substring(start, end))
            else -> end += 1
        }
    }
    return ""
}

/**
 * True when the response is the portal's login screen rather than grades,
 * i.e. the session cookie has expired.
 */
fun isLoggedOut(body: String): Boolean {
    // Unwrap first: PHP escapes non-ASCII, so the header only becomes findable
    // once the DATA field's \uXXXX escapes have been decoded.
    val html = unwrapResponse(body)
    if (html.contains(COURSE_CODE_HEADER)) return false
    return PASSWORD_FIELD.containsMatchIn(html) || LOGIN_MODULE.containsMatchIn(html)
}

/**
 * Stable identity for a course within a semester. Section and group are
 * included because a course code can repeat across sections.
 */
fun courseKey(course: Course): String =
    listOf(course.semester, course.code, course.section, course.group).joinToString("|")

private val SEMESTER_RANK = Regex("""(\d{4})-\d{4}\s+(\d+)""")

/** Rank of a semester label, so the newest one can be picked out. */
private fun semesterRank(semester: String): Int {
    val match = SEMESTER_RANK.find(semester) ?: return 0
    return match.groupValues[1].toInt() * 10 + match.groupValues[2].toInt()
}

/** Latest semester present in a course list, by the year/term in its label. */
fun latestSemester(courses: List<Course>): String? =
    courses.maxByOrNull { semesterRank(it.semester) }?.semester

/**
 * Extract every course row from the grades HTML.
 *
 * Columns are mapped via each table's own header row: the layout genuinely
 * differs between semesters (older ones carry DVM, newer ones SEM and an
 * optional LAB), so fixed cell indexes silently mislabel marks.
 */
fun parseGrades(body: String): List<Course> {
    val html = unwrapResponse(body)
    val courses = mutableListOf<Course>()

    val headings = SEMESTER_HEADING.findAll(html).toList()
    headings.forEachIndexed { index, heading ->
        val semester = WHITESPACE.replace(heading.groupValues[1], " ").trim()
        val sectionStart = heading.range.last + 1
        val sectionEnd = headings.getOrNull(index + 1)?.range?.first ?: html.length
        val section = html.substring(sectionStart, sectionEnd)

        val rows = ROW.findAll(section).map { rowCells(it.groupValues[1]) }.toList()
        val header = rows.firstOrNull { it.firstOrNull() == COURSE_CODE_HEADER } ?: return@forEachIndexed

        for (cells in rows) {
            if (cells === header || cells.size != header.size) continue

            val meta = mutableMapOf<String, String>()
            val marks = linkedMapOf<String, String>()
            header.forEachIndexed { column, label ->
                val value = cells[column]
                if (label in META_COLUMNS) meta[label] = value else marks[label] = value
            }

            val code = meta[COURSE_CODE_HEADER].orEmpty()
            if (code.isEmpty()) continue

            courses += Course(
                semester = semester,
                code = code,
                section = meta["N"].orEmpty(),
                group = meta["M"].orEmpty(),
                lab = meta["L"].orEmpty(),
                name = meta["Dərsin adı"].orEmpty(),
                ects = meta["AKTS"].orEmpty(),
                absences = meta["Qaib"].orEmpty(),
                marks = marks,
            )
        }
    }

    return courses
}
