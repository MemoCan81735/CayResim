plugins { id("cayresim.android.library") }
android { namespace = "app.cayresim.core.processing" }
dependencies {
    implementation(project(":core:boundary"))
    implementation(project(":core:entity"))
    implementation(project(":core:pure"))
}
