package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext

class GatewayApiTrafficAdapter(client: KubernetesClient) : CanaryTrafficAdapter {
    private val client = deploymentClient(client)
    override val id = "gateway-api"
    override val permissions = listOf(ResourcePermission("gateway.networking.k8s.io", "httproutes", listOf("get", "update")))
    private val routeContext = ResourceDefinitionContext.Builder().withGroup("gateway.networking.k8s.io")
        .withVersion("v1").withPlural("httproutes").withKind("HTTPRoute").withNamespaced(true).build()

    private fun routeName(configuration: DeploymentConfiguration): String =
        configuration.trafficOptions["routeName"] ?: configuration.canaryRoute
        ?: throw DeploymentValidationException("gateway-api requires trafficOptions.routeName")

    override fun validate(configuration: DeploymentConfiguration) {
        if (routeName(configuration).isBlank()) throw DeploymentValidationException("HTTPRoute name is required")
    }

    override fun capture(application: DeploymentApplication, configuration: DeploymentConfiguration): TrafficSnapshot {
        validate(configuration)
        val current = route(application.namespace, routeName(configuration))
        val rules = rules(current)
        if (rules.size != 1 || parentRefs(current).size != 1) throw DeploymentValidationException("canary HTTPRoute must have exactly one rule and one parent")
        val refs = refs(current)
        if (refs.size != 1 || refs[0]["name"] != application.serviceName || (refs[0]["namespace"] ?: application.namespace) != application.namespace ||
            (refs[0]["kind"] ?: "Service") != "Service" || (refs[0]["group"] ?: "") != "" || refs[0]["port"] == null) {
            throw DeploymentValidationException("HTTPRoute must point only to the production Service in this namespace")
        }
        if (!routeReady(current)) throw DeploymentConflictException("HTTPRoute must be Accepted with ResolvedRefs")
        return TrafficSnapshot(id, listOf(claim(current, application.id)))
    }

    override fun setWeight(application: DeploymentApplication, configuration: DeploymentConfiguration, run: DeploymentRun, weight: Int?) {
        val original = run.snapshot?.traffic?.also {
            require(it.adapterId == id) { "traffic snapshot belongs to a different adapter" }
        }?.resources?.singleOrNull() ?: requireNotNull(run.snapshot?.route)
        val current = route(application.namespace, routeName(configuration))
        requireUid(current.metadata.uid, original.metadata.uid)
        val stable = refs(original).single().toMutableMap()
        val desired = if (weight == null) listOf(stable) else listOf(
            stable + ("weight" to 100 - weight), stable + mapOf("name" to KubernetesDeploymentWorkloads.previewName(run), "weight" to weight),
        )
        val existing = refs(current)
        if (existing == desired) return
        val allowed = existing == refs(original) || (existing.size == 2 && existing[0].filterKeys { it != "weight" } == stable.filterKeys { it != "weight" } &&
            existing[1].filterKeys { it != "weight" && it != "name" } == stable.filterKeys { it != "weight" && it != "name" } && existing[1]["name"] == KubernetesDeploymentWorkloads.previewName(run))
        if (!allowed) throw DeploymentConflictException("HTTPRoute backends changed outside this run")
        rules(current).single()["backendRefs"] = desired
        routes(application.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
    }

    override fun isReady(application: DeploymentApplication, configuration: DeploymentConfiguration) =
        routeReady(route(application.namespace, routeName(configuration)))

    private fun routes(namespace: String) = client.genericKubernetesResources(routeContext).inNamespace(namespace)
    private fun route(namespace: String, name: String) = routes(namespace).withName(name).get()
        ?: throw DeploymentValidationException("HTTPRoute '$name' does not exist")

    @Suppress("UNCHECKED_CAST")
    private fun rules(route: GenericKubernetesResource): List<MutableMap<String, Any>> =
        (route.additionalProperties["spec"] as Map<String, Any>)["rules"] as List<MutableMap<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun refs(route: GenericKubernetesResource): List<Map<String, Any>> = rules(route).single()["backendRefs"] as List<Map<String, Any>>

    @Suppress("UNCHECKED_CAST")
    private fun routeReady(route: GenericKubernetesResource): Boolean {
        val parents = (route.additionalProperties["status"] as? Map<String, Any>)?.get("parents") as? List<Map<String, Any>> ?: return false
        val expected = parentRefs(route).singleOrNull() ?: return false
        val matching = parents.filter { parent ->
            val ref = parent["parentRef"] as? Map<String, Any> ?: return@filter false
            ref["name"] == expected["name"] && ref["sectionName"] == expected["sectionName"] && ref["port"] == expected["port"] &&
                (ref["namespace"] ?: route.metadata.namespace) == (expected["namespace"] ?: route.metadata.namespace)
        }
        return matching.isNotEmpty() && matching.all { parent ->
            val conditions = parent["conditions"] as? List<Map<String, Any>> ?: return@all false
            listOf("Accepted", "ResolvedRefs").all { type -> conditions.any {
                it["type"] == type && it["status"] == "True" && (it["observedGeneration"] as? Number)?.toLong() == route.metadata.generation
            } }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parentRefs(route: GenericKubernetesResource): List<Map<String, Any>> =
        (route.additionalProperties["spec"] as Map<String, Any>)["parentRefs"] as? List<Map<String, Any>> ?: emptyList()

    private fun requireUid(actual: String?, expected: String?) {
        if (actual != expected) throw DeploymentConflictException("resource was replaced outside this run")
    }

    private fun <T : HasMetadata> claim(resource: T, applicationId: String): T {
        val owner = resource.metadata.annotations?.get(KubernetesDeploymentWorkloads.APP_ANNOTATION)
        if (owner != null && owner != applicationId) throw DeploymentConflictException("resource is managed by another deployment application")
        if (owner == applicationId) return resource
        resource.metadata.annotations = resource.metadata.annotations.orEmpty() + (KubernetesDeploymentWorkloads.APP_ANNOTATION to applicationId)
        return client.resource(resource).lockResourceVersion(resource.metadata.resourceVersion).update()
    }

}
