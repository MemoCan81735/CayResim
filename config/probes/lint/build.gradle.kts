// S-005 Lint-Selbstpruefung: enthaelt absichtlich einen Fund (HardcodedText) und keine Baseline.
// Wird nur mit -Pprobes eingebunden (settings.gradle.kts), kommt nie in die App.
plugins { id("cayresim.android.library") }
android {
    namespace = "app.cayresim.probe.lint"
    lint { baseline = null }
}
