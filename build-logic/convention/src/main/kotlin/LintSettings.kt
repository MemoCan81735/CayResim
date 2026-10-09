import com.android.build.api.dsl.Lint
import org.gradle.api.Project

/**
 * S-005: gleiche Lint-Regeln fuer alle Android-Module. Alte Funde stehen in `lint-baseline.xml` des Moduls,
 * jeder neue Fund (auch eine Warnung) macht den Analyse-Job rot.
 */
fun Lint.cayresimDefaults(project: Project) {
    baseline = project.file("lint-baseline.xml")
    abortOnError = true
    warningsAsErrors = true
    // Lint laeuft im Analyse-Job; der Release-Bau prueft nicht noch einmal (sonst schreibt er dort Baselines).
    checkReleaseBuilds = false
    // Hinweise, die sich mit neuen Bibliotheksversionen oder dem Datum aendern, haben nichts mit dem Code zu tun.
    disable += setOf(
        "GradleDependency",
        "NewerVersionAvailable",
        "AndroidGradlePluginVersion",
        "OldTargetApi",
        "ExpiringTargetSdkVersion",
    )
}
