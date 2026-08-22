package az.beu.gradenotifier.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import az.beu.gradenotifier.data.AppState
import az.beu.gradenotifier.data.CheckState
import az.beu.gradenotifier.data.INTERVAL_CHOICES
import az.beu.gradenotifier.data.Settings
import az.beu.gradenotifier.data.Status
import az.beu.gradenotifier.diff.Change
import az.beu.gradenotifier.diff.describeChange
import az.beu.gradenotifier.parser.Course
import az.beu.gradenotifier.parser.latestSemester
import java.text.DateFormat
import java.util.Date

private const val MAX_RECENT_SHOWN = 8

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: AppState,
    checking: Boolean,
    signedIn: Boolean,
    onCheckNow: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("BEU Grades") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                StatusCard(
                    status = state.status,
                    signedIn = signedIn,
                    checking = checking,
                    onCheckNow = onCheckNow,
                    onSignIn = onSignIn,
                )
            }

            item { SectionTitle("Recent changes") }
            if (state.recentChanges.isEmpty()) {
                item { Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(state.recentChanges.take(MAX_RECENT_SHOWN)) { ChangeRow(it) }
            }

            item { SectionTitle("Grades") }
            item { GradesTable(state.courses) }

            item { SectionTitle("Settings") }
            item {
                SettingsCard(
                    settings = state.settings,
                    signedIn = signedIn,
                    onSettingsChange = onSettingsChange,
                    onSignOut = onSignOut,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun StatusCard(
    status: Status?,
    signedIn: Boolean,
    checking: Boolean,
    onCheckNow: () -> Unit,
    onSignIn: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(statusLabel(status, signedIn), style = MaterialTheme.typography.bodyLarge)
            Text(
                "Last checked ${formatTime(status?.at)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheckNow, enabled = !checking && signedIn) {
                    Text(if (checking) "Checking…" else "Check now")
                }
                if (!signedIn || status?.state == CheckState.LOGGED_OUT) {
                    TextButton(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }
    }
}

private fun statusLabel(status: Status?, signedIn: Boolean): String = when {
    !signedIn -> "Sign in to the portal once to start watching."
    status == null -> "No check yet."
    status.state == CheckState.OK -> "Up to date."
    status.state == CheckState.BASELINE ->
        "Grades recorded — you will be notified from the next check."
    status.state == CheckState.LOGGED_OUT -> "Session expired — sign in again."
    status.state == CheckState.PAUSED -> "Checking is paused."
    else -> status.message.ifEmpty { "Something went wrong." }
}

@Composable
private fun ChangeRow(change: Change) {
    Column {
        Text(describeChange(change), style = MaterialTheme.typography.bodyLarge)
        Text(
            "${change.name} · ${formatTime(change.at)}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GradesTable(courses: List<Course>) {
    if (courses.isEmpty()) {
        Text("No grades stored yet.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    val semester = latestSemester(courses)
    val rows = courses.filter { it.semester == semester }
    // Mark columns vary per semester, so take them from the data itself.
    val labels = rows.flatMap { it.marks.keys }.distinct()

    Column {
        Text(semester.orEmpty(), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {
                MarkCell("Course", header = true, wide = true)
                labels.forEach { MarkCell(it, header = true) }
            }
            HorizontalDivider()
            rows.forEach { course ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarkCell(course.code, wide = true)
                    labels.forEach { MarkCell(course.marks[it]?.ifEmpty { "—" } ?: "—") }
                }
            }
        }
    }
}

@Composable
private fun MarkCell(text: String, header: Boolean = false, wide: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.width(if (wide) 96.dp else 44.dp).padding(vertical = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun SettingsCard(
    settings: Settings,
    signedIn: Boolean,
    onSettingsChange: ((Settings) -> Settings) -> Unit,
    onSignOut: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Check every", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_CHOICES.forEach { minutes ->
                    AssistChip(
                        onClick = { onSettingsChange { it.copy(intervalMinutes = minutes) } },
                        label = { Text(if (minutes < 60) "$minutes min" else "1 hour") },
                        enabled = settings.intervalMinutes != minutes,
                    )
                }
            }
            Text(
                "Android will not check more often than every 15 minutes, and delays " +
                    "checks further while the phone is asleep.",
                style = MaterialTheme.typography.bodySmall,
            )

            SettingSwitch(
                label = "Notify about absences",
                checked = settings.notifyAbsences,
                onChange = { value -> onSettingsChange { it.copy(notifyAbsences = value) } },
            )
            SettingSwitch(
                label = "Checking enabled",
                checked = settings.enabled,
                onChange = { value -> onSettingsChange { it.copy(enabled = value) } },
            )

            if (signedIn) {
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun formatTime(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return "never"
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestamp))
}
