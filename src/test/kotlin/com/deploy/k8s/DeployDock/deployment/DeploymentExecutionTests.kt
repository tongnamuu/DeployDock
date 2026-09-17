package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import com.deploy.k8s.DeployDock.config.DeployDockTemporalProperties
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceSummary
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSliceBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@EnableKubernetesMockClient(crud = true, https = false)
class DeploymentExecutionTests {
    lateinit var client: KubernetesClient
    lateinit var server: KubernetesMockServer
    private lateinit var store: DeploymentStore
    private lateinit var workloads: KubernetesDeploymentWorkloads
    private lateinit var reconciler: DeploymentReconciler
    private lateinit var service: KubernetesDeploymentService
    private val clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC)
    private val routeContext = ResourceDefinitionContext.Builder().withGroup("gateway.networking.k8s.io")
        .withVersion("v1").withPlural("httproutes").withKind("HTTPRoute").withNamespaced(true).build()
    private val principal = "deploydock:alice"

    @BeforeEach
    fun setup() {
        server.expect().post().withPath("/apis/authorization.k8s.io/v1/subjectaccessreviews")
            .andReturn(201, SubjectAccessReviewBuilder().withNewStatus().withAllowed(true).endStatus().build()).always()
        store = KubernetesDeploymentStore(client, "deploydock-system")
        workloads = KubernetesDeploymentWorkloads(client, listOf(GatewayApiTrafficAdapter(client)), SubjectAccessReviewAuthorization(client))
        reconciler = DeploymentReconciler(store, workloads, clock)
        val orchestrators = DeploymentRunOrchestrators(store, reconciler, null, DeployDockTemporalProperties())
        val namespaces = object : NamespaceAccessProvider {
            override fun findAccessible(principal: String) = Mono.just(listOf(NamespaceSummary("team-a", "Active")))
            override fun create(principal: String, name: String) = Mono.just(NamespaceSummary(name, "Active"))
        }
        service = KubernetesDeploymentService(namespaces, store, orchestrators, workloads, clock)
        val deployment = DeploymentBuilder().withNewMetadata().withName("web").withNamespace("team-a").endMetadata()
            .withNewSpec().withReplicas(2).withNewSelector().addToMatchLabels("app", "web").endSelector()
            .withNewTemplate().withNewMetadata().addToLabels("app", "web").endMetadata().withNewSpec()
            .addNewContainer().withName("web").withImage("example/web:v1").addNewPort().withContainerPort(8080).endPort().endContainer()
            .endSpec().endTemplate().endSpec().build()
        client.apps().deployments().inNamespace("team-a").resource(deployment).create()
        ready("web")
        client.services().inNamespace("team-a").resource(ServiceBuilder().withNewMetadata().withName("web").endMetadata()
            .withNewSpec().addToSelector("app", "web").addNewPort().withPort(80).withNewTargetPort(8080).endPort().endSpec().build()).create()
        endpoints("web", "web")
    }

    @Test
    fun `blue green isolates preview and only switches after approval and endpoint convergence`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        assertEquals(DeploymentRunStatus.QUEUED, run.status)
        tick(app, run, 2)
        val preview = assertNotNull(current(app, run).result?.previewService)
        val name = KubernetesDeploymentWorkloads.candidateName(run)
        val candidate = client.apps().deployments().inNamespace("team-a").withName(name).get()
        assertEquals("example/web:v2", candidate.spec.template.spec.containers.single().image)
        assertEquals("example/web:v1", client.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertFalse(candidate.spec.template.metadata.labels["app"] == "web")
        assertEquals(mapOf("app" to "web"), activeSelector())
        tick(app, run)
        assertEquals(DeploymentRunStatus.RUNNING, current(app, run).status)
        assertFailsWith<DeploymentConflictException> { service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block() }
        ready(name)
        endpoints(preview, name)
        tick(app, run)
        assertEquals(DeploymentRunStatus.AWAITING_APPROVAL, current(app, run).status)
        service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block()
        tick(app, run, 3)
        assertEquals(DeploymentRunStatus.PROMOTING, current(app, run).status)
        endpoints("web", name)
        tick(app, run, 2)
        ready("web")
        tick(app, run)
        endpoints("web", "web")
        tick(app, run)
        assertEquals(DeploymentRunStatus.SUCCEEDED, current(app, run).status)
        assertEquals("web", store.get(app.id).application.activeDeployment)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertEquals("example/web:v2", client.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
        assertFailsWith<DeploymentValidationException> { service.action(principal, app.id, run.id, DeploymentAction.ROLLBACK).block() }
        assertEquals(DeploymentRunStatus.SUCCEEDED, current(app, run).status)
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertEquals(0, client.apps().deployments().inNamespace("team-a").withName(name).get().spec.replicas)
        assertEquals("example/web:v2", client.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
    }

    @Test
    fun `canary waits for each approval and accepted route generation then restores on abort`() {
        createRoute()
        val (app, run) = submit(WebDeploymentStrategy.CANARY)
        tick(app, run, 2)
        val name = KubernetesDeploymentWorkloads.candidateName(run)
        ready(name)
        endpoints(KubernetesDeploymentWorkloads.previewName(run), name)
        tick(app, run)
        service.action(principal, app.id, run.id, DeploymentAction.ADVANCE).block()
        tick(app, run, 2)
        assertEquals(listOf(90, 10), routeWeights())
        assertEquals(DeploymentRunStatus.RUNNING, current(app, run).status)
        acceptRoute()
        tick(app, run)
        assertEquals(10, current(app, run).result?.canaryWeight)
        val originalActionId = current(app, run).actionRequests.keys.single()
        service.action(principal, app.id, run.id, DeploymentAction.ADVANCE, originalActionId).block()
        tick(app, run)
        assertEquals(0, current(app, run).step)
        assertFailsWith<DeploymentConflictException> { service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block() }
        service.action(principal, app.id, run.id, DeploymentAction.ADVANCE).block()
        tick(app, run, 2)
        acceptRoute()
        tick(app, run)
        assertEquals(listOf(50, 50), routeWeights())
        service.action(principal, app.id, run.id, DeploymentAction.ABORT).block()
        tick(app, run, 2)
        acceptRoute()
        tick(app, run)
        assertEquals(DeploymentRunStatus.ABORTED, current(app, run).status)
        assertEquals(listOf(100), routeWeights())
        assertEquals(mapOf("app" to "web"), activeSelector())
    }

    @Test
    fun `canary promotion restores route backend and original Deployment identity`() {
        createRoute()
        val (app, run) = submit(WebDeploymentStrategy.CANARY)
        tick(app, run, 2)
        val name = KubernetesDeploymentWorkloads.candidateName(run)
        ready(name)
        endpoints(KubernetesDeploymentWorkloads.previewName(run), name)
        tick(app, run)
        repeat(2) {
            service.action(principal, app.id, run.id, DeploymentAction.ADVANCE).block()
            tick(app, run, 2)
            acceptRoute()
            tick(app, run)
        }
        service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block()
        tick(app, run, 2)
        endpoints("web", name)
        tick(app, run)
        acceptRoute()
        tick(app, run, 2)
        ready("web")
        tick(app, run)
        endpoints("web", "web")
        tick(app, run)
        assertEquals(DeploymentRunStatus.SUCCEEDED, current(app, run).status)
        assertEquals(listOf(100), routeWeights())
        assertEquals(mapOf("app" to "web"), activeSelector())
        assertEquals("web", store.get(app.id).application.activeDeployment)
    }

    @Test
    fun `abort while original is updating restores its image before routing back`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        val name = KubernetesDeploymentWorkloads.candidateName(run)
        ready(name)
        endpoints(KubernetesDeploymentWorkloads.previewName(run), name)
        tick(app, run)
        service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block()
        tick(app, run, 2)
        endpoints("web", name)
        tick(app, run, 2)
        service.action(principal, app.id, run.id, DeploymentAction.ABORT).block()
        tick(app, run, 2)
        assertEquals(mapOf(KubernetesDeploymentWorkloads.RUN_LABEL to run.id), activeSelector())
        ready("web")
        tick(app, run)
        endpoints("web", "web")
        tick(app, run)
        assertEquals(DeploymentRunStatus.ABORTED, current(app, run).status)
        assertEquals("example/web:v1", client.apps().deployments().inNamespace("team-a").withName("web").get().spec.template.spec.containers.single().image)
    }

    @Test
    fun `external Service selector change is not overwritten during promotion or recovery`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        val name = KubernetesDeploymentWorkloads.candidateName(run)
        ready(name)
        endpoints(KubernetesDeploymentWorkloads.previewName(run), name)
        tick(app, run)
        client.services().inNamespace("team-a").withName("web").edit { ServiceBuilder(it).editSpec().addToSelector("external", "true").endSpec().build() }
        service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block()
        tick(app, run, 3)
        assertEquals("true", activeSelector()["external"])
        assertEquals(DeploymentRunStatus.ABORTING, current(app, run).status)
        assertNotNull(current(app, run).recoveryError)
    }

    @Test
    fun `another Service matching preview labels prevents Pod creation`() {
        client.apps().deployments().inNamespace("team-a").withName("web").edit {
            DeploymentBuilder(it).editSpec().editTemplate().editMetadata().addToLabels("shared", "web").endMetadata().endTemplate().endSpec().build()
        }
        ready("web")
        client.services().inNamespace("team-a").resource(ServiceBuilder().withNewMetadata().withName("other").endMetadata()
            .withNewSpec().addToSelector("shared", "web").addNewPort().withPort(80).endPort().endSpec().build()).create()
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        assertEquals(DeploymentRunStatus.ABORTING, current(app, run).status)
        assertEquals(1, client.apps().deployments().inNamespace("team-a").list().items.size)
    }

    @Test
    fun `another application cannot claim the same CronJob`() {
        cronJob("one")
        val first = DeploymentApplication("first", "one", "team-a", ApplicationKind.BATCH, DeploymentOrchestrator.LOCAL, principal, clock.instant())
        val second = first.copy(id = "second")
        val config = DeploymentConfiguration("cfg", first.id, 1, "example/batch:v2", null, null, BatchDeploymentMode.INDIVIDUAL, listOf("one"), principal, clock.instant())
        workloads.snapshot(first, config)
        assertFailsWith<DeploymentConflictException> { workloads.snapshot(second, config) }
    }

    @Test
    fun `live record lock excludes another writer and expired lock is recovered`() {
        val (app, _) = submit(WebDeploymentStrategy.BLUE_GREEN)
        val operation = client.configMaps().inNamespace("deploydock-system").withName(app.id)
        operation.edit { io.fabric8.kubernetes.api.model.ConfigMapBuilder(it).editMetadata()
            .addToAnnotations("deploydock.io/lease-until", Instant.now().plusSeconds(300).toString()).endMetadata().build() }
        assertFailsWith<DeploymentConflictException> { store.update(app.id) { it } }
        operation.edit { io.fabric8.kubernetes.api.model.ConfigMapBuilder(it).editMetadata()
            .addToAnnotations("deploydock.io/lease-until", Instant.now().minusSeconds(1).toString()).endMetadata().build() }
        assertEquals(app.id, store.update(app.id) { it }.application.id)
    }

    @Test
    fun `store recreation preserves snapshot and preview creation is idempotent`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        val restored = KubernetesDeploymentStore(client, "deploydock-system")
        val saved = restored.get(app.id).runs.single()
        assertNotNull(saved.snapshot?.service)
        restored.update(app.id) { it.copy(runs = listOf(saved.copy(phase = "APPLY"))) }
        DeploymentReconciler(restored, workloads, clock).reconcile(app.id, run.id)
        assertEquals(2, client.apps().deployments().inNamespace("team-a").list().items.size)
        assertEquals(2, client.services().inNamespace("team-a").list().items.size)
        assertEquals(DeploymentRunStatus.RUNNING, restored.get(app.id).runs.single().status)
    }

    @Test
    fun `duplicate request returns same run and concurrent release is rejected`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        val retry = service.submitRun(principal, app.id, SubmitDeploymentRunRequest(run.configurationId, "release-1")).block()
        assertEquals(run.id, retry?.id)
        assertFailsWith<DeploymentConflictException> {
            service.submitRun(principal, app.id, SubmitDeploymentRunRequest(run.configurationId, "release-2")).block()
        }
    }

    @Test
    fun `unready preview times out and restores old service without succeeding`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        val later = Clock.fixed(clock.instant().plusSeconds(601), ZoneOffset.UTC)
        val recovery = DeploymentReconciler(store, workloads, later)
        repeat(3) { recovery.reconcile(app.id, run.id) }
        assertEquals(DeploymentRunStatus.FAILED, current(app, run).status)
        assertTrue(current(app, run).error.orEmpty().contains("deadline"))
        assertEquals(mapOf("app" to "web"), activeSelector())
    }

    @Test
    fun `rolling waits for deployment controller observed generation`() {
        val (app, run) = submit(WebDeploymentStrategy.ROLLING)
        tick(app, run, 3)
        assertEquals(DeploymentRunStatus.RUNNING, current(app, run).status)
        ready("web")
        tick(app, run)
        assertEquals(DeploymentRunStatus.SUCCEEDED, current(app, run).status)
    }

    @Test
    fun `grouped CronJob update compensates already changed targets after partial failure`() {
        cronJob("one")
        cronJob("two")
        val app = service.registerApplication(principal, RegisterApplicationRequest("batch", "team-a", ApplicationKind.BATCH)).block()!!
        val config = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("example/batch:v2",
            batchMode = BatchDeploymentMode.GROUPED, batchTargets = listOf("one", "two"))).block()!!
        val run = service.submitRun(principal, app.id, SubmitDeploymentRunRequest(config.id, "batch-1")).block()!!
        tick(app, run)
        server.expect().put().withPath("/apis/batch/v1/namespaces/team-a/cronjobs/two").andReturn(422, "rejected").once()
        tick(app, run)
        assertEquals(DeploymentRunStatus.ABORTING, current(app, run).status)
        tick(app, run, 2)
        assertEquals(DeploymentRunStatus.FAILED, current(app, run).status)
        listOf("one", "two").forEach {
            assertEquals("example/batch:v1", client.batch().v1().cronjobs().inNamespace("team-a").withName(it).get().spec.jobTemplate.spec.template.spec.containers.single().image)
        }
    }

    @Test
    fun `individual CronJob changes only its selected target`() {
        cronJob("one")
        cronJob("two")
        val app = service.registerApplication(principal, RegisterApplicationRequest("batch", "team-a", ApplicationKind.BATCH)).block()!!
        val config = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("example/batch:v2",
            batchMode = BatchDeploymentMode.INDIVIDUAL, batchTargets = listOf("one"))).block()!!
        val run = service.submitRun(principal, app.id, SubmitDeploymentRunRequest(config.id, "batch-1")).block()!!
        tick(app, run, 2)
        assertEquals(DeploymentRunStatus.SUCCEEDED, current(app, run).status)
        assertEquals("example/batch:v2", client.batch().v1().cronjobs().inNamespace("team-a").withName("one").get().spec.jobTemplate.spec.template.spec.containers.single().image)
        assertEquals("example/batch:v1", client.batch().v1().cronjobs().inNamespace("team-a").withName("two").get().spec.jobTemplate.spec.template.spec.containers.single().image)
    }

    @Test
    fun `Temporal selection is rejected when disabled`() {
        assertFailsWith<DeploymentUnavailableException> {
            service.registerApplication(principal, RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB, DeploymentOrchestrator.TEMPORAL)).block()
        }
    }

    @Test
    fun `namespace visibility alone does not grant deployment permission`() {
        val app = service.registerApplication(principal, RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB)).block()!!
        val config = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("example/web:v2", webStrategy = WebDeploymentStrategy.BLUE_GREEN)).block()!!
        server.clearExpectations()
        server.expect().post().withPath("/apis/authorization.k8s.io/v1/subjectaccessreviews")
            .andReturn(201, SubjectAccessReviewBuilder().withNewStatus().withAllowed(false).endStatus().build()).always()
        assertFailsWith<DeploymentForbiddenException> { service.submitRun(principal, app.id, SubmitDeploymentRunRequest(config.id, "denied")).block() }
        assertTrue(store.get(app.id).runs.isEmpty())
    }

    @Test
    fun `Temporal converter round trips Kotlin result and persisted resource snapshot`() {
        val (app, run) = submit(WebDeploymentStrategy.BLUE_GREEN)
        tick(app, run, 2)
        val converter = deploymentDataConverter()
        val record = store.get(app.id)
        val payload = converter.toPayload(record).get()
        assertEquals(record, converter.fromPayload(payload, DeploymentRecord::class.java, DeploymentRecord::class.java))
    }

    @Test
    fun `Temporal workflow polls activities and completes after durable run completes`() {
        val options = TestEnvironmentOptions.newBuilder().setWorkflowClientOptions(WorkflowClientOptions.newBuilder()
            .setDataConverter(deploymentDataConverter()).build()).build()
        TestWorkflowEnvironment.newInstance(options).use { environment ->
            val worker = environment.newWorker("deploy-test")
            worker.registerWorkflowImplementationTypes(DeploymentWorkflowImpl::class.java)
            var calls = 0
            worker.registerActivitiesImplementations(object : DeploymentActivities {
                override fun reconcile(applicationId: String, runId: String): Boolean {
                    assertEquals("app", applicationId)
                    assertEquals("run", runId)
                    return ++calls >= 3
                }
            })
            environment.start()
            val workflow = environment.workflowClient.newWorkflowStub(DeploymentWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue("deploy-test").build())
            workflow.deploy("app", "run")
            assertEquals(3, calls)
        }
    }

    private fun submit(strategy: WebDeploymentStrategy): Pair<DeploymentApplication, DeploymentRun> {
        val app = service.registerApplication(principal, RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB)).block()!!
        val config = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("example/web:v2",
            webStrategy = strategy, canaryRoute = if (strategy == WebDeploymentStrategy.CANARY) "web" else null)).block()!!
        return app to service.submitRun(principal, app.id, SubmitDeploymentRunRequest(config.id, "release-1")).block()!!
    }

    private fun tick(app: DeploymentApplication, run: DeploymentRun, count: Int = 1) { repeat(count) { reconciler.reconcile(app.id, run.id) } }
    private fun current(app: DeploymentApplication, run: DeploymentRun) = store.get(app.id).runs.first { it.id == run.id }
    private fun activeSelector() = client.services().inNamespace("team-a").withName("web").get().spec.selector

    private fun ready(name: String) {
        val resource = client.apps().deployments().inNamespace("team-a").withName(name).get()
        resource.status = DeploymentStatusBuilder().withObservedGeneration(resource.metadata.generation)
            .withReplicas(2).withUpdatedReplicas(2).withAvailableReplicas(2).withReadyReplicas(2).build()
        client.apps().deployments().inNamespace("team-a").resource(resource).updateStatus()
    }

    private fun endpoints(serviceName: String, deployment: String) {
        val slice = EndpointSliceBuilder().withNewMetadata().withName("$serviceName-endpoints")
            .addToLabels("kubernetes.io/service-name", serviceName).endMetadata().withAddressType("IPv4")
            .addNewEndpoint().withAddresses("10.1.1.1").withNewConditions().withReady(true).endConditions()
            .withNewTargetRef().withKind("Pod").withName("$deployment-rs-pod").endTargetRef().endEndpoint().build()
        val operation = client.discovery().v1().endpointSlices().inNamespace("team-a")
        val existing = operation.withName(slice.metadata.name).get()
        if (existing == null) operation.resource(slice).create()
        else {
            slice.metadata.resourceVersion = existing.metadata.resourceVersion
            operation.resource(slice).update()
        }
    }

    private fun cronJob(name: String) {
        client.batch().v1().cronjobs().inNamespace("team-a").resource(CronJobBuilder().withNewMetadata().withName(name).endMetadata()
            .withNewSpec().withSchedule("0 * * * *").withNewJobTemplate().withNewSpec().withNewTemplate()
            .withNewSpec().withRestartPolicy("Never").addNewContainer().withName("batch").withImage("example/batch:v1").endContainer()
            .endSpec().endTemplate().endSpec().endJobTemplate().endSpec().build()).create()
    }

    private fun createRoute() {
        val json = """{"apiVersion":"gateway.networking.k8s.io/v1","kind":"HTTPRoute","metadata":{"name":"web","namespace":"team-a"},"spec":{"parentRefs":[{"name":"gateway"}],"rules":[{"backendRefs":[{"name":"web","port":80,"weight":100}]}]}}"""
        val route = deploymentMapper().readValue(json, io.fabric8.kubernetes.api.model.GenericKubernetesResource::class.java)
        client.genericKubernetesResources(routeContext).inNamespace("team-a").resource(route).create()
        acceptRoute()
    }

    private fun acceptRoute() {
        val operation = client.genericKubernetesResources(routeContext).inNamespace("team-a")
        val route = operation.withName("web").get()
        route.additionalProperties["status"] = mapOf("parents" to listOf(mapOf("parentRef" to mapOf("name" to "gateway"), "conditions" to listOf("Accepted", "ResolvedRefs").map {
            mapOf("type" to it, "status" to "True", "observedGeneration" to route.metadata.generation)
        })))
        operation.resource(route).updateStatus()
    }

    private fun routeWeights(): List<Int> {
        val route = client.genericKubernetesResources(routeContext).inNamespace("team-a").withName("web").get()
        return deploymentMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(route)
            .path("spec").path("rules")[0].path("backendRefs").map { it.path("weight").asInt(1) }
    }
}
