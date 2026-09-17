package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.PodSpec
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import org.springframework.stereotype.Component

@Component
class KubernetesDeploymentWorkloads(client: KubernetesClient) {
    private val client = deploymentClient(client)
    private val routeContext = ResourceDefinitionContext.Builder().withGroup("gateway.networking.k8s.io")
        .withVersion("v1").withPlural("httproutes").withKind("HTTPRoute").withNamespaced(true).build()

    fun authorize(principal: String, app: DeploymentApplication, config: DeploymentConfiguration) {
        val permissions = if (app.kind == ApplicationKind.BATCH) {
            listOf(Triple("batch", "cronjobs", listOf("get", "update")))
        } else if (config.webStrategy == WebDeploymentStrategy.ROLLING) {
            listOf(Triple("apps", "deployments", listOf("get", "update")))
        } else {
            listOf(Triple("apps", "deployments", listOf("get", "create", "update")),
                Triple("", "services", listOf("get", "list", "create", "update")),
                Triple("discovery.k8s.io", "endpointslices", listOf("list"))) +
                if (config.webStrategy == WebDeploymentStrategy.CANARY) listOf(Triple("gateway.networking.k8s.io", "httproutes", listOf("get", "update"))) else emptyList()
        }
        permissions.forEach { (group, resource, verbs) -> verbs.forEach { verb ->
            val review = SubjectAccessReviewBuilder().withNewSpec().withUser(principal)
                .withNewResourceAttributes().withNamespace(app.namespace).withGroup(group)
                .withResource(resource).withVerb(verb).endResourceAttributes().endSpec().build()
            if (client.authorization().v1().subjectAccessReview().create(review).status?.allowed != true) throw DeploymentForbiddenException()
        } }
    }

    fun snapshot(app: DeploymentApplication, config: DeploymentConfiguration): DeploymentSnapshot {
        if (app.kind == ApplicationKind.BATCH) {
            val jobs = config.batchTargets.ifEmpty { listOf(app.name) }.map { target ->
                client.batch().v1().cronjobs().inNamespace(app.namespace).withName(target).get()
                    ?: throw DeploymentValidationException("CronJob '$target' does not exist")
            }
            jobs.forEach { container(it.spec.jobTemplate.spec.template.spec, app.containerName) }
            return DeploymentSnapshot(cronJobs = jobs.map { claim(it, app.id) })
        }
        val source = deployment(app.namespace, app.activeDeployment)
        if (!ready(source)) throw DeploymentConflictException("source Deployment must be fully ready")
        container(source.spec.template.spec, app.containerName)
        if (config.webStrategy == WebDeploymentStrategy.ROLLING) return DeploymentSnapshot(deployment = claim(source, app.id))
        val service = service(app.namespace, requireNotNull(app.serviceName))
        if (service.spec.publishNotReadyAddresses == true) throw DeploymentValidationException("publishNotReadyAddresses must be disabled")
        if (service.spec.selector.isNullOrEmpty() || !service.spec.selector.all { source.spec.template.metadata.labels[it.key] == it.value }) {
            throw DeploymentValidationException("production Service must select the source Deployment")
        }
        val key = source.spec.selector.matchLabels.orEmpty().keys.firstOrNull { it in service.spec.selector }
            ?: throw DeploymentValidationException("Service and Deployment need a shared matchLabels key to isolate the new version")
        val route = if (config.webStrategy == WebDeploymentStrategy.CANARY) {
            route(app.namespace, requireNotNull(config.canaryRoute)).also { current ->
                val rules = rules(current)
                if (rules.size != 1 || parentRefs(current).size != 1) throw DeploymentValidationException("canary HTTPRoute must have exactly one rule and one parent")
                val refs = refs(current)
                if (refs.size != 1 || refs[0]["name"] != app.serviceName || (refs[0]["namespace"] ?: app.namespace) != app.namespace ||
                    (refs[0]["kind"] ?: "Service") != "Service" || (refs[0]["group"] ?: "") != "" || refs[0]["port"] == null) {
                    throw DeploymentValidationException("HTTPRoute must point only to the production Service in this namespace")
                }
                if (!routeReady(current)) throw DeploymentConflictException("HTTPRoute must be Accepted with ResolvedRefs")
            }
        } else null
        return DeploymentSnapshot(claim(source, app.id), claim(service, app.id), route?.let { claim(it, app.id) }, isolationKey = key)
    }

    fun createPreview(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun): DeploymentExecutionResult {
        val snapshot = requireNotNull(run.snapshot)
        val source = requireNotNull(snapshot.deployment)
        val name = candidateName(run)
        val label = requireNotNull(snapshot.isolationKey)
        val desired = DeploymentBuilder(source).build().apply {
            metadata = ObjectMetaBuilder().withName(name).withNamespace(app.namespace)
                .addToLabels(RUN_LABEL, run.id).addToAnnotations(APP_ANNOTATION, app.id).build()
            status = null
            spec.replicas = config.replicas ?: source.spec.replicas
            spec.selector.matchLabels = mapOf(RUN_LABEL to run.id)
            spec.selector.matchExpressions = emptyList()
            spec.template.metadata.labels = spec.template.metadata.labels + mapOf(label to run.id, RUN_LABEL to run.id)
            spec.template.metadata.annotations = spec.template.metadata.annotations.orEmpty() + (APP_ANNOTATION to app.id)
            container(spec.template.spec, app.containerName).image = config.image
        }
        val collision = client.services().inNamespace(app.namespace).list().items.firstOrNull { service ->
            service.metadata.labels?.get(RUN_LABEL) != run.id && !service.spec.selector.isNullOrEmpty() &&
                service.spec.selector.all { desired.spec.template.metadata.labels[it.key] == it.value }
        }
        if (collision != null) throw DeploymentValidationException("preview would receive traffic from Service '${collision.metadata.name}'; isolate its selector first")
        val existing = client.apps().deployments().inNamespace(app.namespace).withName(name).get()
        if (existing == null) client.apps().deployments().inNamespace(app.namespace).resource(desired).create()
        else requireOwned(existing.metadata.labels, run)
        val previewName = previewName(run)
        val original = requireNotNull(snapshot.service)
        val preview = ServiceBuilder().withNewMetadata().withName(previewName).withNamespace(app.namespace)
            .addToLabels(RUN_LABEL, run.id).endMetadata().withNewSpec().withType("ClusterIP")
            .addToSelector(RUN_LABEL, run.id).withPorts(original.spec.ports.map { port ->
                io.fabric8.kubernetes.api.model.ServicePortBuilder(port).withNodePort(null).build()
            }).endSpec().build()
        val found = client.services().inNamespace(app.namespace).withName(previewName).get()
        if (found == null) client.services().inNamespace(app.namespace).resource(preview).create()
        else requireOwned(found.metadata.labels, run)
        return DeploymentExecutionResult("WEB_${config.webStrategy}", listOf(
            DeploymentResourcePlan("apps/v1", "Deployment", app.namespace, name, "new version"),
            DeploymentResourcePlan("v1", "Service", app.namespace, previewName, "preview only"),
        ), previewName, preview.spec.ports.map { it.port })
    }

    fun previewReady(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun): Boolean {
        val candidate = deployment(app.namespace, candidateName(run))
        requireOwned(candidate.metadata.labels, run)
        if (container(candidate.spec.template.spec, app.containerName).image != config.image) throw DeploymentConflictException("preview image changed outside this run")
        val expectedReplicas = config.replicas ?: requireNotNull(run.snapshot?.deployment).spec.replicas ?: 1
        if (candidate.spec.replicas != expectedReplicas) throw DeploymentConflictException("preview replica count changed outside this run")
        return ready(candidate) && endpointsReady(app.namespace, previewName(run), run.id)
    }

    fun endpointsReady(namespace: String, name: String, runId: String? = null): Boolean {
        val endpoints = client.discovery().v1().endpointSlices().inNamespace(namespace)
            .withLabel("kubernetes.io/service-name", name).list().items.flatMap { it.endpoints }
            .filter { it.conditions?.ready == true && it.conditions?.terminating != true }
        if (endpoints.isEmpty()) return false
        return runId == null || endpoints.all { it.targetRef?.name?.startsWith(candidateName(runId) + "-") == true }
    }

    fun originalEndpointsReady(app: DeploymentApplication, run: DeploymentRun): Boolean {
        val name = requireNotNull(run.snapshot?.deployment).metadata.name
        val endpoints = client.discovery().v1().endpointSlices().inNamespace(app.namespace)
            .withLabel("kubernetes.io/service-name", requireNotNull(app.serviceName)).list().items
            .flatMap { it.endpoints }.filter { it.conditions?.ready == true && it.conditions?.terminating != true }
        return endpoints.isNotEmpty() && endpoints.all { it.targetRef?.name?.startsWith("$name-") == true }
    }

    fun rolling(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun) {
        val original = requireNotNull(run.snapshot?.deployment)
        val current = deployment(app.namespace, original.metadata.name)
        requireUid(current.metadata.uid, original.metadata.uid)
        if (container(current.spec.template.spec, app.containerName).image == config.image &&
            (config.replicas == null || current.spec.replicas == config.replicas)) return
        if (current.spec.template != original.spec.template) throw DeploymentConflictException("Deployment changed outside this run")
        container(current.spec.template.spec, app.containerName).image = config.image
        if (config.replicas != null) current.spec.replicas = config.replicas
        client.apps().deployments().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
    }

    fun rollingReady(app: DeploymentApplication, run: DeploymentRun, expectedImage: String? = null): Boolean {
        val original = requireNotNull(run.snapshot?.deployment)
        val current = deployment(app.namespace, original.metadata.name)
        requireUid(current.metadata.uid, original.metadata.uid)
        val expected = expectedImage ?: container(original.spec.template.spec, app.containerName).image
        if (container(current.spec.template.spec, app.containerName).image != expected) throw DeploymentConflictException("Deployment image changed outside this run")
        return ready(current)
    }

    fun switchService(app: DeploymentApplication, run: DeploymentRun, restore: Boolean = false) {
        val original = requireNotNull(run.snapshot?.service)
        val current = service(app.namespace, original.metadata.name)
        requireUid(current.metadata.uid, original.metadata.uid)
        val candidateSelector = mapOf(RUN_LABEL to run.id)
        val desired = if (restore) original.spec.selector else candidateSelector
        if (current.spec.selector == desired) return
        val expected = if (restore) candidateSelector else original.spec.selector
        if (current.spec.selector != expected) throw DeploymentConflictException("Service selector changed outside this run")
        current.spec.selector = desired
        client.services().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
    }

    fun setCanaryWeight(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun, weight: Int?) {
        val original = requireNotNull(run.snapshot?.route)
        val current = route(app.namespace, requireNotNull(config.canaryRoute))
        requireUid(current.metadata.uid, original.metadata.uid)
        val stable = refs(original).single().toMutableMap()
        val desired = if (weight == null) listOf(stable) else listOf(
            stable + ("weight" to 100 - weight), stable + mapOf("name" to previewName(run), "weight" to weight),
        )
        val existing = refs(current)
        if (existing == desired) return
        val allowed = existing == refs(original) || (existing.size == 2 && existing[0].filterKeys { it != "weight" } == stable.filterKeys { it != "weight" } &&
            existing[1].filterKeys { it != "weight" && it != "name" } == stable.filterKeys { it != "weight" && it != "name" } && existing[1]["name"] == previewName(run))
        if (!allowed) throw DeploymentConflictException("HTTPRoute backends changed outside this run")
        rules(current).single()["backendRefs"] = desired
        routes(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
    }

    fun canaryRouteReady(app: DeploymentApplication, config: DeploymentConfiguration) = routeReady(route(app.namespace, requireNotNull(config.canaryRoute)))

    fun updateBatch(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun, restore: Boolean = false) {
        requireNotNull(run.snapshot).cronJobs.forEach { original ->
            val current = client.batch().v1().cronjobs().inNamespace(app.namespace).withName(original.metadata.name).get()
                ?: throw DeploymentConflictException("CronJob disappeared")
            requireUid(current.metadata.uid, original.metadata.uid)
            val target = container(current.spec.jobTemplate.spec.template.spec, app.containerName)
            val previous = container(original.spec.jobTemplate.spec.template.spec, app.containerName).image
            val desired = if (restore) previous else config.image
            if (target.image != desired) {
                if (target.image != if (restore) config.image else previous) throw DeploymentConflictException("CronJob image changed outside this run")
                target.image = desired
                client.batch().v1().cronjobs().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
            }
        }
    }

    fun restoreRolling(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun) {
        val original = requireNotNull(run.snapshot?.deployment)
        val current = deployment(app.namespace, original.metadata.name)
        requireUid(current.metadata.uid, original.metadata.uid)
        val target = container(current.spec.template.spec, app.containerName)
        val previous = container(original.spec.template.spec, app.containerName).image
        if (target.image != previous && target.image != config.image) throw DeploymentConflictException("Deployment image changed outside this run")
        if (target.image == previous && current.spec.replicas == original.spec.replicas) return
        target.image = previous
        current.spec.replicas = original.spec.replicas
        client.apps().deployments().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).replace()
    }

    fun retirePreview(app: DeploymentApplication, run: DeploymentRun) {
        val current = client.apps().deployments().inNamespace(app.namespace).withName(candidateName(run)).get() ?: return
        requireOwned(current.metadata.labels, run)
        if (current.spec.replicas == 0) return
        current.spec.replicas = 0
        client.apps().deployments().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).replace()
    }

    fun resumePreview(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun) {
        val current = deployment(app.namespace, candidateName(run))
        requireOwned(current.metadata.labels, run)
        val count = config.replicas ?: requireNotNull(run.snapshot?.deployment).spec.replicas ?: 1
        if (current.spec.replicas == count) return
        current.spec.replicas = count
        client.apps().deployments().inNamespace(app.namespace).resource(current).lockResourceVersion(current.metadata.resourceVersion).replace()
    }

    private fun container(spec: PodSpec, name: String?) = spec.containers.singleOrNull { name == null || it.name == name }
        ?: throw DeploymentValidationException("containerName must identify one container")

    private fun deployment(namespace: String, name: String): Deployment = client.apps().deployments().inNamespace(namespace).withName(name).get()
        ?: throw DeploymentValidationException("Deployment '$name' does not exist")

    private fun service(namespace: String, name: String): Service = client.services().inNamespace(namespace).withName(name).get()
        ?: throw DeploymentValidationException("Service '$name' does not exist")

    private fun ready(value: Deployment): Boolean {
        if (value.status?.conditions.orEmpty().any { it.type == "Progressing" && it.status == "False" }) throw DeploymentValidationException("Deployment exceeded its progress deadline")
        val replicas = value.spec.replicas ?: 1
        return replicas > 0 && (value.status?.observedGeneration ?: -1) >= (value.metadata.generation ?: 0) &&
            value.status?.updatedReplicas == replicas && value.status?.availableReplicas == replicas && value.status?.replicas == replicas
    }

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

    private fun requireOwned(labels: Map<String, String>?, run: DeploymentRun) {
        if (labels?.get(RUN_LABEL) != run.id) throw DeploymentConflictException("resource name is already owned by another workload")
    }

    private fun requireUid(actual: String?, expected: String?) {
        if (actual != expected) throw DeploymentConflictException("resource was replaced outside this run")
    }

    private fun <T : HasMetadata> claim(resource: T, applicationId: String): T {
        val owner = resource.metadata.annotations?.get(APP_ANNOTATION)
        if (owner != null && owner != applicationId) throw DeploymentConflictException("resource is managed by another deployment application")
        if (owner == applicationId) return resource
        resource.metadata.annotations = resource.metadata.annotations.orEmpty() + (APP_ANNOTATION to applicationId)
        return client.resource(resource).lockResourceVersion(resource.metadata.resourceVersion).update()
    }

    companion object {
        const val RUN_LABEL = "deploydock.io/run"
        const val APP_ANNOTATION = "deploydock.io/application"
        fun candidateName(run: DeploymentRun) = candidateName(run.id)
        fun candidateName(runId: String) = "dd-${runId.removePrefix("run-")}"
        fun previewName(run: DeploymentRun) = "${candidateName(run)}-preview"
    }
}
