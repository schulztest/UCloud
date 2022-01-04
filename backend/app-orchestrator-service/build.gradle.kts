version = "2022.1.1-patch.2"

application {
    mainClassName = "dk.sdu.cloud.app.orchestrator.MainKt"
}

kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(project(":auth-service:api"))
            implementation(project(":app-store-service:api"))
            implementation(project(":accounting-service:api"))
            implementation(project(":accounting-service:util"))
            implementation(project(":mail-service:api"))
            implementation(project(":file-orchestrator-service:api"))
            implementation(project(":file-orchestrator-service:util"))
            implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.4")
        }
    }
}
