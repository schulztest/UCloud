version = "0.2.0-rc0"

application {
    mainClassName = "dk.sdu.cloud.slack.MainKt"
}

dependencies {
    implementation(project(":auth-service:api"))
}
