package com.deploy.k8s.DeployDock.deployment

import java.time.Clock
import java.util.UUID

class DeploymentClient(
    private val store: DeploymentStore,
    private val workloads: KubernetesDeploymentWorkloads,
    private val clock: Clock = Clock.systemUTC(),
    private val requireOrchestrator: (DeploymentOrchestrator) -> Unit = {
        if (it != DeploymentOrchestrator.LOCAL) throw DeploymentUnavailableException("configure a Temporal scheduler before selecting TEMPORAL")
    },
) {
    fun capabilities() = workloads.capabilities()

    fun registerApplication(principal: String, request: RegisterApplicationRequest): DeploymentApplication {
        listOf(request.name, request.namespace).forEach {
            if (it.length !in 1..63 || !it.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?"))) throw DeploymentValidationException("name and namespace must be Kubernetes names")
        }
        requireOrchestrator(request.orchestrator)
        val id = "app-" + UUID.nameUUIDFromBytes("${request.namespace}/${request.name}".toByteArray())
        val app = DeploymentApplication(id, request.name, request.namespace, request.kind, request.orchestrator,
            principal, clock.instant(), request.serviceName ?: request.name, request.containerName)
        return store.create(DeploymentRecord(app)).application
    }

    fun applications(): List<DeploymentApplication> = store.list().map { it.application }
    fun configurations(applicationId: String): List<DeploymentConfiguration> =
        listOfNotNull(store.get(applicationId).configurations.maxByOrNull { it.revision })
    fun runs(applicationId: String): List<DeploymentRun> = store.get(applicationId).let { record ->
        record.runs.map { it.copy(configuration = record.configurationFor(it)) }
    }

    fun saveConfiguration(principal: String, applicationId: String, request: SaveDeploymentConfigurationRequest): DeploymentConfiguration {
        return store.update(applicationId) { record ->
            validate(record.application, request)
            val config = DeploymentConfiguration(newId("cfg"), applicationId,
                (record.configurations.maxOfOrNull { it.revision } ?: 0) + 1,
                request.image.trim(), request.replicas, request.webStrategy, request.batchMode,
                request.batchTargets, principal, clock.instant(), request.canaryRoute,
                request.canarySteps, request.progressDeadlineSeconds, request.trafficAdapter, request.trafficOptions)
            workloads.validateTraffic(config)
            record.copy(configurations = listOf(config),
                runs = record.runs.map { it.copy(configuration = record.configurationFor(it)) })
        }.configurations.last()
    }

    fun submitRun(principal: String, applicationId: String, request: SubmitDeploymentRunRequest): DeploymentRun {
        if (request.requestId.isBlank() || request.requestId.length > 128) throw DeploymentValidationException("requestId must contain 1 to 128 characters")
        return store.update(applicationId) { record ->
            val existing = record.runs.find { it.requestId == request.requestId }
            if (existing != null) {
                if (existing.configurationId != request.configurationId) throw DeploymentConflictException("requestId already belongs to a different configuration")
                return@update record
            }
            if (record.runs.any { !it.terminal() }) throw DeploymentConflictException("application already has an active run")
            val config = record.configurations.maxByOrNull { it.revision }
                ?: throw DeploymentConfigurationNotFoundException(request.configurationId)
            if (config.id != request.configurationId) throw DeploymentConflictException("only the latest configuration can be deployed; refresh and retry")
            val app = record.application
            requireOrchestrator(app.orchestrator)
            workloads.authorize(principal, app, config)
            val id = newId("run")
            val run = DeploymentRun(id, app.id, config.id, app.kind, app.orchestrator, config.webStrategy,
                config.batchMode, config.batchTargets, DeploymentRunStatus.QUEUED, principal, clock.instant(),
                executionId = "deploydock-$id", requestId = request.requestId, configuration = config)
            record.copy(runs = record.runs + run)
        }.runs.first { it.requestId == request.requestId }
    }

    fun action(principal: String, applicationId: String, runId: String, action: DeploymentAction, requestId: String): DeploymentRun {
        if (requestId.isBlank() || requestId.length > 128) throw DeploymentValidationException("requestId must contain 1 to 128 characters")
        return store.update(applicationId) { record ->
            val run = record.runs.find { it.id == runId } ?: throw DeploymentValidationException("run does not exist")
            val config = record.configurationFor(run)
            workloads.authorize(principal, record.application, config)
            val previous = run.actionRequests[requestId]
            if (previous != null) {
                if (previous != action) throw DeploymentConflictException("requestId already belongs to a different action")
                return@update record
            }
            if (action == DeploymentAction.ROLLBACK) {
                if (run.status != DeploymentRunStatus.SUCCEEDED || record.runs.last().id != runId || record.runs.any { !it.terminal() }) {
                    throw DeploymentConflictException("only the most recent successful run can be rolled back")
                }
                return@update record.copy(runs = record.runs.map {
                    if (it.id == runId) it.copy(status = DeploymentRunStatus.ABORTING,
                        phase = if (run.kind == ApplicationKind.WEB && run.webStrategy != WebDeploymentStrategy.ROLLING) "ROLLBACK_PREVIEW" else "RESTORE",
                        rollbackRequested = true, actionBy = principal, error = null,
                        actionRequests = it.actionRequests + (requestId to action)) else it
                })
            }
            if (run.terminal()) throw DeploymentConflictException("run has already completed")
            if (run.action != null) throw DeploymentConflictException("another action is pending")
            if (action != DeploymentAction.ABORT && run.status != DeploymentRunStatus.AWAITING_APPROVAL) {
                throw DeploymentConflictException("new version is not ready for approval")
            }
            if (action == DeploymentAction.ADVANCE && !config.usesWeightedTraffic()) {
                throw DeploymentConflictException("ADVANCE requires weighted traffic; test the preview and use PROMOTE")
            }
            if (action == DeploymentAction.ADVANCE && run.step + 1 >= config.canarySteps.size) {
                throw DeploymentConflictException("no remaining canary step")
            }
            if (action == DeploymentAction.PROMOTE && config.usesWeightedTraffic() && run.step != config.canarySteps.lastIndex) {
                throw DeploymentConflictException("complete the canary steps before promotion")
            }
            record.copy(runs = record.runs.map { if (it.id == runId) it.copy(action = action, actionBy = principal,
                actionRequests = it.actionRequests + (requestId to action)) else it })
        }.runs.first { it.id == runId }
    }

    private fun validate(app: DeploymentApplication, request: SaveDeploymentConfigurationRequest) {
        if (request.image.isBlank() || request.image.length > 512) throw DeploymentValidationException("image is required")
        if (request.replicas != null && request.replicas < 1) throw DeploymentValidationException("replicas must be positive")
        if (request.progressDeadlineSeconds !in 30..3600) throw DeploymentValidationException("progress deadline must be between 30 and 3600 seconds")
        if (app.kind == ApplicationKind.WEB) {
            if (request.webStrategy == null || request.batchMode != null || request.batchTargets.isNotEmpty()) throw DeploymentValidationException("web strategy is required and batch fields are not allowed")
            if (request.webStrategy == WebDeploymentStrategy.CANARY && (request.trafficAdapter != null || request.canaryRoute != null) &&
                (request.canarySteps.isEmpty() || request.canarySteps.any { it !in 1..99 } || request.canarySteps.zipWithNext().any { it.first >= it.second })) {
                throw DeploymentValidationException("canarySteps must strictly increase between 1 and 99")
            }
        } else {
            if (request.batchMode == null || request.webStrategy != null || request.canaryRoute != null || request.trafficAdapter != null || request.trafficOptions.isNotEmpty()) throw DeploymentValidationException("batch mode is required and web fields are not allowed")
            val count = request.batchTargets.size
            if (count > 10 || request.batchTargets.distinct().size != count || request.batchTargets.any { !it.matches(Regex("[a-z0-9]([-a-z0-9]*[a-z0-9])?")) }) throw DeploymentValidationException("batchTargets must contain at most 10 distinct CronJob names")
            if (request.batchMode == BatchDeploymentMode.GROUPED && count < 2) throw DeploymentValidationException("GROUPED requires at least two CronJobs")
            if (request.batchMode == BatchDeploymentMode.INDIVIDUAL && count > 1) throw DeploymentValidationException("INDIVIDUAL accepts at most one CronJob")
        }
    }

    private fun newId(prefix: String) = "$prefix-${UUID.randomUUID()}"
}
