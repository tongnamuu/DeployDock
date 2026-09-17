package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@RestController
@RequestMapping("/api/v2/deployment-applications/{applicationId}")
class BatchExecutionController(
    private val namespaces: NamespaceAccessProvider,
    private val store: DeploymentStore,
    private val executions: BatchExecutionClient,
) {
    @GetMapping("/execution-targets")
    fun targets(@AuthenticationPrincipal jwt: Jwt, @PathVariable applicationId: String): Mono<List<BatchExecutionTarget>> =
        authorized(requireNotNull(jwt.subject), applicationId) { executions.targets(requireNotNull(jwt.subject), applicationId) }

    @GetMapping("/executions")
    fun history(@AuthenticationPrincipal jwt: Jwt, @PathVariable applicationId: String): Mono<List<BatchExecution>> =
        authorized(requireNotNull(jwt.subject), applicationId) { executions.executions(requireNotNull(jwt.subject), applicationId) }

    @PostMapping("/executions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submit(@AuthenticationPrincipal jwt: Jwt, @PathVariable applicationId: String, @RequestBody request: SubmitBatchExecutionRequest): Mono<BatchExecution> =
        authorized(requireNotNull(jwt.subject), applicationId) { executions.submit(requireNotNull(jwt.subject), applicationId, request) }

    private fun <T : Any> authorized(principal: String, id: String, operation: () -> T): Mono<T> =
        Mono.fromCallable { store.get(id) }.subscribeOn(Schedulers.boundedElastic()).flatMap { record ->
            namespaces.findAccessible(principal).flatMap { visible ->
                if (visible.none { it.name == record.application.namespace }) Mono.error(DeploymentForbiddenException())
                else Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic())
            }
        }
}

@Component
class BatchExecutionScheduler(private val store: DeploymentStore, private val executions: BatchExecutionClient) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${deploydock.deployment.poll-ms:5000}")
    fun reconcile() {
        try {
            store.list().filter { it.application.kind == ApplicationKind.BATCH }.forEach { record ->
                record.batchExecutions.filter { !it.terminal() }.forEach { execution ->
                    try { executions.reconcile(record.application.id, execution.id) }
                    catch (failure: Exception) { logger.warn("Batch execution {} will be retried: {}", execution.id, failure.message) }
                }
            }
        } catch (failure: Exception) { logger.warn("Batch execution polling unavailable: {}", failure.message) }
    }
}
