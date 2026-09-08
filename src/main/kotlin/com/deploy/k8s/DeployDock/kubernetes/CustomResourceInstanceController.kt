package com.deploy.k8s.DeployDock.kubernetes

import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import io.fabric8.kubernetes.api.model.GenericKubernetesResource
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

data class CreatableCustomResource(
    val namespace: String,
    val crdName: String,
    val apiGroup: String,
    val apiVersion: String,
    val resource: String,
    val kind: String,
    val displayName: String,
)

data class CreateCustomResourceRequest(
    @field:Size(max = 253)
    @field:Pattern(
        regexp = "[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?",
        message = "must be a lowercase Kubernetes-compatible name",
    )
    val name: String,
    val spec: Map<String, Any?> = emptyMap(),
)

data class CreatedCustomResource(
    val namespace: String,
    val name: String,
    val apiVersion: String,
    val kind: String,
    val resourceVersion: String?,
)

@RestController
@RequestMapping("/api/custom-resources")
class CustomResourceInstanceController(private val customResources: CustomResourceInstanceProvider) {
    @GetMapping("/creatable")
    fun creatable(@AuthenticationPrincipal jwt: Jwt): Mono<List<CreatableCustomResource>> =
        customResources.findCreatable(requireNotNull(jwt.subject))

    @PostMapping("/{namespace}/{crdName}")
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable namespace: String,
        @PathVariable crdName: String,
        @Valid @RequestBody request: CreateCustomResourceRequest,
    ): Mono<CreatedCustomResource> =
        customResources.create(requireNotNull(jwt.subject), namespace, crdName, request)
}

interface CustomResourceInstanceProvider {
    fun findCreatable(principal: String): Mono<List<CreatableCustomResource>>
    fun create(
        principal: String,
        namespace: String,
        crdName: String,
        request: CreateCustomResourceRequest,
    ): Mono<CreatedCustomResource>
}

@org.springframework.stereotype.Service
class KubernetesCustomResourceInstanceService(
    private val client: KubernetesClient,
    private val properties: DeployDockKubernetesProperties,
) : CustomResourceInstanceProvider {
    override fun findCreatable(principal: String): Mono<List<CreatableCustomResource>> = blocking {
        val definitions = registrations()
            .filter { "create" in it.allowedVerbs }
            .mapNotNull { registration ->
            resolve(registration)?.let { registration to it }
        }
        client.namespaces().list().items
            .asSequence()
            .map { it.metadata.name }
            .filter(NamespacePermissionCatalog::isManageableNamespace)
            .flatMap { namespace ->
                definitions.asSequence()
                    .filter { (registration) ->
                        canCreate(principal, namespace, registration) &&
                            canCreate(serviceAccountPrincipal(), namespace, registration)
                    }
                    .map { (registration, definition) -> toCreatable(namespace, registration, definition) }
            }
            .sortedWith(compareBy({ it.namespace }, { it.displayName }, { it.crdName }))
            .toList()
    }

    override fun create(
        principal: String,
        namespace: String,
        crdName: String,
        request: CreateCustomResourceRequest,
    ): Mono<CreatedCustomResource> = blocking {
        NamespacePermissionCatalog.requireManageableNamespace(namespace)
        if (client.namespaces().withName(namespace).get() == null) {
            throw KubernetesNamespaceNotFoundException(namespace)
        }
        val registration = registrations().firstOrNull { it.crdName == crdName }
            ?: throw RegisteredCustomResourceNotFoundException(crdName)
        if ("create" !in registration.allowedVerbs || !canCreate(principal, namespace, registration, request.name)) {
            throw CustomResourceCreationForbiddenException()
        }
        if (!canCreate(serviceAccountPrincipal(), namespace, registration, request.name)) {
            throw CustomResourceAccessException(
                "DeployDock service account does not have proxy create permission for '$namespace/$crdName'",
            )
        }
        val definition = resolve(registration) ?: throw CustomResourceDefinitionNotFoundException(crdName)
        val version = servedVersion(definition)
        val apiVersion = "${registration.apiGroup}/${version.name}"
        val resource = GenericKubernetesResource().apply {
            this.apiVersion = apiVersion
            kind = definition.spec.names.kind
            metadata = ObjectMetaBuilder().withName(request.name).withNamespace(namespace).build()
            setAdditionalProperty("spec", request.spec)
        }
        val created = try {
            client.genericKubernetesResources(apiVersion, definition.spec.names.kind)
                .inNamespace(namespace)
                .resource(resource)
                .create()
        } catch (exception: KubernetesClientException) {
            if (exception.code == 409) throw CustomResourceAlreadyExistsException(namespace, request.name)
            throw exception
        }
        CreatedCustomResource(
            namespace,
            created.metadata.name,
            created.apiVersion,
            created.kind,
            created.metadata.resourceVersion,
        )
    }

    private fun registrations(): List<CustomResourceRegistration> =
        client.resources(DeployDockPermissionPolicy::class.java)
            .inNamespace(properties.controlNamespace)
            .withName(POLICY_NAME)
            .get()?.spec?.customResources.orEmpty()

    private fun resolve(registration: CustomResourceRegistration): CustomResourceDefinition? {
        val definition = client.apiextensions().v1().customResourceDefinitions()
            .withName(registration.crdName).get() ?: return null
        val established = definition.status?.conditions.orEmpty()
            .any { it.type == "Established" && it.status == "True" }
        return definition.takeIf {
            established &&
                it.spec.scope == "Namespaced" &&
                it.spec.group == registration.apiGroup &&
                it.spec.names.plural == registration.resource &&
                it.spec.versions.orEmpty().any { version -> version.served == true }
        }
    }

    private fun servedVersion(definition: CustomResourceDefinition) =
        definition.spec.versions.firstOrNull { it.served == true && it.storage == true }
            ?: definition.spec.versions.first { it.served == true }

    private fun toCreatable(
        namespace: String,
        registration: CustomResourceRegistration,
        definition: CustomResourceDefinition,
    ): CreatableCustomResource {
        val version = servedVersion(definition)
        return CreatableCustomResource(
            namespace,
            registration.crdName,
            registration.apiGroup,
            "${registration.apiGroup}/${version.name}",
            registration.resource,
            definition.spec.names.kind,
            registration.displayName,
        )
    }

    private fun canCreate(
        principal: String,
        namespace: String,
        registration: CustomResourceRegistration,
        name: String? = null,
    ): Boolean {
        val review = SubjectAccessReviewBuilder()
            .withNewSpec()
                .withUser(principal)
                .withNewResourceAttributes()
                    .withGroup(registration.apiGroup)
                    .withResource(registration.resource)
                    .withVerb("create")
                    .withNamespace(namespace)
                    .withName(name)
                .endResourceAttributes()
            .endSpec()
            .build()
        return client.authorization().v1().subjectAccessReview().create(review).status?.allowed == true
    }

    private fun serviceAccountPrincipal(): String =
        "system:serviceaccount:${properties.controlNamespace}:$SERVICE_ACCOUNT_NAME"

    private fun <T : Any> blocking(operation: () -> T): Mono<T> =
        Mono.fromCallable(operation)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(KubernetesClientException::class.java) {
                CustomResourceAccessException("Kubernetes custom resource operation failed", it)
            }

    companion object {
        private const val POLICY_NAME = "default"
        private const val SERVICE_ACCOUNT_NAME = "deploydock"
    }
}

class RegisteredCustomResourceNotFoundException(crdName: String) :
    RuntimeException("CRD '$crdName' is not registered")
class CustomResourceCreationForbiddenException : RuntimeException("custom resource creation is not allowed")
class CustomResourceAlreadyExistsException(namespace: String, name: String) :
    RuntimeException("custom resource '$namespace/$name' already exists")
class CustomResourceAccessException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
