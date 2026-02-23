plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(project(":tla2lincheck-parser"))
    implementation(project(":tla2lincheck-generator"))
}

gradlePlugin {
    plugins {
        create("tla2lincheck") {
            id = "io.github.tla2lincheck"
            implementationClass = "io.github.tla2lincheck.gradle.Tla2LincheckPlugin"
            displayName = "TLA+ to Lincheck Test Generator"
            description = "Generates Lincheck concurrent test classes from TLA+ specifications"
        }
    }
}
