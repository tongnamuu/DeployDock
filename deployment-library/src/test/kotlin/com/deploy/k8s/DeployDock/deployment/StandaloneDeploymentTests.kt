package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnableKubernetesMockClient(crud = true, https = false)
class StandaloneDeploymentTests {
    lateinit var kubernetes: KubernetesClient

    @Test
    fun `saving keeps one current configuration and preserves unexecuted web revisions`() {
        val store = MemoryStore()
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val latest = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v3", webStrategy = WebDeploymentStrategy.BLUE_GREEN))
        assertEquals(2, latest.revision)
        assertEquals(listOf(latest), api.configurations(app.id))
        assertEquals(listOf(latest), store.get(app.id).configurations)
        assertEquals(listOf(latest, old), api.revisions(app.id))
        assertTrue(api.runs(app.id).isEmpty())
        assertEquals(old, api.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "historical-release")).configuration)
        assertEquals(listOf(latest), api.configurations(app.id))
    }

    @Test
    fun `new configuration cannot change active execution and web rollback is rejected`() {
        seed()
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val workloads = KubernetesDeploymentWorkloads(kubernetes)
        val api = DeploymentClient(store, workloads)
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val original = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val request = SubmitDeploymentRunRequest(original.id, "release")
        val run = api.submitRun("client", app.id, request)
        val latest = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v3", webStrategy = WebDeploymentStrategy.BLUE_GREEN))
        assertEquals(run.id, api.submitRun("client", app.id, request).id)
        assertEquals(listOf(latest), store.get(app.id).configurations)
        assertEquals(original, api.runs(app.id).single().configuration)
        val reconciler = DeploymentReconciler(KubernetesDeploymentStore(kubernetes, "team-a"), workloads, Clock.systemUTC())
        repeat(2) { reconciler.reconcile(app.id, run.id) }
        ready("web")
        assertTrue(reconciler.reconcile(app.id, run.id))
        assertEquals("example/web:v2", kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertFailsWith<DeploymentValidationException> { api.action("client", app.id, run.id, DeploymentAction.ROLLBACK, "rollback") }
        assertEquals(DeploymentRunStatus.SUCCEEDED, api.runs(app.id).single().status)
        assertEquals("example/web:v2", kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertEquals(listOf(latest), api.configurations(app.id))
    }

    @Test
    fun `legacy configuration history is compacted without losing run settings`() {
        val store = MemoryStore()
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "release"))
        val previousLatest = old.copy(id = "legacy-latest", revision = 2, image = "example/web:v3")
        store.update(app.id) { it.copy(configurations = listOf(old, previousLatest), runs = listOf(run.copy(configuration = null))) }
        assertEquals(listOf(previousLatest), api.configurations(app.id))
        assertEquals(old, api.runs(app.id).single().configuration)
        val latest = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v4", webStrategy = WebDeploymentStrategy.ROLLING))
        assertEquals(3, latest.revision)
        assertEquals(listOf(latest), store.get(app.id).configurations)
        assertEquals(old, store.get(app.id).runs.single().configuration)
        val mapper = deploymentMapper()
        assertEquals(store.get(app.id), mapper.readValue(mapper.writeValueAsBytes(store.get(app.id)), DeploymentRecord::class.java))
    }

    @Test
    fun `plain library runs rolling deployment without Spring Temporal or gateway`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.springframework.boot.SpringApplication") }
        assertFailsWith<ClassNotFoundException> { Class.forName("io.temporal.client.WorkflowClient") }
        assertFailsWith<ClassNotFoundException> { Class.forName("com.deploy.k8s.DeployDock.deployment.GatewayApiTrafficAdapter") }
        seed()
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val workloads = KubernetesDeploymentWorkloads(kubernetes)
        val api = DeploymentClient(store, workloads)
        val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val saved = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(saved.id, "release"))
        assertEquals(DeploymentRunStatus.QUEUED, run.status)
        repeat(2) { reconciler.reconcile(app.id, run.id) }
        assertEquals("example/web:v2", kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        ready("web")
        assertTrue(reconciler.reconcile(app.id, run.id))
        assertEquals(DeploymentRunStatus.SUCCEEDED, api.runs(app.id).single().status)
        assertEquals(emptySet(), api.capabilities().configuredTrafficAdapters)
        assertEquals(WebDeploymentStrategy.entries.toSet(), api.capabilities().webStrategies)
        assertFalse(api.capabilities().weightedCanary)
    }

    @Test
    fun `blue green preview needs only native Deployment and Service APIs`() {
        seed()
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val workloads = KubernetesDeploymentWorkloads(kubernetes)
        val api = DeploymentClient(store, workloads)
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val saved = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.BLUE_GREEN))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(saved.id, "release"))
        val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
        repeat(2) { reconciler.reconcile(app.id, run.id) }
        val preview = assertNotNull(api.runs(app.id).single().result?.previewService)
        assertNotNull(kubernetes.services().inNamespace("team-a").withName(preview).get())
        assertEquals(mapOf("app" to "web"), kubernetes.services().inNamespace("team-a").withName("web").get().spec.selector)
        assertEquals(DeploymentRunStatus.RUNNING, api.runs(app.id).single().status)
    }

    @Test
    fun `explicit weighted canary is rejected when selected adapter is unavailable`() {
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        assertFailsWith<DeploymentValidationException> {
            api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.CANARY, canaryRoute = "web"))
        }
        assertTrue(api.configurations(app.id).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["none", "nginx", "cilium"])
    fun `preview canary promotes and rejects web rollback without changing ingress`(ingressClass: String) {
        val ingress = if (ingressClass == "none") null else kubernetes.network().v1().ingresses().inNamespace("team-a")
            .resource(IngressBuilder().withNewMetadata().withName("web").endMetadata().withNewSpec()
                .withIngressClassName(ingressClass).withNewDefaultBackend().withNewService().withName("web")
                .withNewPort().withNumber(80).endPort().endService().endDefaultBackend().endSpec().build()).create()
        val f = nativeCanary()
        val deploymentUid = kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().metadata.uid
        val serviceUid = kubernetes.services().inNamespace("team-a").withName("web").get().metadata.uid
        f.tick(2)
        val name = KubernetesDeploymentWorkloads.candidateName(f.run)
        val preview = assertNotNull(f.current().result?.previewService)
        assertEquals(CanaryTrafficMode.PREVIEW_ONLY, f.current().result?.trafficMode)
        assertNull(f.current().snapshot?.traffic)
        assertNull(f.current().snapshot?.route)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertFailsWith<DeploymentConflictException> { f.action(DeploymentAction.PROMOTE) }
        ready(name)
        endpoints(preview, name)
        f.tick()
        assertEquals(DeploymentRunStatus.AWAITING_APPROVAL, f.current().status)
        assertEquals(0, f.current().result?.canaryWeight)
        assertFailsWith<DeploymentConflictException> { f.action(DeploymentAction.ADVANCE) }
        f.action(DeploymentAction.PROMOTE)
        f.tick(3)
        assertEquals(DeploymentRunStatus.PROMOTING, f.current().status)
        endpoints("web", name)
        f.tick(2)
        ready("web")
        f.tick()
        endpoints("web", "web")
        f.tick()
        assertEquals(DeploymentRunStatus.SUCCEEDED, f.current().status)
        assertEquals(deploymentUid, kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().metadata.uid)
        assertEquals(serviceUid, kubernetes.services().inNamespace("team-a").withName("web").get().metadata.uid)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertFailsWith<DeploymentValidationException> { f.action(DeploymentAction.ROLLBACK) }
        assertEquals(DeploymentRunStatus.SUCCEEDED, f.current().status)
        assertEquals("example/web:v2", kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertEquals(0, kubernetes.apps().deployments().inNamespace("team-a").withName(name).get().spec.replicas)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertEquals(ingress, kubernetes.network().v1().ingresses().inNamespace("team-a").withName("web").get())
        assertTrue(f.permissions.none { it.resource in setOf("ingresses", "httproutes") })
    }

    @Test
    fun `preview canary abort restores without a traffic adapter`() {
        val f = nativeCanary()
        f.tick(2)
        f.action(DeploymentAction.ABORT)
        f.tick(3)
        assertEquals(DeploymentRunStatus.ABORTED, f.current().status)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertEquals(0, kubernetes.apps().deployments().inNamespace("team-a")
            .withName(KubernetesDeploymentWorkloads.candidateName(f.run)).get().spec.replicas)
    }

    @Test
    fun `preview canary readiness failure restores without a traffic adapter`() {
        val f = nativeCanary()
        f.tick(2)
        val name = KubernetesDeploymentWorkloads.candidateName(f.run)
        ready(name)
        endpoints(KubernetesDeploymentWorkloads.previewName(f.run), name)
        f.tick()
        val resource = kubernetes.apps().deployments().inNamespace("team-a").withName(name).get()
        resource.status.availableReplicas = 0
        kubernetes.apps().deployments().inNamespace("team-a").resource(resource).updateStatus()
        f.tick(3)
        assertEquals(DeploymentRunStatus.FAILED, f.current().status)
        assertNotNull(f.current().error)
        assertNull(f.current().recoveryError)
        assertEquals(mapOf("app" to "web"), activeSelector())
    }

    @Test
    fun `registered adapter is not selected implicitly for preview canary`() {
        val adapter = object : CanaryTrafficAdapter {
            override val id = "unused"
            override val permissions: List<ResourcePermission> get() = error("must not request traffic permissions")
            override fun validate(configuration: DeploymentConfiguration) = error("must not validate traffic")
            override fun capture(application: DeploymentApplication, configuration: DeploymentConfiguration): TrafficSnapshot = error("must not snapshot traffic")
            override fun setWeight(application: DeploymentApplication, configuration: DeploymentConfiguration, run: DeploymentRun, weight: Int?) = error("must not route traffic")
            override fun isReady(application: DeploymentApplication, configuration: DeploymentConfiguration): Boolean = error("must not read traffic")
        }
        val f = nativeCanary(listOf(adapter))
        assertTrue(f.api.capabilities().weightedCanary)
        f.tick(2)
        assertEquals(CanaryTrafficMode.PREVIEW_ONLY, f.current().result?.trafficMode)
        f.action(DeploymentAction.ABORT)
        f.tick(3)
        assertEquals(DeploymentRunStatus.ABORTED, f.current().status)
    }

    @Test
    fun `ambiguous or missing traffic adapter settings never downgrade silently`() {
        val f = nativeCanary()
        listOf(
            SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.CANARY, trafficOptions = mapOf("routeName" to "web")),
            SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.CANARY, trafficAdapter = "missing"),
            SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.BLUE_GREEN, trafficAdapter = "missing"),
        ).forEach { request ->
            assertFailsWith<DeploymentValidationException> { f.api.saveConfiguration("client", f.run.applicationId, request) }
        }
        assertEquals(1, f.api.configurations(f.run.applicationId).size)
    }

    @Test
    fun `custom traffic adapter and custom store work without HTTPRoute dependency`() {
        seed()
        val adapter = RecordingTrafficAdapter()
        val store = MemoryStore()
        val workloads = KubernetesDeploymentWorkloads(kubernetes, listOf(adapter))
        val api = DeploymentClient(store, workloads)
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val config = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.CANARY,
            trafficAdapter = adapter.id, trafficOptions = mapOf("route" to "external-route")))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(config.id, "release"))
        val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
        reconciler.reconcile(app.id, run.id)
        val prepared = api.runs(app.id).single()
        assertEquals(adapter.id, prepared.snapshot?.traffic?.adapterId)
        assertEquals("external-route", prepared.snapshot?.traffic?.attributes?.get("route"))
        workloads.setCanaryWeight(app, config, prepared, 10)
        workloads.setCanaryWeight(app, config, prepared, null)
        assertEquals(listOf(10, null), adapter.weights)
        assertTrue(workloads.canaryRouteReady(app, config))
        assertEquals(setOf(adapter.id), api.capabilities().configuredTrafficAdapters)
        assertTrue(WebDeploymentStrategy.CANARY in api.capabilities().webStrategies)
        assertTrue(api.capabilities().weightedCanary)
        reconciler.reconcile(app.id, run.id)
        assertEquals(CanaryTrafficMode.WEIGHTED, api.runs(app.id).single().result?.trafficMode)
        val mapper = deploymentMapper()
        assertEquals(store.get(app.id), mapper.readValue(mapper.writeValueAsBytes(store.get(app.id)), DeploymentRecord::class.java))
    }

    @Test
    fun `historical revision creates a new deployment with a fresh snapshot and unchanged latest settings`() {
        seed()
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val workloads = KubernetesDeploymentWorkloads(kubernetes)
        val api = DeploymentClient(store, workloads)
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v1", webStrategy = WebDeploymentStrategy.ROLLING))
        val latest = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val first = api.submitRun("client", app.id, SubmitDeploymentRunRequest(latest.id, "first"))
        val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
        repeat(2) { reconciler.reconcile(app.id, first.id) }
        ready("web")
        assertTrue(reconciler.reconcile(app.id, first.id))
        val finished = api.runs(app.id).single()
        val restarted = DeploymentClient(KubernetesDeploymentStore(kubernetes, "team-a"), workloads)
        val request = SubmitDeploymentRunRequest(old.id, "redeploy")
        val second = restarted.submitRun("another-user", app.id, request)
        assertFalse(second.id == first.id)
        assertNull(second.snapshot)
        assertFalse(second.rollbackRequested)
        assertEquals(old, second.configuration)
        assertEquals("another-user", second.requestedBy)
        assertEquals(second.id, restarted.submitRun("another-user", app.id, request).id)
        assertFailsWith<DeploymentConflictException> { restarted.submitRun("client", app.id, request.copy(requestId = "concurrent")) }
        assertFailsWith<DeploymentConflictException> { restarted.submitRun("client", app.id, request.copy(configurationId = latest.id)) }
        reconciler.reconcile(app.id, second.id)
        assertEquals("example/web:v2", api.runs(app.id).last().snapshot?.deployment?.spec?.template?.spec?.containers?.single()?.image)
        reconciler.reconcile(app.id, second.id)
        ready("web")
        assertTrue(reconciler.reconcile(app.id, second.id))
        assertEquals("example/web:v1", kubernetes.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertEquals(finished, api.runs(app.id).first())
        assertEquals(DeploymentRunStatus.SUCCEEDED, api.runs(app.id).last().status)
        assertEquals(listOf(latest), api.configurations(app.id))
        assertEquals(listOf(latest, old), restarted.revisions(app.id))
        assertFailsWith<DeploymentConfigurationNotFoundException> { api.submitRun("client", app.id, SubmitDeploymentRunRequest("unknown", "unknown")) }
        val denied = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes, authorization = DeploymentAuthorization { _, _, _ -> throw DeploymentForbiddenException() }))
        assertFailsWith<DeploymentForbiddenException> { denied.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "denied")) }
        assertEquals(2, api.runs(app.id).size)
    }

    @Test
    fun `legacy revisions are recovered from run settings and kept on the next save`() {
        val store = MemoryStore()
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v1", webStrategy = WebDeploymentStrategy.ROLLING))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "first"))
        val latest = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        store.update(app.id) { it.copy(revisions = emptyList(), runs = listOf(run.copy(status = DeploymentRunStatus.SUCCEEDED))) }
        assertEquals(listOf(latest, old), api.revisions(app.id))
        val saved = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v3", webStrategy = WebDeploymentStrategy.ROLLING))
        assertEquals(listOf(saved, latest, old), api.revisions(app.id))
        assertEquals(old, api.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "legacy-redeploy")).configuration)
        val other = api.registerApplication("client", RegisterApplicationRequest("other", "team-a", ApplicationKind.WEB))
        assertFailsWith<DeploymentConfigurationNotFoundException> { api.submitRun("client", other.id, SubmitDeploymentRunRequest(old.id, "foreign")) }
    }

    @Test
    fun `batch configuration remains latest only and cannot use web revision API`() {
        val api = DeploymentClient(MemoryStore(), KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("batch", "team-a", ApplicationKind.BATCH))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/batch:v1", batchMode = BatchDeploymentMode.INDIVIDUAL))
        api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/batch:v2", batchMode = BatchDeploymentMode.INDIVIDUAL))
        assertFailsWith<DeploymentConflictException> { api.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "old")) }
        assertFailsWith<DeploymentValidationException> { api.revisions(app.id) }
    }

    @Test
    fun `historical revision must still have its traffic adapter available`() {
        val store = MemoryStore()
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes, listOf(RecordingTrafficAdapter())))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val old = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v1", webStrategy = WebDeploymentStrategy.CANARY,
            trafficAdapter = RecordingTrafficAdapter().id, trafficOptions = mapOf("route" to "web")))
        api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.ROLLING))
        val noAdapter = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        assertFailsWith<DeploymentValidationException> { noAdapter.submitRun("client", app.id, SubmitDeploymentRunRequest(old.id, "old")) }
        assertTrue(api.runs(app.id).isEmpty())
    }

    private fun nativeCanary(adapters: List<CanaryTrafficAdapter> = emptyList()): NativeCanary {
        seed()
        endpoints("web", "web")
        val permissions = mutableListOf<ResourcePermission>()
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val workloads = KubernetesDeploymentWorkloads(kubernetes, adapters, DeploymentAuthorization { _, _, requested -> permissions.addAll(requested) })
        val api = DeploymentClient(store, workloads)
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        val config = api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest(
            "example/web:v2", webStrategy = WebDeploymentStrategy.CANARY))
        val run = api.submitRun("client", app.id, SubmitDeploymentRunRequest(config.id, "release"))
        return NativeCanary(api, DeploymentReconciler(store, workloads, Clock.systemUTC()), run, permissions)
    }

    private data class NativeCanary(
        val api: DeploymentClient,
        val reconciler: DeploymentReconciler,
        val run: DeploymentRun,
        val permissions: List<ResourcePermission>,
    ) {
        fun tick(count: Int = 1) { repeat(count) { reconciler.reconcile(run.applicationId, run.id) } }
        fun current() = api.runs(run.applicationId).single()
        fun action(action: DeploymentAction) = api.action("client", run.applicationId, run.id, action, action.name)
    }

    private fun activeSelector() = kubernetes.services().inNamespace("team-a").withName("web").get().spec.selector

    private fun endpoints(serviceName: String, deployment: String) {
        val slice = EndpointSliceBuilder().withNewMetadata().withName("$serviceName-endpoints")
            .addToLabels("kubernetes.io/service-name", serviceName).endMetadata().withAddressType("IPv4")
            .addNewEndpoint().withAddresses("10.1.1.1").withNewConditions().withReady(true).endConditions()
            .withNewTargetRef().withKind("Pod").withName("$deployment-rs-pod").endTargetRef().endEndpoint().build()
        val operation = kubernetes.discovery().v1().endpointSlices().inNamespace("team-a")
        val existing = operation.withName(slice.metadata.name).get()
        if (existing == null) operation.resource(slice).create()
        else {
            slice.metadata.resourceVersion = existing.metadata.resourceVersion
            operation.resource(slice).update()
        }
    }

    private fun seed() {
        kubernetes.apps().deployments().inNamespace("team-a").resource(DeploymentBuilder().withNewMetadata().withName("web").endMetadata()
            .withNewSpec().withReplicas(1).withNewSelector().addToMatchLabels("app", "web").endSelector()
            .withNewTemplate().withNewMetadata().addToLabels("app", "web").endMetadata().withNewSpec()
            .addNewContainer().withName("web").withImage("example/web:v1").endContainer().endSpec().endTemplate().endSpec().build()).create()
        ready("web")
        kubernetes.services().inNamespace("team-a").resource(ServiceBuilder().withNewMetadata().withName("web").endMetadata()
            .withNewSpec().addToSelector("app", "web").addNewPort().withPort(80).endPort().endSpec().build()).create()
    }

    private fun ready(name: String) {
        val value = kubernetes.apps().deployments().inNamespace("team-a").withName(name).get()
        value.status = DeploymentStatusBuilder().withObservedGeneration(value.metadata.generation)
            .withReplicas(1).withAvailableReplicas(1).withUpdatedReplicas(1).withReadyReplicas(1).build()
        kubernetes.apps().deployments().inNamespace("team-a").resource(value).updateStatus()
    }

    private class RecordingTrafficAdapter : CanaryTrafficAdapter {
        override val id = "custom-router"
        override val permissions = emptyList<ResourcePermission>()
        val weights = mutableListOf<Int?>()
        override fun validate(configuration: DeploymentConfiguration) {
            require(configuration.trafficOptions["route"] != null)
        }
        override fun capture(application: DeploymentApplication, configuration: DeploymentConfiguration) =
            TrafficSnapshot(id, attributes = configuration.trafficOptions)
        override fun setWeight(application: DeploymentApplication, configuration: DeploymentConfiguration, run: DeploymentRun, weight: Int?) {
            weights.add(weight)
        }
        override fun isReady(application: DeploymentApplication, configuration: DeploymentConfiguration) = true
    }

    private class MemoryStore : DeploymentStore {
        private val records = mutableMapOf<String, DeploymentRecord>()
        override fun create(record: DeploymentRecord): DeploymentRecord = record.also { records[it.application.id] = it }
        override fun get(id: String) = records.getValue(id)
        override fun list() = records.values.toList()
        override fun update(id: String, operation: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord =
            operation(get(id)).also { records[id] = it }
    }
}
