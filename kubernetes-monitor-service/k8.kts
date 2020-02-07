//DEPS dk.sdu.cloud:k8-resources:0.1.0
package dk.sdu.cloud.k8

bundle {
    name = "kubernetes-monitor"
    version = "0.1.2"

    withAmbassador("/api/kubernetes/monitor") {}

    val deployment = withDeployment {
        deployment.spec.replicas = 2
        deployment.spec.template.spec.serviceAccountName = this@bundle.name
    }

    withPostgresMigration(deployment)

    withClusterServiceAccount {
        addRule(
            apiGroups = listOf(""),
            resources = listOf("pods"),
            verbs = listOf("get", "watch", "list", "delete", "pods", "create", "update")
        )
    }
}
