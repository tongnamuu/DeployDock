package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockTemporalProperties
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v2/deployment-applications")
class DeploymentController(
    private val deployments: DeploymentProvider,
    private val workloads: KubernetesDeploymentWorkloads,
    private val temporal: DeployDockTemporalProperties,
) {
    @GetMapping("/capabilities")
    fun capabilities(): DeploymentConsoleCapabilities = DeploymentConsoleCapabilities(
        workloads.capabilities(),
        if (temporal.enabled) DeploymentOrchestrator.entries.toSet() else setOf(DeploymentOrchestrator.LOCAL),
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun registerApplication(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: RegisterApplicationRequest,
    ): Mono<DeploymentApplication> =
        deployments.registerApplication(requireNotNull(jwt.subject), request)

    @GetMapping
    fun applications(@AuthenticationPrincipal jwt: Jwt): Mono<List<DeploymentApplication>> =
        deployments.applications(requireNotNull(jwt.subject))

    @PostMapping("/{applicationId}/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    fun saveConfiguration(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable applicationId: String,
        @Valid @RequestBody request: SaveDeploymentConfigurationRequest,
    ): Mono<DeploymentConfiguration> =
        deployments.saveConfiguration(requireNotNull(jwt.subject), applicationId, request)

    @GetMapping("/{applicationId}/configurations")
    fun configurations(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable applicationId: String,
    ): Mono<List<DeploymentConfiguration>> =
        deployments.configurations(requireNotNull(jwt.subject), applicationId)

    @PostMapping("/{applicationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submitRun(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable applicationId: String,
        @RequestBody request: SubmitDeploymentRunRequest,
    ): Mono<DeploymentRun> =
        deployments.submitRun(requireNotNull(jwt.subject), applicationId, request).map { it.copy(snapshot = null) }

    @GetMapping("/{applicationId}/runs")
    fun runs(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable applicationId: String,
    ): Mono<List<DeploymentRun>> =
        deployments.runs(requireNotNull(jwt.subject), applicationId).map { runs -> runs.map { it.copy(snapshot = null) } }

    @PostMapping("/{applicationId}/runs/{runId}/actions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun action(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable applicationId: String,
        @PathVariable runId: String,
        @RequestBody request: DeploymentActionRequest,
    ): Mono<DeploymentRun> = deployments.action(requireNotNull(jwt.subject), applicationId, runId, request.action, request.requestId).map { it.copy(snapshot = null) }
}

data class DeploymentConsoleCapabilities(
    val deployment: DeploymentCapabilities,
    val orchestrators: Set<DeploymentOrchestrator>,
)
