/**
 * Parsing of the BEU student portal grade table (my.beu.edu.az?mod=grades).
 *
 * The portal serves grades either as a full HTML page or, when the semester
 * selector is used, as a {"CODE":"1","DATA":"<html fragment>"} envelope.
 * Both forms are accepted here.
 *
 * Parsing is regex based on purpose: MV3 service workers have no DOMParser.
 */

/** Header labels describing the course itself rather than a mark. */
const META_COLUMNS = {
  'Dərs kodu': 'code',
  N: 'section',
  M: 'group',
  L: 'lab',
  'Dərsin adı': 'name',
  AKTS: 'ects',
  Qaib: 'absences',
};

const COURSE_CODE_HEADER = 'Dərs kodu';
const SEMESTER_HEADING = /<b>\s*([\d-]+\s+\d+\.\s*semester)\s*<\/b>/g;
const ROW = /<tr[^>]*>([\s\S]*?)<\/tr>/g;
const CELL = /<td[^>]*>([\s\S]*?)<\/td>/g;

const HTML_ENTITIES = {
  '&amp;': '&',
  '&lt;': '<',
  '&gt;': '>',
  '&quot;': '"',
  '&#39;': "'",
  '&nbsp;': ' ',
};

function decodeEntities(text) {
  return text
    .replace(/&(?:amp|lt|gt|quot|#39|nbsp);/g, (entity) => HTML_ENTITIES[entity])
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)));
}

/** Strip tags and collapse whitespace to get a cell's visible text. */
function cellText(cellHtml) {
  return decodeEntities(cellHtml.replace(/<[^>]+>/g, ''))
    .replace(/\u00a0/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function rowCells(rowHtml) {
  return [...rowHtml.matchAll(CELL)].map((match) => cellText(match[1]));
}

/**
 * Unwrap the AJAX envelope when given a JSON response body.
 * @param {string} body raw response text
 * @returns {string} HTML to parse
 */
export function unwrapResponse(body) {
  const trimmed = body.trimStart();
  if (!trimmed.startsWith('{')) return body;
  try {
    const payload = JSON.parse(trimmed);
    return typeof payload.DATA === 'string' ? payload.DATA : '';
  } catch {
    return body;
  }
}

/**
 * True when the response is the portal's login screen rather than grades,
 * i.e. the session cookie has expired.
 * @param {string} body raw response text
 */
export function isLoggedOut(body) {
  if (body.includes(COURSE_CODE_HEADER)) return false;
  return /type=["']password["']/i.test(body) || /mod=login/i.test(body);
}

/**
 * Stable identity for a course within a semester. Section and group are
 * included because a course code can repeat across sections.
 * @param {object} course
 */
export function courseKey(course) {
  return [course.semester, course.code, course.section, course.group]
    .map((part) => part ?? '')
    .join('|');
}

/**
 * Extract every course row from the grades HTML.
 *
 * Columns are mapped via each table's own header row: the layout genuinely
 * differs between semesters (older ones carry DVM, newer ones SEM and an
 * optional LAB), so fixed cell indexes silently mislabel marks.
 *
 * @param {string} body raw response text (page HTML or JSON envelope)
 * @returns {Array<{semester: string, code: string, name: string, marks: object}>}
 */
export function parseGrades(body) {
  const html = unwrapResponse(body);
  const courses = [];

  const headings = [...html.matchAll(SEMESTER_HEADING)];
  for (let i = 0; i < headings.length; i += 1) {
    const semester = headings[i][1].replace(/\s+/g, ' ').trim();
    const sectionStart = headings[i].index + headings[i][0].length;
    const sectionEnd = i + 1 < headings.length ? headings[i + 1].index : html.length;
    const section = html.slice(sectionStart, sectionEnd);

    const rows = [...section.matchAll(ROW)].map((match) => rowCells(match[1]));
    const header = rows.find((cells) => cells[0] === COURSE_CODE_HEADER);
    if (!header) continue;

    for (const cells of rows) {
      if (cells === header || cells.length !== header.length) continue;

      const course = { semester, marks: {} };
      header.forEach((label, index) => {
        const value = cells[index];
        if (label in META_COLUMNS) {
          course[META_COLUMNS[label]] = value;
        } else {
          course.marks[label] = value;
        }
      });

      if (course.code) courses.push(course);
    }
  }

  return courses;
}
