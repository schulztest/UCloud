version = "2022.1.3"

application {
    mainClassName = "dk.sdu.cloud.audit.ingestion.MainKt"
}

kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(project(":auth-service:api"))
        }
    }
}