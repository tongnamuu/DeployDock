package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockTemporalProperties
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.worker.WorkerFactory
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration

data class DeploymentExecutionCommand(
    val run: DeploymentRun,
    val application: DeploymentApplication,
    val configuration: DeploymentConfiguration,
)

interface DeploymentExecutor {
    fun execute(command: DeploymentExecutionCommand): DeploymentExecutionResult
}

@Component
class DeploymentPlanExecutor : DeploymentExecutor {
    override fun execute(command: DeploymentExecutionCommand): DeploymentExecutionResult =
        when (command.application.kind) {
            ApplicationKind.WEB -> web(command)
            ApplicationKind.BATCH -> batch(command)
        }

    private fun web(command: DeploymentExecutionCommand): DeploymentExecutionResult {
        val app = command.application
        val config = command.configuration
        val strategy = requireNotNull(config.webStrategy)
        val resource = when (strategy) {
            WebDeploymentStrategy.ROLLING -> DeploymentResourcePlan(
                apiVersion = "apps/v1",
                kind = "Deployment",
                namespace = app.namespace,
                name = app.name,
                purpose = "rolling replacement for ${config.image}",
            )
            WebDeploymentStrategy.BLUE_GREEN -> DeploymentResourcePlan(
                apiVersion = "apps/v1",
                kind = "Deployment",
                namespace = app.namespace,
                name = app.name,
                purpose = "blue-green orchestration for existing Deployment using ${config.image}",
            )
            WebDeploymentStrategy.CANARY -> DeploymentResourcePlan(
                apiVersion = "apps/v1",
                kind = "Deployment",
                namespace = app.namespace,
                name = app.name,
                purpose = "canary orchestration for existing Deployment using ${config.image}",
            )
        }
        return DeploymentExecutionResult("WEB_${strategy.name}", listOf(resource))
    }

    private fun batch(command: DeploymentExecutionCommand): DeploymentExecutionResult {
        val app = command.application
        val config = command.configuration
        val mode = requireNotNull(config.batchMode)
        val targets = config.batchTargets.ifEmpty { listOf(app.name) }
        return DeploymentExecutionResult(
            "BATCH_${mode.name}",
            targets.map { target ->
                DeploymentResourcePlan(
                    apiVersion = "batch.deploydock.io/v1alpha1",
                    kind = "BatchVersionBinding",
                    namespace = app.namespace,
                    name = "${app.name}-$target",
                    purpose = "activate ${config.image} for $target",
                )
            },
        )
    }
}

@ActivityInterface
interface DeploymentActivities {
    @ActivityMethod
    fun execute(command: DeploymentExecutionCommand): DeploymentExecutionResult
}

@Component
class DeploymentActivitiesImpl(
    private val executor: DeploymentExecutor,
) : DeploymentActivities {
    override fun execute(command: DeploymentExecutionCommand): DeploymentExecutionResult =
        executor.execute(command)
}

@WorkflowInterface
interface DeploymentWorkflow {
    @WorkflowMethod
    fun deploy(command: DeploymentExecutionCommand): DeploymentExecutionResult
}

class DeploymentWorkflowImpl : DeploymentWorkflow {
    private val activities = io.temporal.workflow.Workflow.newActivityStub(
        DeploymentActivities::class.java,
        io.temporal.activity.ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(10))
            .build(),
    )

    override fun deploy(command: DeploymentExecutionCommand): DeploymentExecutionResult =
        activities.execute(command)
}

interface DeploymentRunOrchestrator {
    fun execute(command: DeploymentExecutionCommand): OrchestratedDeploymentResult
}

data class OrchestratedDeploymentResult(
    val executionId: String,
    val result: DeploymentExecutionResult,
)

@Component
class LocalDeploymentRunOrchestrator(
    private val executor: DeploymentExecutor,
) : DeploymentRunOrchestrator {
    override fun execute(command: DeploymentExecutionCommand): OrchestratedDeploymentResult =
        OrchestratedDeploymentResult("local-${command.run.id}", executor.execute(command))
}

@Component
@ConditionalOnProperty(prefix = "deploydock.temporal", name = ["enabled"], havingValue = "true")
class TemporalDeploymentRunOrchestrator(
    private val workflowClient: WorkflowClient,
    private val properties: DeployDockTemporalProperties,
) {
    fun execute(command: DeploymentExecutionCommand): OrchestratedDeploymentResult {
        val workflowId = "deploydock-${command.run.id}"
        val workflow = workflowClient.newWorkflowStub(
            DeploymentWorkflow::class.java,
            WorkflowOptions.newBuilder()
                .setTaskQueue(properties.taskQueue)
                .setWorkflowId(workflowId)
                .build(),
        )
        val result = workflow.deploy(command)
        return OrchestratedDeploymentResult(workflowId, result)
    }
}

@Component
class DeploymentRunOrchestrators(
    private val local: LocalDeploymentRunOrchestrator,
    private val temporal: TemporalDeploymentRunOrchestrator?,
) {
    fun execute(command: DeploymentExecutionCommand): OrchestratedDeploymentResult =
        if (command.application.orchestrator == DeploymentOrchestrator.TEMPORAL && temporal != null) {
            temporal.execute(command)
        } else {
            local.execute(command)
        }
}

@Configuration
@ConditionalOnProperty(prefix = "deploydock.temporal", name = ["enabled"], havingValue = "true")
class TemporalDeploymentConfiguration {
    @Bean
    fun workflowClient(properties: DeployDockTemporalProperties): WorkflowClient =
        WorkflowClient.newInstance(
            io.temporal.serviceclient.WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder()
                    .setTarget(properties.target)
                    .build(),
            ),
            WorkflowClientOptions.newBuilder()
                .setNamespace(properties.namespace)
                .build(),
        )

    @Bean
    fun temporalDeploymentWorker(
        workflowClient: WorkflowClient,
        properties: DeployDockTemporalProperties,
        activities: DeploymentActivities,
    ): TemporalDeploymentWorker =
        TemporalDeploymentWorker(workflowClient, properties, activities)
}

class TemporalDeploymentWorker(
    workflowClient: WorkflowClient,
    private val properties: DeployDockTemporalProperties,
    private val activities: DeploymentActivities,
) : SmartLifecycle {
    private val factory = WorkerFactory.newInstance(workflowClient)
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

    override fun isRunning(): Boolean = running
}
