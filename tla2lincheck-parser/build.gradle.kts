dependencies {
    api(project(":tla2lincheck-ir"))
    implementation(files("${rootProject.projectDir}/libs/tla2tools.jar"))
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
