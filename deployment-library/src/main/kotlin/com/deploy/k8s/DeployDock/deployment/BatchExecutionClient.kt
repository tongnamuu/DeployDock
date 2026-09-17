package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class BatchExecutionStatus { QUEUED, PENDING, RUNNING, SUCCEEDED, FAILED, MISSING }

data class SubmitBatchExecutionRequest(val cronJobName: String, val requestId: String)

data class BatchExecutionTarget(val name: String, val images: Map<String, String>)

data class BatchExecution(
    val id: String,
    val applicationId: String,
    val cronJobName: String,
    val requestId: String,
    val requestedBy: String,
    val requestedAt: Instant,
    val jobName: String,
    val images: Map<String, String>,
    val status: BatchExecutionStatus = BatchExecutionStatus.QUEUED,
    val jobUid: String? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val active: Int = 0,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
    val desiredJob: Job? = null,
) {
    fun terminal() = status in setOf(BatchExecutionStatus.SUCCEEDED, BatchExecutionStatus.FAILED, BatchExecutionStatus.MISSING)
}

class BatchExecutionClient(
    private val store: DeploymentStore,
    client: KubernetesClient,
    private val authorization: DeploymentAuthorization = ClientCredentialsAuthorization,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val client = deploymentClient(client)

    fun targets(principal: String, applicationId: String): List<BatchExecutionTarget> {
        val record = store.get(applicationId)
        requireBatch(record)
        authorization.authorize(principal, record.application.namespace, listOf(ResourcePermission("batch", "cronjobs", listOf("get"))))
        return targetNames(record).mapNotNull { name ->
            cronJobs(record).withName(name).get()?.let { cron ->
                checkOwner(cron.metadata.annotations.orEmpty(), applicationId)
                BatchExecutionTarget(name, cron.spec.jobTemplate.spec.template.spec.containers.associate { it.name to it.image })
            }
        }
    }

    fun executions(principal: String, applicationId: String): List<BatchExecution> {
        val record = store.get(applicationId)
        requireBatch(record)
        authorization.authorize(principal, record.application.namespace, listOf(ResourcePermission("batch", "jobs", listOf("get"))))
        return record.batchExecutions.map { it.copy(desiredJob = null) }
    }

    fun submit(principal: String, applicationId: String, request: SubmitBatchExecutionRequest): BatchExecution {
        if (request.requestId.isBlank() || request.requestId.length > 128) throw DeploymentValidationException("requestId must contain 1 to 128 characters")
        val updated = store.update(applicationId) { record ->
            requireBatch(record)
            authorizeSubmission(principal, record)
            record.batchExecutions.find { it.requestId == request.requestId }?.let {
                if (it.cronJobName != request.cronJobName) throw DeploymentConflictException("requestId already belongs to another batch target")
                return@update record
            }
            if (request.cronJobName !in targetNames(record)) throw DeploymentValidationException("CronJob is not a target of this batch application")
            val cron = cronJobs(record).withName(request.cronJobName).get()
                ?: throw DeploymentValidationException("CronJob does not exist")
            checkOwner(cron.metadata.annotations.orEmpty(), applicationId)
            val spec = cron.spec.jobTemplate.spec
            if (spec.manualSelector == true || spec.selector != null || spec.suspend == true) {
                throw DeploymentValidationException("manual execution requires an unsuspended Job template without an explicit selector")
            }
            val id = "job-" + UUID.nameUUIDFromBytes("$applicationId/${request.requestId}".toByteArray())
            val job = JobBuilder().withNewMetadata().withName("dd-$id").withNamespace(record.application.namespace)
                .addToLabels(cron.spec.jobTemplate.metadata?.labels.orEmpty() + (EXECUTION_LABEL to id))
                .addToAnnotations(cron.spec.jobTemplate.metadata?.annotations.orEmpty() + mapOf(
                    KubernetesDeploymentWorkloads.APP_ANNOTATION to applicationId, REQUEST_ANNOTATION to request.requestId,
                )).endMetadata().withSpec(spec).build()
            val execution = BatchExecution(id, applicationId, request.cronJobName, request.requestId, principal, clock.instant(),
                job.metadata.name, spec.template.spec.containers.associate { it.name to it.image }, desiredJob = job)
            record.copy(batchExecutions = record.batchExecutions + execution)
        }
        return updated.batchExecutions.first { it.requestId == request.requestId }.copy(desiredJob = null)
    }

    fun reconcile(applicationId: String, executionId: String): Boolean {
        val updated = store.update(applicationId) { record ->
            requireBatch(record)
            val execution = record.batchExecutions.first { it.id == executionId }
            if (execution.terminal()) return@update record
            val next = try {
                if (execution.jobUid == null) authorizeSubmission(execution.requestedBy, record)
                else authorization.authorize(execution.requestedBy, record.application.namespace, listOf(ResourcePermission("batch", "jobs", listOf("get"))))
                val operation = client.batch().v1().jobs().inNamespace(record.application.namespace)
                val existing = operation.withName(execution.jobName).get()
                when {
                    existing == null && execution.jobUid != null -> execution.copy(status = BatchExecutionStatus.MISSING, message = "Job disappeared before completion was observed")
                    else -> {
                        val job = existing ?: operation.resource(JobBuilder(requireNotNull(execution.desiredJob)).build()).create()
                        if (job.metadata.labels?.get(EXECUTION_LABEL) != execution.id ||
                            job.metadata.annotations?.get(KubernetesDeploymentWorkloads.APP_ANNOTATION) != applicationId ||
                            job.metadata.annotations?.get(REQUEST_ANNOTATION) != execution.requestId ||
                            (execution.jobUid != null && job.metadata.uid != execution.jobUid)) {
                            throw DeploymentConflictException("Job identity changed outside this execution")
                        }
                        val completed = job.status?.conditions.orEmpty().firstOrNull { it.status == "True" && it.type in setOf("Failed", "Complete") }
                        execution.copy(jobUid = requireNotNull(job.metadata.uid) { "Job UID is missing" }, desiredJob = null,
                            status = when {
                                completed?.type == "Failed" -> BatchExecutionStatus.FAILED
                                completed?.type == "Complete" -> BatchExecutionStatus.SUCCEEDED
                                (job.status?.active ?: 0) > 0 -> BatchExecutionStatus.RUNNING
                                else -> BatchExecutionStatus.PENDING
                            }, startedAt = job.status?.startTime, completedAt = job.status?.completionTime,
                            active = job.status?.active ?: 0, succeeded = job.status?.succeeded ?: 0, failed = job.status?.failed ?: 0,
                            message = completed?.message)
                    }
                }
            } catch (failure: Exception) {
                execution.copy(message = failure.message ?: "Job status update failed; retry pending")
            }
            record.copy(batchExecutions = record.batchExecutions.map { if (it.id == executionId) next else it })
        }
        return updated.batchExecutions.first { it.id == executionId }.terminal()
    }

    private fun authorizeSubmission(principal: String, record: DeploymentRecord) = authorization.authorize(principal, record.application.namespace,
        listOf(ResourcePermission("batch", "cronjobs", listOf("get")), ResourcePermission("batch", "jobs", listOf("get", "create"))))

    private fun targetNames(record: DeploymentRecord): Set<String> =
        (record.configurations + record.runs.map { record.configurationFor(it) }).flatMap { it.batchTargets.ifEmpty { listOf(record.application.name) } }
            .ifEmpty { listOf(record.application.name) }.toSet()

    private fun cronJobs(record: DeploymentRecord) = client.batch().v1().cronjobs().inNamespace(record.application.namespace)

    private fun requireBatch(record: DeploymentRecord) {
        if (record.application.kind != ApplicationKind.BATCH) throw DeploymentValidationException("manual Job execution requires a batch application")
    }

    private fun checkOwner(annotations: Map<String, String>, applicationId: String) {
        val owner = annotations[KubernetesDeploymentWorkloads.APP_ANNOTATION]
        if (owner != null && owner != applicationId) throw DeploymentConflictException("CronJob belongs to another application")
    }

    companion object {
        const val EXECUTION_LABEL = "deploydock.io/batch-execution"
        const val REQUEST_ANNOTATION = "deploydock.io/batch-request"
    }
}
