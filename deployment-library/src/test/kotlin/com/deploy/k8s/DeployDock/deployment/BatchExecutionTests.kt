package com.deploy.k8s.DeployDock.deployment

import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@EnableKubernetesMockClient(crud = true, https = false)
class BatchExecutionTests {
    lateinit var kubernetes: KubernetesClient
    private fun store() = KubernetesDeploymentStore(kubernetes, "team-a")
    private fun jobs() = kubernetes.batch().v1().jobs().inNamespace("team-a")
    private fun crons() = kubernetes.batch().v1().cronjobs().inNamespace("team-a")
    private fun api() = DeploymentClient(store(), KubernetesDeploymentWorkloads(kubernetes))
    private fun executions() = BatchExecutionClient(store(), kubernetes)

    private fun seed(): DeploymentApplication {
        crons().resource(CronJobBuilder().withNewMetadata().withName("billing").endMetadata()
            .withNewSpec().withSchedule("0 * * * *").withSuspend(true).withNewJobTemplate().withNewSpec().withNewTemplate()
            .withNewSpec().withRestartPolicy("Never").addNewContainer().withName("main").withImage("example/batch:v1").endContainer()
            .endSpec().endTemplate().endSpec().endJobTemplate().endSpec().build()).create()
        val app = api().registerApplication("user", RegisterApplicationRequest("billing", "team-a", ApplicationKind.BATCH))
        api().saveConfiguration("user", app.id, SaveDeploymentConfigurationRequest("example/batch:v2", batchMode = BatchDeploymentMode.INDIVIDUAL))
        return app
    }

    @Test
    fun `manual execution snapshots deployed template not saved settings and survives restart`() {
        val app = seed()
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual-1"))
        assertNull(execution.desiredJob)
        assertEquals(mapOf("main" to "example/batch:v1"), execution.images)
        assertTrue(jobs().list().items.isEmpty())
        val cron = crons().withName("billing").get()
        cron.spec.jobTemplate.spec.template.spec.containers.single().image = "example/batch:v3"
        crons().resource(cron).update()
        assertNotNull(store().get(app.id).batchExecutions.single().desiredJob)
        val restarted = executions()
        assertFalse(restarted.reconcile(app.id, execution.id))
        assertEquals("example/batch:v1", jobs().list().items.single().spec.template.spec.containers.single().image)
        assertEquals("example/batch:v3", crons().withName("billing").get().spec.jobTemplate.spec.template.spec.containers.single().image)
        assertTrue(crons().withName("billing").get().spec.suspend)
        assertTrue(api().runs(app.id).isEmpty())
        assertEquals("example/batch:v2", api().configurations(app.id).single().image)
        assertNull(restarted.executions("user", app.id).single().desiredJob)
    }

    @Test
    fun `deployment updates template without running it or changing execution history`() {
        val app = seed()
        val config = api().configurations(app.id).single()
        val run = api().submitRun("user", app.id, SubmitDeploymentRunRequest(config.id, "deploy"))
        val reconciler = DeploymentReconciler(store(), KubernetesDeploymentWorkloads(kubernetes), Clock.systemUTC())
        repeat(4) { reconciler.reconcile(app.id, run.id) }
        assertEquals(DeploymentRunStatus.SUCCEEDED, api().runs(app.id).single().status)
        assertEquals("example/batch:v2", crons().withName("billing").get().spec.jobTemplate.spec.template.spec.containers.single().image)
        assertTrue(jobs().list().items.isEmpty())
        assertTrue(executions().executions("user", app.id).isEmpty())
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual"))
        executions().reconcile(app.id, execution.id)
        assertEquals("example/batch:v2", jobs().list().items.single().spec.template.spec.containers.single().image)
        assertEquals(1, api().runs(app.id).size)
    }

    @Test
    fun `repeated submission and recovery after job creation produce only one job`() {
        val app = seed()
        val request = SubmitBatchExecutionRequest("billing", "retry")
        val execution = executions().submit("user", app.id, request)
        jobs().resource(JobBuilder(store().get(app.id).batchExecutions.single().desiredJob).build()).create()
        assertEquals(execution.id, executions().submit("user", app.id, request).id)
        repeat(3) { executions().reconcile(app.id, execution.id) }
        assertEquals(1, jobs().list().items.size)
        assertEquals(1, executions().executions("user", app.id).size)
        assertNotNull(executions().executions("user", app.id).single().jobUid)
        assertFailsWith<DeploymentConflictException> {
            executions().submit("user", app.id, request.copy(cronJobName = "different"))
        }
    }

    @Test
    fun `failed pods are retried and completion history survives job deletion`() {
        val app = seed()
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual"))
        executions().reconcile(app.id, execution.id)
        val job = jobs().withName(execution.jobName).get()
        job.status = JobStatusBuilder().withActive(1).withFailed(1).withStartTime("2026-09-17T00:00:00Z").build()
        jobs().resource(job).updateStatus()
        assertFalse(executions().reconcile(app.id, execution.id))
        assertEquals(BatchExecutionStatus.RUNNING, executions().executions("user", app.id).single().status)
        val completed = jobs().withName(execution.jobName).get()
        completed.status = JobStatusBuilder().withSucceeded(1).withCompletionTime("2026-09-17T00:01:00Z")
            .addNewCondition().withType("Complete").withStatus("True").endCondition().build()
        jobs().resource(completed).updateStatus()
        assertTrue(executions().reconcile(app.id, execution.id))
        jobs().withName(execution.jobName).delete()
        assertTrue(executions().reconcile(app.id, execution.id))
        assertEquals(BatchExecutionStatus.SUCCEEDED, executions().executions("user", app.id).single().status)
        assertTrue(jobs().list().items.isEmpty())
    }

    @Test
    fun `missing active job is recorded and never recreated`() {
        val app = seed()
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual"))
        executions().reconcile(app.id, execution.id)
        jobs().withName(execution.jobName).delete()
        assertTrue(executions().reconcile(app.id, execution.id))
        assertEquals(BatchExecutionStatus.MISSING, executions().executions("user", app.id).single().status)
        executions().reconcile(app.id, execution.id)
        assertTrue(jobs().list().items.isEmpty())
    }

    @Test
    fun `failure condition ends execution without affecting deployment history`() {
        val app = seed()
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual"))
        executions().reconcile(app.id, execution.id)
        val job = jobs().withName(execution.jobName).get()
        job.status = JobStatusBuilder().withFailed(3).addNewCondition().withType("Failed").withStatus("True")
            .withMessage("Backoff limit reached").endCondition().build()
        jobs().resource(job).updateStatus()
        assertTrue(executions().reconcile(app.id, execution.id))
        val history = executions().executions("user", app.id).single()
        assertEquals(BatchExecutionStatus.FAILED, history.status)
        assertEquals("Backoff limit reached", history.message)
        assertTrue(api().runs(app.id).isEmpty())
    }

    @Test
    fun `web unrelated targets and foreign ownership are rejected`() {
        val app = seed()
        val web = api().registerApplication("user", RegisterApplicationRequest("web", "team-a", ApplicationKind.WEB))
        assertFailsWith<DeploymentValidationException> { executions().targets("user", web.id) }
        assertFailsWith<DeploymentValidationException> { executions().submit("user", app.id, SubmitBatchExecutionRequest("other", "manual")) }
        val cron = crons().withName("billing").get()
        cron.metadata.annotations = mapOf(KubernetesDeploymentWorkloads.APP_ANNOTATION to "other-app")
        crons().resource(cron).update()
        assertFailsWith<DeploymentConflictException> { executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual")) }
        assertTrue(store().get(app.id).batchExecutions.isEmpty())
        assertTrue(jobs().list().items.isEmpty())
    }

    @Test
    fun `authorization is checked again before creation`() {
        val app = seed()
        val execution = executions().submit("user", app.id, SubmitBatchExecutionRequest("billing", "manual"))
        val denied = BatchExecutionClient(store(), kubernetes, DeploymentAuthorization { _, _, _ -> throw DeploymentForbiddenException() })
        assertFailsWith<DeploymentForbiddenException> { denied.submit("user", app.id, SubmitBatchExecutionRequest("billing", "new")) }
        assertFalse(denied.reconcile(app.id, execution.id))
        assertNotNull(store().get(app.id).batchExecutions.single().message)
        assertTrue(jobs().list().items.isEmpty())
    }
}
