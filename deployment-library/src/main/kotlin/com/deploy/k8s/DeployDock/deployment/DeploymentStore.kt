package com.deploy.k8s.DeployDock.deployment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.fabric8.kubernetes.api.model.ConfigMapBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import java.time.Instant
import java.util.UUID

fun deploymentMapper(): ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

fun deploymentClient(client: KubernetesClient): KubernetesClient = client.newClient(
    io.fabric8.kubernetes.client.RequestConfigBuilder(client.configuration.requestConfig)
        .withRequestTimeout(5000).withRequestRetryBackoffLimit(0).build(),
).adapt(KubernetesClient::class.java)

interface DeploymentStore {
    fun create(record: DeploymentRecord): DeploymentRecord
    fun get(id: String): DeploymentRecord
    fun list(): List<DeploymentRecord>
    fun update(id: String, operation: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord
}

class KubernetesDeploymentStore(
    client: KubernetesClient,
    private val controlNamespace: String = "deploydock-system",
) : DeploymentStore {
    private val client = deploymentClient(client)
    private val mapper = deploymentMapper()
    private fun maps() = client.configMaps().inNamespace(controlNamespace)
    private fun decode(value: String) = mapper.readValue(value, DeploymentRecord::class.java)

    override fun create(record: DeploymentRecord): DeploymentRecord {
        val value = ConfigMapBuilder().withNewMetadata()
            .withName(record.application.id)
            .addToLabels("deploydock.io/store", "deployment")
            .endMetadata().addToData("record", mapper.writeValueAsString(record)).build()
        maps().resource(value).create()
        return record
    }

    override fun get(id: String): DeploymentRecord = maps().withName(id).get()
        ?.data?.get("record")?.let(::decode) ?: throw DeploymentApplicationNotFoundException(id)

    override fun list(): List<DeploymentRecord> = maps().withLabel("deploydock.io/store", "deployment")
        .list().items.map { decode(it.data.getValue("record")) }

    override fun update(id: String, operation: (DeploymentRecord) -> DeploymentRecord): DeploymentRecord {
        val current = maps().withName(id).get() ?: throw DeploymentApplicationNotFoundException(id)
        val annotations = current.metadata.annotations.orEmpty()
        val expiry = annotations["deploydock.io/lease-until"]?.let(Instant::parse)
        if (expiry != null && expiry.isAfter(Instant.now())) {
            throw DeploymentConflictException("application is being reconciled; retry the request")
        }
        current.metadata.annotations = annotations + mapOf(
            "deploydock.io/lease-owner" to UUID.randomUUID().toString(),
            "deploydock.io/lease-until" to Instant.now().plusSeconds(300).toString(),
        )
        val claimed = maps().resource(current).lockResourceVersion(current.metadata.resourceVersion).update()
        val previous = decode(claimed.data.getValue("record"))
        var updated = previous
        try {
            val proposed = operation(previous)
            if (mapper.writeValueAsBytes(proposed).size > 800_000) throw DeploymentConflictException("deployment history storage limit reached")
            updated = proposed
            return updated
        } finally {
            claimed.data = mapOf("record" to mapper.writeValueAsString(updated))
            claimed.metadata.annotations = claimed.metadata.annotations - setOf("deploydock.io/lease-owner", "deploydock.io/lease-until")
            maps().resource(claimed).lockResourceVersion(claimed.metadata.resourceVersion).update()
        }
    }
}
