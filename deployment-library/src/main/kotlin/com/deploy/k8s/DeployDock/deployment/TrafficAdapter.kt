package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient

data class ResourcePermission(val group: String, val resource: String, val verbs: List<String>)

data class TrafficSnapshot(
    val adapterId: String,
    val resources: List<GenericKubernetesResource> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
)

interface CanaryTrafficAdapter {
    val id: String
    val permissions: List<ResourcePermission>
    fun validate(configuration: DeploymentConfiguration)
    fun capture(application: DeploymentApplication, configuration: DeploymentConfiguration): TrafficSnapshot
    fun setWeight(application: DeploymentApplication, configuration: DeploymentConfiguration, run: DeploymentRun, weight: Int?)
    fun isReady(application: DeploymentApplication, configuration: DeploymentConfiguration): Boolean
}

data class DeploymentCapabilities(
    val webStrategies: Set<WebDeploymentStrategy>,
    val batchModes: Set<BatchDeploymentMode>,
    val configuredTrafficAdapters: Set<String>,
)

fun interface DeploymentAuthorization {
    fun authorize(principal: String, namespace: String, permissions: List<ResourcePermission>)
}

object ClientCredentialsAuthorization : DeploymentAuthorization {
    override fun authorize(principal: String, namespace: String, permissions: List<ResourcePermission>) = Unit
}

class SubjectAccessReviewAuthorization(client: KubernetesClient) : DeploymentAuthorization {
    private val client = deploymentClient(client)
    override fun authorize(principal: String, namespace: String, permissions: List<ResourcePermission>) {
        permissions.forEach { permission -> permission.verbs.forEach { verb ->
            val review = SubjectAccessReviewBuilder().withNewSpec().withUser(principal)
                .withNewResourceAttributes().withNamespace(namespace).withGroup(permission.group)
                .withResource(permission.resource).withVerb(verb).endResourceAttributes().endSpec().build()
            if (client.authorization().v1().subjectAccessReview().create(review).status?.allowed != true) throw DeploymentForbiddenException()
        } }
    }
}
