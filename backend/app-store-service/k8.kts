//DEPS dk.sdu.cloud:k8-resources:0.1.0
package dk.sdu.cloud.k8

bundle {
    name = "app-store"
    version = "2022.2.33"

    withAmbassador(null) {
        addSimpleMapping("/api/hpc")
    }

    val deployment = withDeployment {
        deployment.spec.replicas = Configuration.retrieve("defaultScale", "Default scale", 1)
        injectSecret("elasticsearch-credentials")
    }

    withPostgresMigration(deployment)
    withAdHocJob(deployment, "app-to-elastic", { listOf("--run-script", "--migrate-apps-to-elastic") })
    withAdHocJob(deployment, "move-tags", { listOf("--move") })
    withAdHocJob(deployment, "resize-logos", { listOf("--resize-logos")}) {}
}
