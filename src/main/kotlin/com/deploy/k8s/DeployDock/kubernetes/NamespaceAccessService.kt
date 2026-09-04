package com.deploy.k8s.DeployDock.kubernetes

import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import io.fabric8.kubernetes.api.model.NamespaceBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

data class NamespaceSummary(
    val name: String,
    val phase: String?,
)

data class CreateNamespaceRequest(
    @field:jakarta.validation.constraints.Pattern(
        regexp = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?",
        message = "must be a lowercase Kubernetes-compatible name",
    )
    @field:jakarta.validation.constraints.Size(max = 63)
    val name: String,
)

@Service
class KubernetesNamespaceAccessService(
    private val client: KubernetesClient,
    private val properties: DeployDockKubernetesProperties,
) : NamespaceAccessProvider {
    override fun findAccessible(principal: String): Mono<List<NamespaceSummary>> =
        Mono.fromCallable {
            client.namespaces().list().items
                .asSequence()
                .filter { namespace -> isAllowed(principal, namespace.metadata.name) }
                .map { NamespaceSummary(it.metadata.name, it.status?.phase) }
                .sortedBy { it.name }
                .toList()
        }.subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(KubernetesClientException::class.java) {
                NamespaceAccessException("failed to evaluate Kubernetes namespace access", it)
            }

    override fun create(principal: String, name: String): Mono<NamespaceSummary> =
        Mono.fromCallable {
            if (!canCreateNamespace(principal, name)) {
                throw NamespaceCreationForbiddenException()
            }

            val namespace = try {
                client.namespaces().resource(
                    NamespaceBuilder()
                        .withNewMetadata()
                            .withName(name)
                            .addToLabels("app.kubernetes.io/managed-by", "deploydock")
                        .endMetadata()
                        .build(),
                ).create()
            } catch (exception: KubernetesClientException) {
                if (exception.code == 409) throw NamespaceAlreadyExistsException(name)
                throw exception
            }
            NamespaceSummary(namespace.metadata.name, namespace.status?.phase)
        }.subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(KubernetesClientException::class.java) {
                NamespaceAccessException("failed to create Kubernetes namespace", it)
            }

    private fun isAllowed(principal: String, namespace: String): Boolean {
        val review = SubjectAccessReviewBuilder()
            .withNewSpec()
                .withUser(principal)
                .withNewResourceAttributes()
                    .withGroup(properties.namespaceAccessGroup)
                    .withResource(properties.namespaceAccessResource)
                    .withVerb(properties.namespaceAccessVerb)
                    .withNamespace(namespace)
                .endResourceAttributes()
            .endSpec()
            .build()
        val result = client.authorization().v1().subjectAccessReview().create(review)
        return result.status?.allowed == true
    }

    private fun canCreateNamespace(principal: String, name: String): Boolean {
        val review = SubjectAccessReviewBuilder()
            .withNewSpec()
                .withUser(principal)
                .withNewResourceAttributes()
                    .withGroup("")
                    .withResource("namespaces")
                    .withVerb("create")
                    .withName(name)
                .endResourceAttributes()
            .endSpec()
            .build()
        return client.authorization().v1().subjectAccessReview().create(review).status?.allowed == true
    }
}

interface NamespaceAccessProvider {
    fun findAccessible(principal: String): Mono<List<NamespaceSummary>>
    fun create(principal: String, name: String): Mono<NamespaceSummary>
}

class NamespaceAccessException(message: String, cause: Throwable) : RuntimeException(message, cause)

class NamespaceCreationForbiddenException : RuntimeException("namespace creation is not allowed")

class NamespaceAlreadyExistsException(name: String) : RuntimeException("namespace '$name' already exists")
