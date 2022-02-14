//DEPS dk.sdu.cloud:k8-resources:0.1.2
package dk.sdu.cloud.k8

bundle {
    name = "app-aau"
    version = "2022.1.7"
    
    withAmbassador() {
        addSimpleMapping("/ucloud/aau")
    }
    
    val deployment = withDeployment {
        deployment.spec.replicas = 1
        injectSecret("ucloud-provider-tokens-aau")
    }
    
    withPostgresMigration(deployment)
}