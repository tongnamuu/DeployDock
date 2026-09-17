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
import java.time.Duration

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
