package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockTemporalProperties
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceSummary
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.client.Config
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "DEPLOYDOCK_E2E_KUBECONFIG", matches = ".+")
class DeploymentClusterTests {
    @Test
    fun `existing Deployment supports preview promotion and redeployment of a saved revision`() {
        val config = Config.fromKubeconfig(Files.readString(Path.of(System.getenv("DEPLOYDOCK_E2E_KUBECONFIG"))))
        check(config.currentContext?.name == "kind-deploydock-dev") { "E2E is restricted to the repository Kind cluster" }
        val namespace = "dd-rollout-e2e-${UUID.randomUUID().toString().take(8)}"
        val principal = "deploydock:rollout-e2e"
        KubernetesClientBuilder().withConfig(config).build().use { client ->
            client.namespaces().resource(NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()).create()
            try {
                client.rbac().roles().inNamespace(namespace).resource(RoleBuilder().withNewMetadata().withName("test").endMetadata()
                    .addNewRule().withApiGroups("*").withResources("*").withVerbs("get", "list", "create", "update").endRule().build()).create()
                client.rbac().roleBindings().inNamespace(namespace).resource(RoleBindingBuilder().withNewMetadata().withName("test").endMetadata()
                    .addNewSubject().withKind("User").withApiGroup("rbac.authorization.k8s.io").withName(principal).endSubject()
                    .withNewRoleRef().withApiGroup("rbac.authorization.k8s.io").withKind("Role").withName("test").endRoleRef().build()).create()
                client.apps().deployments().inNamespace(namespace).resource(DeploymentBuilder().withNewMetadata().withName("web").endMetadata()
                    .withNewSpec().withReplicas(1).withNewSelector().addToMatchLabels("app", "web").endSelector()
                    .withNewTemplate().withNewMetadata().addToLabels("app", "web").endMetadata().withNewSpec()
                    .addNewContainer().withName("web").withImage("nginx:1.27-alpine")
                    .withNewReadinessProbe().withNewHttpGet().withPath("/").withNewPort(80).endHttpGet().endReadinessProbe()
                    .endContainer().endSpec().endTemplate().endSpec().build()).create()
                client.apps().deployments().inNamespace(namespace).withName("web").waitUntilReady(180, java.util.concurrent.TimeUnit.SECONDS)
                client.services().inNamespace(namespace).resource(ServiceBuilder().withNewMetadata().withName("web").endMetadata()
                    .withNewSpec().addToSelector("app", "web").addNewPort().withPort(80).withNewTargetPort(80).endPort().endSpec().build()).create()
                val originalUid = client.apps().deployments().inNamespace(namespace).withName("web").get().metadata.uid
                val store = KubernetesDeploymentStore(client, namespace)
                val workloads = KubernetesDeploymentWorkloads(client, authorization = SubjectAccessReviewAuthorization(client))
                val reconciler = DeploymentReconciler(store, workloads, Clock.systemUTC())
                val namespaces = object : NamespaceAccessProvider {
                    override fun findAccessible(principal: String) = Mono.just(listOf(NamespaceSummary(namespace, "Active")))
                    override fun create(principal: String, name: String) = Mono.error<NamespaceSummary>(UnsupportedOperationException())
                }
                val orchestrators = DeploymentRunOrchestrators(store, reconciler, null, DeployDockTemporalProperties())
                val service = KubernetesDeploymentService(namespaces, store, orchestrators, workloads, Clock.systemUTC())
                val app = service.registerApplication(principal, RegisterApplicationRequest("web", namespace, ApplicationKind.WEB)).block()!!
                val baseline = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("nginx:1.27-alpine", webStrategy = WebDeploymentStrategy.ROLLING)).block()!!
                val saved = service.saveConfiguration(principal, app.id, SaveDeploymentConfigurationRequest("nginx:1.28-alpine", webStrategy = WebDeploymentStrategy.BLUE_GREEN)).block()!!
                var run = service.submitRun(principal, app.id, SubmitDeploymentRunRequest(saved.id, "e2e")).block()!!
                fun waitFor(status: DeploymentRunStatus): DeploymentRun {
                    val deadline = Instant.now().plusSeconds(180)
                    while (Instant.now().isBefore(deadline)) {
                        reconciler.reconcile(app.id, run.id)
                        val current = store.get(app.id).runs.first { it.id == run.id }
                        if (current.status == status) return current
                        check(!current.terminal()) { "unexpected status: ${current.status}: ${current.error}" }
                        Thread.sleep(1000)
                    }
                    error("Timed out waiting for $status: ${store.get(app.id).runs.first { it.id == run.id }}")
                }
                fun assertVersion(serviceName: String, version: String) {
                    client.services().inNamespace(namespace).withName(serviceName).portForward(80).use { forward ->
                        HttpClient.newHttpClient().use { http ->
                            val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${forward.localPort}/"))
                                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
                            assertEquals(200, response.statusCode())
                            assertTrue(response.headers().firstValue("Server").orElse("").contains(version))
                        }
                    }
                }
                val preview = waitFor(DeploymentRunStatus.AWAITING_APPROVAL)
                assertVersion("web", "1.27")
                assertVersion(requireNotNull(preview.result?.previewService), "1.28")
                service.action(principal, app.id, run.id, DeploymentAction.PROMOTE).block()
                waitFor(DeploymentRunStatus.SUCCEEDED)
                assertVersion("web", "1.28")
                assertEquals(originalUid, client.apps().deployments().inNamespace(namespace).withName("web").get().metadata.uid)
                run = service.submitRun(principal, app.id, SubmitDeploymentRunRequest(baseline.id, "e2e-revision")).block()!!
                waitFor(DeploymentRunStatus.SUCCEEDED)
                assertVersion("web", "1.27")
                assertEquals(2, service.runs(principal, app.id).block()!!.size)
                assertEquals(saved.id, service.configurations(principal, app.id).block()!!.single().id)
            } finally {
                client.namespaces().withName(namespace).delete()
            }
        }
    }
}
