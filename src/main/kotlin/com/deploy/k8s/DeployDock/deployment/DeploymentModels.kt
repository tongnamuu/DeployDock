package com.deploy.k8s.DeployDock.deployment

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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

enum class DeploymentRunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

data class RegisterApplicationRequest(
    @field:Pattern(regexp = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
    @field:Size(max = 63)
    val name: String,
    @field:Pattern(regexp = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?")
    @field:Size(max = 63)
    val namespace: String,
    val kind: ApplicationKind,
    val orchestrator: DeploymentOrchestrator = DeploymentOrchestrator.LOCAL,
)

data class DeploymentApplication(
    val id: String,
    val name: String,
    val namespace: String,
    val kind: ApplicationKind,
    val orchestrator: DeploymentOrchestrator,
    val createdBy: String,
    val createdAt: Instant,
)

data class SaveDeploymentConfigurationRequest(
    @field:NotBlank
    @field:Size(max = 512)
    val image: String,
    val replicas: Int? = null,
    val webStrategy: WebDeploymentStrategy? = null,
    val batchMode: BatchDeploymentMode? = null,
    val batchTargets: List<String> = emptyList(),
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
)

data class SubmitDeploymentRunRequest(
    val configurationId: String,
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
)

data class DeploymentExecutionResult(
    val mode: String,
    val resources: List<DeploymentResourcePlan>,
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
