package com.deploy.k8s.DeployDock.deployment

import java.time.Clock

class DeploymentReconciler(
    private val store: DeploymentStore,
    private val workloads: KubernetesDeploymentWorkloads,
    private val clock: Clock,
) {
    fun reconcile(applicationId: String, runId: String): Boolean {
        val record = store.update(applicationId) { record ->
            val run = record.runs.first { it.id == runId }
            if (run.terminal()) return@update record
            val config = record.configurations.first { it.id == run.configurationId }
            val next = try {
                workloads.authorize(run.actionBy ?: run.requestedBy, record.application, config)
                advance(record.application, config, run)
            } catch (failure: Exception) {
                when {
                    run.snapshot == null -> run.copy(status = DeploymentRunStatus.FAILED, error = failure.message)
                    run.status == DeploymentRunStatus.ABORTING -> run.copy(recoveryError = "rollback pending: ${failure.message}")
                    else -> run.copy(status = DeploymentRunStatus.ABORTING, phase = "RESTORE", action = null,
                        error = failure.message ?: "deployment failed", phaseStartedAt = clock.instant())
                }
            }
            record.copy(runs = record.runs.map { if (it.id == runId) next else it })
        }
        return record.runs.first { it.id == runId }.terminal()
    }

    private fun advance(app: DeploymentApplication, config: DeploymentConfiguration, run: DeploymentRun): DeploymentRun {
        if (run.action == DeploymentAction.ABORT) return run.copy(status = DeploymentRunStatus.ABORTING, phase = "RESTORE", action = null, phaseStartedAt = clock.instant())
        if (run.status != DeploymentRunStatus.AWAITING_APPROVAL && run.status != DeploymentRunStatus.ABORTING &&
            clock.instant().isAfter(run.phaseStartedAt.plusSeconds(config.progressDeadlineSeconds))) {
            throw DeploymentValidationException("deployment progress deadline exceeded")
        }
        return when (run.phase) {
            "PREPARE" -> run.copy(snapshot = workloads.snapshot(app, config), status = DeploymentRunStatus.RUNNING,
                phase = "APPLY", phaseStartedAt = clock.instant())
            "APPLY" -> when {
                app.kind == ApplicationKind.BATCH -> {
                    workloads.updateBatch(app, config, run)
                    run.copy(status = DeploymentRunStatus.SUCCEEDED, result = DeploymentExecutionResult("BATCH_${config.batchMode}",
                        requireNotNull(run.snapshot).cronJobs.map { DeploymentResourcePlan("batch/v1", "CronJob", app.namespace, it.metadata.name, "future jobs use ${config.image}") }))
                }
                config.webStrategy == WebDeploymentStrategy.ROLLING -> {
                    workloads.rolling(app, config, run)
                    run.copy(phase = "WAIT_READY", phaseStartedAt = clock.instant(), result = DeploymentExecutionResult("WEB_ROLLING",
                        listOf(DeploymentResourcePlan("apps/v1", "Deployment", app.namespace, app.activeDeployment, "waiting for updated replicas"))))
                }
                else -> run.copy(result = workloads.createPreview(app, config, run), phase = "WAIT_READY", phaseStartedAt = clock.instant())
            }
            "WAIT_READY" -> if (config.webStrategy == WebDeploymentStrategy.ROLLING) {
                if (workloads.rollingReady(app, run, config.image)) run.copy(status = DeploymentRunStatus.SUCCEEDED) else run
            } else if (workloads.previewReady(app, config, run)) {
                run.copy(phase = "APPROVAL", status = DeploymentRunStatus.AWAITING_APPROVAL)
            } else run
            "APPROVAL" -> when (run.action) {
                DeploymentAction.ADVANCE -> run.copy(phase = "ROUTE", status = DeploymentRunStatus.RUNNING,
                    step = run.step + 1, action = null, phaseStartedAt = clock.instant())
                DeploymentAction.PROMOTE -> run.copy(phase = "SWITCH", status = DeploymentRunStatus.PROMOTING,
                    action = null, phaseStartedAt = clock.instant())
                else -> {
                    if (!workloads.previewReady(app, config, run)) throw DeploymentValidationException("preview lost readiness")
                    run
                }
            }
            "ROUTE" -> {
                if (!config.usesWeightedTraffic()) throw DeploymentValidationException("weighted traffic is not configured")
                if (!workloads.previewReady(app, config, run)) throw DeploymentValidationException("canary lost readiness")
                val weight = config.canarySteps[run.step]
                workloads.setCanaryWeight(app, config, run, weight)
                if (workloads.canaryRouteReady(app, config)) run.copy(phase = "APPROVAL", status = DeploymentRunStatus.AWAITING_APPROVAL,
                    result = run.result?.copy(canaryWeight = weight)) else run
            }
            "SWITCH" -> {
                if (!workloads.previewReady(app, config, run)) throw DeploymentValidationException("preview is not ready for promotion")
                workloads.switchService(app, run)
                run.copy(phase = "SWITCH_WAIT", phaseStartedAt = clock.instant())
            }
            "SWITCH_WAIT" -> {
                if (!workloads.previewReady(app, config, run)) throw DeploymentValidationException("promoted Deployment lost readiness")
                if (!workloads.endpointsReady(app.namespace, requireNotNull(app.serviceName), run.id)) return run
                if (config.usesWeightedTraffic()) {
                    workloads.setCanaryWeight(app, config, run, null)
                    if (!workloads.canaryRouteReady(app, config)) return run
                }
                run.copy(phase = "UPDATE_SOURCE", phaseStartedAt = clock.instant())
            }
            "UPDATE_SOURCE" -> {
                workloads.rolling(app, config, run)
                run.copy(phase = "SOURCE_READY", phaseStartedAt = clock.instant())
            }
            "SOURCE_READY" -> {
                if (!workloads.rollingReady(app, run, config.image)) return run
                workloads.switchService(app, run, restore = true)
                run.copy(phase = "SOURCE_TRAFFIC")
            }
            "SOURCE_TRAFFIC" -> {
                if (!workloads.rollingReady(app, run, config.image)) return run
                if (!workloads.originalEndpointsReady(app, run)) return run
                workloads.retirePreview(app, run)
                run.copy(status = DeploymentRunStatus.SUCCEEDED, result = run.result?.copy(canaryWeight = 100))
            }
            "ROLLBACK_PREVIEW" -> {
                workloads.resumePreview(app, config, run)
                if (!workloads.previewReady(app, config, run)) return run
                workloads.switchService(app, run)
                if (!workloads.endpointsReady(app.namespace, requireNotNull(app.serviceName), run.id)) return run
                run.copy(phase = "RESTORE")
            }
            "RESTORE" -> {
                if (run.snapshot == null) return run.copy(status = DeploymentRunStatus.ABORTED)
                when {
                    app.kind == ApplicationKind.BATCH -> workloads.updateBatch(app, config, run, restore = true)
                    config.webStrategy == WebDeploymentStrategy.ROLLING -> workloads.restoreRolling(app, config, run)
                    else -> {
                        workloads.restoreRolling(app, config, run)
                        if (!workloads.rollingReady(app, run)) return run
                        workloads.switchService(app, run, restore = true)
                        if (config.usesWeightedTraffic()) workloads.setCanaryWeight(app, config, run, null)
                    }
                }
                run.copy(phase = "RESTORE_WAIT")
            }
            "RESTORE_WAIT" -> {
                if (app.kind == ApplicationKind.WEB) {
                    if (!workloads.rollingReady(app, run)) return run
                    if (config.webStrategy != WebDeploymentStrategy.ROLLING) {
                        if (!workloads.originalEndpointsReady(app, run)) return run
                        if (config.usesWeightedTraffic() && !workloads.canaryRouteReady(app, config)) return run
                        workloads.retirePreview(app, run)
                    }
                }
                run.copy(recoveryError = null, status = when {
                    run.rollbackRequested -> DeploymentRunStatus.ROLLED_BACK
                    run.error != null -> DeploymentRunStatus.FAILED
                    else -> DeploymentRunStatus.ABORTED
                })
            }
            else -> throw DeploymentValidationException("unknown deployment phase '${run.phase}'")
        }
    }
}
