version = "2021.3.0-alpha7"

application {
    mainClassName = "dk.sdu.cloud.file.ucloud.MainKt"
}

kotlin.sourceSets {
    val jvmMain by getting {
        dependencies {
            implementation(project(":auth-service:api"))
            implementation(project(":notification-service:api"))
            implementation(project(":accounting-service:api"))
            implementation(project(":sync-mounter-service:api"))
        }
    }
}
