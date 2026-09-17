package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.util.UUID

interface DeploymentProvider {
    fun registerApplication(principal: String, request: RegisterApplicationRequest): Mono<DeploymentApplication>
    fun applications(principal: String): Mono<List<DeploymentApplication>>
    fun saveConfiguration(principal: String, applicationId: String, request: SaveDeploymentConfigurationRequest): Mono<DeploymentConfiguration>
    fun configurations(principal: String, applicationId: String): Mono<List<DeploymentConfiguration>>
    fun submitRun(principal: String, applicationId: String, request: SubmitDeploymentRunRequest): Mono<DeploymentRun>
    fun runs(principal: String, applicationId: String): Mono<List<DeploymentRun>>
    fun action(principal: String, applicationId: String, runId: String, action: DeploymentAction, requestId: String = UUID.randomUUID().toString()): Mono<DeploymentRun>
}

@Service
class KubernetesDeploymentService(
    private val namespaces: NamespaceAccessProvider,
    private val store: DeploymentStore,
    orchestrators: DeploymentRunOrchestrators,
    workloads: KubernetesDeploymentWorkloads,
    clock: Clock,
) : DeploymentProvider {
    private val client = DeploymentClient(store, workloads, clock, orchestrators::requireAvailable)

    override fun registerApplication(principal: String, request: RegisterApplicationRequest): Mono<DeploymentApplication> =
        access(principal, request.namespace).then(Mono.fromCallable { client.registerApplication(principal, request) }
            .subscribeOn(Schedulers.boundedElastic()))

    override fun applications(principal: String): Mono<List<DeploymentApplication>> = namespaces.findAccessible(principal)
        .flatMap { visible -> Mono.fromCallable {
            client.applications().filter { app -> visible.any { it.name == app.namespace } }
        }.subscribeOn(Schedulers.boundedElastic()) }

    override fun saveConfiguration(principal: String, applicationId: String, request: SaveDeploymentConfigurationRequest): Mono<DeploymentConfiguration> =
        authorized(principal, applicationId) { client.saveConfiguration(principal, applicationId, request) }

    override fun configurations(principal: String, applicationId: String): Mono<List<DeploymentConfiguration>> =
        authorized(principal, applicationId) { client.configurations(applicationId) }

    override fun submitRun(principal: String, applicationId: String, request: SubmitDeploymentRunRequest): Mono<DeploymentRun> =
        authorized(principal, applicationId) { client.submitRun(principal, applicationId, request) }

    override fun runs(principal: String, applicationId: String): Mono<List<DeploymentRun>> =
        authorized(principal, applicationId) { client.runs(applicationId) }

    override fun action(principal: String, applicationId: String, runId: String, action: DeploymentAction, requestId: String): Mono<DeploymentRun> =
        authorized(principal, applicationId) { client.action(principal, applicationId, runId, action, requestId) }

    private fun <T : Any> authorized(principal: String, id: String, operation: () -> T): Mono<T> =
        Mono.fromCallable { store.get(id) }.subscribeOn(Schedulers.boundedElastic()).flatMap { record ->
            access(principal, record.application.namespace).then(Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic()))
        }

    private fun access(principal: String, namespace: String): Mono<Void> = namespaces.findAccessible(principal).flatMap { visible ->
        if (visible.none { it.name == namespace }) Mono.error(DeploymentForbiddenException()) else Mono.empty()
    }
}
