package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnableKubernetesMockClient(crud = true, https = false)
class StandaloneDeploymentTests {
    lateinit var kubernetes: KubernetesClient

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
        assertEquals(setOf(WebDeploymentStrategy.ROLLING, WebDeploymentStrategy.BLUE_GREEN), api.capabilities().webStrategies)
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
    fun `unsupported canary is rejected without silently installing an adapter`() {
        val store = KubernetesDeploymentStore(kubernetes, "team-a")
        val api = DeploymentClient(store, KubernetesDeploymentWorkloads(kubernetes))
        val app = api.registerApplication("client", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        assertFailsWith<DeploymentValidationException> {
            api.saveConfiguration("client", app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.CANARY, canaryRoute = "web"))
        }
        assertTrue(api.configurations(app.id).isEmpty())
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
        val mapper = deploymentMapper()
        assertEquals(store.get(app.id), mapper.readValue(mapper.writeValueAsBytes(store.get(app.id)), DeploymentRecord::class.java))
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
