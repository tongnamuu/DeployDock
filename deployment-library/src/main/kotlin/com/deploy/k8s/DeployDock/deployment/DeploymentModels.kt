package com.deploy.k8s.DeployDock.deployment

import java.time.Instant

enum class ApplicationKind {
    WEB,
    BATCH,
}

enum class DeploymentOrchestrator {
    LOCAL,
    TEMPORAL,
}

enum class WebDeploymentStrategy {
    ROLLING,
    BLUE_GREEN,
    CANARY,
}

enum class BatchDeploymentMode {
    GROUPED,
    INDIVIDUAL,
}

enum class CanaryTrafficMode { PREVIEW_ONLY, WEIGHTED }

enum class DeploymentRunStatus {
    QUEUED,
    RUNNING,
    AWAITING_APPROVAL,
    PROMOTING,
    ABORTING,
    ABORTED,
    ROLLED_BACK,
    SUCCEEDED,
    FAILED,
}

data class RegisterApplicationRequest(
    val name: String,
    val namespace: String,
    val kind: ApplicationKind,
    val orchestrator: DeploymentOrchestrator = DeploymentOrchestrator.LOCAL,
    val serviceName: String? = null,
    val containerName: String? = null,
)

data class DeploymentApplication(
    val id: String,
    val name: String,
    val namespace: String,
    val kind: ApplicationKind,
    val orchestrator: DeploymentOrchestrator,
    val createdBy: String,
    val createdAt: Instant,
    val serviceName: String? = null,
    val containerName: String? = null,
    val activeDeployment: String = name,
)

data class SaveDeploymentConfigurationRequest(
    val image: String,
    val replicas: Int? = null,
    val webStrategy: WebDeploymentStrategy? = null,
    val batchMode: BatchDeploymentMode? = null,
    val batchTargets: List<String> = emptyList(),
    val canaryRoute: String? = null,
    val canarySteps: List<Int> = listOf(10, 50),
    val progressDeadlineSeconds: Long = 600,
    val trafficAdapter: String? = null,
    val trafficOptions: Map<String, String> = emptyMap(),
)

data class DeploymentConfiguration(
    val id: String,
    val applicationId: String,
    val revision: Int,
    val image: String,
    val replicas: Int?,
    val webStrategy: WebDeploymentStrategy?,
    val batchMode: BatchDeploymentMode?,
    val batchTargets: List<String>,
    val savedBy: String,
    val savedAt: Instant,
    val canaryRoute: String? = null,
    val canarySteps: List<Int> = listOf(10, 50),
    val progressDeadlineSeconds: Long = 600,
    val trafficAdapter: String? = null,
    val trafficOptions: Map<String, String> = emptyMap(),
)

data class SubmitDeploymentRunRequest(
    val configurationId: String,
    val requestId: String,
)

data class DeploymentRun(
    val id: String,
    val applicationId: String,
    val configurationId: String,
    val kind: ApplicationKind,
    val orchestrator: DeploymentOrchestrator,
    val webStrategy: WebDeploymentStrategy?,
    val batchMode: BatchDeploymentMode?,
    val batchTargets: List<String>,
    val status: DeploymentRunStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val executionId: String? = null,
    val result: DeploymentExecutionResult? = null,
    val requestId: String = id,
    val phase: String = "PREPARE",
    val action: DeploymentAction? = null,
    val step: Int = -1,
    val phaseStartedAt: Instant = requestedAt,
    val error: String? = null,
    val snapshot: DeploymentSnapshot? = null,
    val rollbackRequested: Boolean = false,
    val recoveryError: String? = null,
    val actionBy: String? = null,
    val actionRequests: Map<String, DeploymentAction> = emptyMap(),
    val configuration: DeploymentConfiguration? = null,
)

enum class DeploymentAction { ADVANCE, PROMOTE, ABORT, ROLLBACK }

data class DeploymentActionRequest(val action: DeploymentAction, val requestId: String)

data class DeploymentSnapshot(
    val deployment: io.fabric8.kubernetes.api.model.apps.Deployment? = null,
    val service: io.fabric8.kubernetes.api.model.Service? = null,
    val route: io.fabric8.kubernetes.api.model.GenericKubernetesResource? = null,
    val cronJobs: List<io.fabric8.kubernetes.api.model.batch.v1.CronJob> = emptyList(),
    val isolationKey: String? = null,
    val traffic: TrafficSnapshot? = null,
)

data class DeploymentRecord(
    val application: DeploymentApplication,
    val configurations: List<DeploymentConfiguration> = emptyList(),
    val runs: List<DeploymentRun> = emptyList(),
    val batchExecutions: List<BatchExecution> = emptyList(),
)

data class DeploymentExecutionResult(
    val mode: String,
    val resources: List<DeploymentResourcePlan>,
    val previewService: String? = null,
    val previewPorts: List<Int> = emptyList(),
    val canaryWeight: Int = 0,
    val trafficMode: CanaryTrafficMode? = null,
)

data class DeploymentResourcePlan(
    val apiVersion: String,
    val kind: String,
    val namespace: String,
    val name: String,
    val purpose: String,
)

class DeploymentForbiddenException : RuntimeException("deployment access is not allowed")
class DeploymentValidationException(message: String) : RuntimeException(message)
class DeploymentApplicationNotFoundException(id: String) : RuntimeException("application '$id' does not exist")
class DeploymentConfigurationNotFoundException(id: String) : RuntimeException("configuration '$id' does not exist")
class DeploymentConflictException(message: String) : RuntimeException(message)
class DeploymentUnavailableException(message: String) : RuntimeException(message)

fun DeploymentRun.terminal(): Boolean = status in setOf(DeploymentRunStatus.SUCCEEDED, DeploymentRunStatus.FAILED, DeploymentRunStatus.ABORTED, DeploymentRunStatus.ROLLED_BACK)

fun DeploymentConfiguration.usesWeightedTraffic(): Boolean =
    webStrategy == WebDeploymentStrategy.CANARY && (trafficAdapter != null || canaryRoute != null)

fun DeploymentRecord.configurationFor(run: DeploymentRun): DeploymentConfiguration =
    run.configuration ?: configurations.first { it.id == run.configurationId }
