version = "0.22.0"

application {
    mainClassName = "dk.sdu.cloud.app.kubernetes.MainKt"
}

kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(project(":auth-service:api"))
            implementation(project(":app-orchestrator-service:api"))
            implementation(project(":project-service:api"))
        }
    }
}
