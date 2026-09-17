package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface DeploymentProvider {
    fun registerApplication(principal: String, request: RegisterApplicationRequest): Mono<DeploymentApplication>
    fun applications(principal: String): Mono<List<DeploymentApplication>>
    fun saveConfiguration(
        principal: String,
        applicationId: String,
        request: SaveDeploymentConfigurationRequest,
    ): Mono<DeploymentConfiguration>
    fun configurations(principal: String, applicationId: String): Mono<List<DeploymentConfiguration>>
    fun submitRun(principal: String, applicationId: String, request: SubmitDeploymentRunRequest): Mono<DeploymentRun>
    fun runs(principal: String, applicationId: String): Mono<List<DeploymentRun>>
}

@Service
class InMemoryDeploymentService(
    private val namespaces: NamespaceAccessProvider,
    private val orchestrators: DeploymentRunOrchestrators,
    private val clock: Clock,
) : DeploymentProvider {
    private val applications = ConcurrentHashMap<String, DeploymentApplication>()
    private val configurations = ConcurrentHashMap<String, DeploymentConfiguration>()
    private val revisionSequences = ConcurrentHashMap<String, AtomicInteger>()
    private val runs = ConcurrentHashMap<String, DeploymentRun>()

    override fun registerApplication(
        principal: String,
        request: RegisterApplicationRequest,
    ): Mono<DeploymentApplication> =
        requireNamespaceAccess(principal, request.namespace).map {
            val app = DeploymentApplication(
                id = newId("app"),
                name = request.name,
                namespace = request.namespace,
                kind = request.kind,
                orchestrator = request.orchestrator,
                createdBy = principal,
                createdAt = clock.instant(),
            )
            applications[app.id] = app
            app
        }

    override fun applications(principal: String): Mono<List<DeploymentApplication>> =
        namespaces.findAccessible(principal).map { visible ->
            val names = visible.map { it.name }.toSet()
            applications.values.filter { it.namespace in names }.sortedBy { it.name }
        }

    override fun saveConfiguration(
        principal: String,
        applicationId: String,
        request: SaveDeploymentConfigurationRequest,
    ): Mono<DeploymentConfiguration> {
        val app = application(applicationId)
        validateConfiguration(app, request)
        return requireNamespaceAccess(principal, app.namespace).map {
            val config = DeploymentConfiguration(
                id = newId("cfg"),
                applicationId = app.id,
                revision = revisionSequences.computeIfAbsent(app.id) { AtomicInteger() }.incrementAndGet(),
                image = request.image.trim(),
                replicas = request.replicas,
                webStrategy = request.webStrategy,
                batchMode = request.batchMode,
                batchTargets = request.batchTargets.map { it.trim() },
                savedBy = principal,
                savedAt = clock.instant(),
            )
            configurations[config.id] = config
            config
        }
    }

    override fun configurations(principal: String, applicationId: String): Mono<List<DeploymentConfiguration>> {
        val app = application(applicationId)
        return requireNamespaceAccess(principal, app.namespace).map {
            configurations.values.filter { it.applicationId == app.id }.sortedBy { it.revision }
        }
    }

    override fun submitRun(
        principal: String,
        applicationId: String,
        request: SubmitDeploymentRunRequest,
    ): Mono<DeploymentRun> {
        val app = application(applicationId)
        val config = configurations[request.configurationId]
            ?: throw DeploymentConfigurationNotFoundException(request.configurationId)
        if (config.applicationId != app.id) throw DeploymentConfigurationNotFoundException(request.configurationId)
        return requireNamespaceAccess(principal, app.namespace).map {
            val queued = DeploymentRun(
                id = newId("run"),
                applicationId = app.id,
                configurationId = config.id,
                kind = app.kind,
                orchestrator = app.orchestrator,
                webStrategy = config.webStrategy,
                batchMode = config.batchMode,
                batchTargets = config.batchTargets,
                status = DeploymentRunStatus.QUEUED,
                requestedBy = principal,
                requestedAt = clock.instant(),
            )
            runs[queued.id] = queued
            val running = queued.copy(status = DeploymentRunStatus.RUNNING)
            runs[queued.id] = running
            runCatching {
                orchestrators.execute(DeploymentExecutionCommand(running, app, config))
            }.fold(
                onSuccess = { outcome ->
                    running.copy(
                        status = DeploymentRunStatus.SUCCEEDED,
                        executionId = outcome.executionId,
                        result = outcome.result,
                    )
                },
                onFailure = { failure ->
                    running.copy(
                        status = DeploymentRunStatus.FAILED,
                        result = DeploymentExecutionResult(
                            "FAILED",
                            listOf(
                                DeploymentResourcePlan(
                                    apiVersion = "deploydock.io/v1alpha1",
                                    kind = "DeploymentFailure",
                                    namespace = app.namespace,
                                    name = running.id,
                                    purpose = failure.message ?: "deployment execution failed",
                                ),
                            ),
                        ),
                    )
                },
            ).also { runs[it.id] = it }
        }
    }

    override fun runs(principal: String, applicationId: String): Mono<List<DeploymentRun>> {
        val app = application(applicationId)
        return requireNamespaceAccess(principal, app.namespace).map {
            runs.values.filter { it.applicationId == app.id }.sortedBy { it.requestedAt }
        }
    }

    private fun validateConfiguration(app: DeploymentApplication, request: SaveDeploymentConfigurationRequest) {
        if (request.image.isBlank()) throw DeploymentValidationException("image is required")
        if (request.replicas != null && request.replicas < 0) {
            throw DeploymentValidationException("replicas must be zero or greater")
        }
        when (app.kind) {
            ApplicationKind.WEB -> {
                if (request.webStrategy == null) throw DeploymentValidationException("webStrategy is required for web applications")
                if (request.batchMode != null || request.batchTargets.isNotEmpty()) {
                    throw DeploymentValidationException("batch deployment fields are not allowed for web applications")
                }
            }
            ApplicationKind.BATCH -> {
                if (request.batchMode == null) throw DeploymentValidationException("batchMode is required for batch applications")
                if (request.webStrategy != null) {
                    throw DeploymentValidationException("webStrategy is not allowed for batch applications")
                }
                if (request.batchMode == BatchDeploymentMode.GROUPED && request.batchTargets.size < 2) {
                    throw DeploymentValidationException("GROUPED batch deployments require at least two batchTargets")
                }
                if (request.batchMode == BatchDeploymentMode.INDIVIDUAL && request.batchTargets.size > 1) {
                    throw DeploymentValidationException("INDIVIDUAL batch deployments accept at most one batchTarget")
                }
            }
        }
    }

    private fun application(id: String): DeploymentApplication =
        applications[id] ?: throw DeploymentApplicationNotFoundException(id)

    private fun requireNamespaceAccess(principal: String, namespace: String): Mono<Unit> =
        namespaces.findAccessible(principal).map { visible ->
            if (visible.none { it.name == namespace }) throw DeploymentForbiddenException()
        }

    private fun newId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
}
