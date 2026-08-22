package az.beu.gradenotifier.net

/** The portal endpoints the app touches. */
object PortalUrls {
    const val BASE = "https://my.beu.edu.az/"
    const val GRADES = "https://my.beu.edu.az/index.php?mod=grades"
}

/** Outcome of one attempt to read the grades page. */
sealed interface PortalResult {
    data class Body(val text: String) : PortalResult
    data class Failure(val message: String) : PortalResult
}

/** Reading the grades page, abstracted so the poll loop is testable. */
fun interface Portal {
    suspend fun fetchGrades(): PortalResult
}
