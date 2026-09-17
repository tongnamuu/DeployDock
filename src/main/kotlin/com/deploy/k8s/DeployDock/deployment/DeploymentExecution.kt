package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockTemporalProperties
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import io.temporal.common.RetryOptions
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.common.converter.JacksonJsonPayloadConverter
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.WorkerFactory
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
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
                if (config.webStrategy == WebDeploymentStrategy.CANARY) {
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
                        if (config.webStrategy == WebDeploymentStrategy.CANARY) workloads.setCanaryWeight(app, config, run, null)
                    }
                }
                run.copy(phase = "RESTORE_WAIT")
            }
            "RESTORE_WAIT" -> {
                if (app.kind == ApplicationKind.WEB) {
                    if (!workloads.rollingReady(app, run)) return run
                    if (config.webStrategy != WebDeploymentStrategy.ROLLING) {
                        if (!workloads.originalEndpointsReady(app, run)) return run
                        if (config.webStrategy == WebDeploymentStrategy.CANARY && !workloads.canaryRouteReady(app, config)) return run
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

@ActivityInterface
interface DeploymentActivities {
    fun reconcile(applicationId: String, runId: String): Boolean
}

@Component
class DeploymentActivitiesImpl(private val reconciler: DeploymentReconciler) : DeploymentActivities {
    override fun reconcile(applicationId: String, runId: String) = reconciler.reconcile(applicationId, runId)
}

@WorkflowInterface
interface DeploymentWorkflow {
    @WorkflowMethod
    fun deploy(applicationId: String, runId: String)
}

class DeploymentWorkflowImpl : DeploymentWorkflow {
    private val activities = Workflow.newActivityStub(DeploymentActivities::class.java, ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(2))
        .setRetryOptions(RetryOptions.newBuilder().setInitialInterval(Duration.ofSeconds(5)).setMaximumInterval(Duration.ofMinutes(1)).build()).build())

    override fun deploy(applicationId: String, runId: String) {
        repeat(500) {
            if (activities.reconcile(applicationId, runId)) return
            Workflow.sleep(Duration.ofSeconds(5))
        }
        Workflow.continueAsNew(applicationId, runId)
    }
}

fun deploymentDataConverter() = DefaultDataConverter.newDefaultInstance()
    .withPayloadConverterOverrides(JacksonJsonPayloadConverter(deploymentMapper()))

@Component
class DeploymentRunOrchestrators(
    private val store: DeploymentStore,
    private val reconciler: DeploymentReconciler,
    private val workflowClient: WorkflowClient?,
    private val properties: DeployDockTemporalProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun requireAvailable(orchestrator: DeploymentOrchestrator) {
        if (orchestrator == DeploymentOrchestrator.TEMPORAL && workflowClient == null) throw DeploymentUnavailableException("Temporal is disabled; enable it or explicitly select LOCAL")
    }

    @Scheduled(fixedDelayString = "\${deploydock.deployment.poll-ms:5000}")
    fun dispatch() {
        try {
            store.list().forEach { record -> record.runs.filter { !it.terminal() }.forEach { run ->
                try {
                    if (run.orchestrator == DeploymentOrchestrator.LOCAL) reconciler.reconcile(record.application.id, run.id)
                    else startTemporal(record.application.id, run)
                } catch (failure: Exception) {
                    logger.warn("Deployment {} will be retried: {}", run.id, failure.message)
                }
            } }
        } catch (failure: Exception) {
            logger.warn("Deployment dispatch unavailable: {}", failure.message)
        }
    }

    private fun startTemporal(applicationId: String, run: DeploymentRun) {
        requireAvailable(DeploymentOrchestrator.TEMPORAL)
        val workflow = requireNotNull(workflowClient).newWorkflowStub(DeploymentWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(properties.taskQueue).setWorkflowId(run.executionId)
                .setWorkflowIdReusePolicy(io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE).build())
        try {
            WorkflowClient.start(workflow::deploy, applicationId, run.id)
        } catch (_: WorkflowExecutionAlreadyStarted) {
        }
    }
}

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "deploydock.deployment", name = ["scheduler-enabled"], havingValue = "true", matchIfMissing = true)
class DeploymentSchedulingConfiguration

@Configuration
@ConditionalOnProperty(prefix = "deploydock.temporal", name = ["enabled"], havingValue = "true")
class TemporalDeploymentConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun workflowService(properties: DeployDockTemporalProperties): WorkflowServiceStubs = WorkflowServiceStubs.newServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(properties.target).build())

    @Bean
    fun workflowClient(service: WorkflowServiceStubs, properties: DeployDockTemporalProperties): WorkflowClient = WorkflowClient.newInstance(service,
        WorkflowClientOptions.newBuilder().setNamespace(properties.namespace).setDataConverter(deploymentDataConverter()).build())

    @Bean
    fun temporalDeploymentWorker(client: WorkflowClient, properties: DeployDockTemporalProperties, activities: DeploymentActivities) =
        TemporalDeploymentWorker(client, properties, activities)
}

class TemporalDeploymentWorker(client: WorkflowClient, private val properties: DeployDockTemporalProperties,
    private val activities: DeploymentActivities) : SmartLifecycle {
    private val factory = WorkerFactory.newInstance(client)
    private var running = false
    override fun start() {
        val worker = factory.newWorker(properties.taskQueue)
        worker.registerWorkflowImplementationTypes(DeploymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        factory.start()
        running = true
    }
    override fun stop() {
        factory.shutdown()
        running = false
    }
    override fun isRunning() = running
}
