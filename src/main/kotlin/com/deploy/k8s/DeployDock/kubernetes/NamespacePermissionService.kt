package com.deploy.k8s.DeployDock.kubernetes

import com.deploy.k8s.DeployDock.auth.UserAccountRepository
import com.deploy.k8s.DeployDock.auth.UserIdentity
import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.authorization.v1.SubjectAccessReviewBuilder
import io.fabric8.kubernetes.api.model.rbac.PolicyRuleBuilder
import io.fabric8.kubernetes.api.model.rbac.Role
import io.fabric8.kubernetes.api.model.rbac.RoleBindingBuilder
import io.fabric8.kubernetes.api.model.rbac.RoleBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

data class PermissionRule(
    val apiGroup: String,
    val resource: String,
    val verbs: Set<String>,
)

data class ReplaceNamespacePermissionsRequest(
    val permissions: List<PermissionRule>,
)

data class PermissionResource(
    val apiGroup: String,
    val resource: String,
    val displayName: String,
    val allowedVerbs: Set<String> = NamespacePermissionCatalog.allowedVerbs,
)

data class RegisterCustomResourceRequest(
    val displayName: String? = null,
    val allowedVerbs: Set<String>,
)

data class NamespaceMemberMapping(
    val namespace: String,
    val username: String,
    val kubernetesPrincipal: String,
    val permissions: List<PermissionRule>,
)

interface NamespacePermissionProvider {
    fun catalog(principal: String): Mono<List<PermissionResource>>
    fun customResourceRegistrations(principal: String): Mono<List<CustomResourceRegistration>>
    fun registerCustomResource(
        principal: String,
        crdName: String,
        request: RegisterCustomResourceRequest,
    ): Mono<CustomResourceRegistration>
    fun unregisterCustomResource(principal: String, crdName: String): Mono<Void>
    fun users(principal: String): Mono<List<UserIdentity>>
    fun mappings(principal: String, namespace: String): Mono<List<NamespaceMemberMapping>>
    fun replace(
        principal: String,
        namespace: String,
        username: String,
        request: ReplaceNamespacePermissionsRequest,
    ): Mono<NamespaceMemberMapping>
    fun revoke(principal: String, namespace: String, username: String): Mono<Void>
}

@Service
class KubernetesNamespacePermissionService(
    private val client: KubernetesClient,
    private val users: UserAccountRepository,
    private val properties: DeployDockKubernetesProperties,
) : NamespacePermissionProvider {
    override fun catalog(principal: String): Mono<List<PermissionResource>> = blocking {
        requireAdmin(principal)
        currentCatalog()
    }

    override fun customResourceRegistrations(principal: String): Mono<List<CustomResourceRegistration>> = blocking {
        requireAdmin(principal)
        loadOrCreatePolicy().spec.customResources.sortedBy { it.crdName }
    }

    override fun registerCustomResource(
        principal: String,
        crdName: String,
        request: RegisterCustomResourceRequest,
    ): Mono<CustomResourceRegistration> = blocking {
        requireAdmin(principal)
        val definition = client.apiextensions().v1().customResourceDefinitions().withName(crdName).get()
            ?: throw CustomResourceDefinitionNotFoundException(crdName)
        if (definition.spec.scope != "Namespaced") {
            throw InvalidPermissionRequestException("CRD '$crdName' must be namespace-scoped")
        }
        if (definition.spec.group == API_GROUP) {
            throw InvalidPermissionRequestException("DeployDock control CRDs cannot be registered")
        }
        val established = definition.status?.conditions.orEmpty()
            .any { it.type == "Established" && it.status == "True" }
        if (!established) throw InvalidPermissionRequestException("CRD '$crdName' is not established")
        if (request.allowedVerbs.isEmpty() || !NamespacePermissionCatalog.allowedVerbs.containsAll(request.allowedVerbs)) {
            throw InvalidPermissionRequestException("registration contains an unsupported verb")
        }
        val displayName = request.displayName?.trim().takeUnless { it.isNullOrEmpty() } ?: definition.spec.names.kind
        if (displayName.length > 80) throw InvalidPermissionRequestException("displayName must be 80 characters or fewer")

        val registration = CustomResourceRegistration(
            crdName = definition.metadata.name,
            apiGroup = definition.spec.group,
            resource = definition.spec.names.plural,
            displayName = displayName,
            allowedVerbs = request.allowedVerbs.toSortedSet(),
        )
        val policy = loadOrCreatePolicy()
        val existing = policy.spec.customResources.firstOrNull { it.crdName == registration.crdName }
        if (existing != null) {
            val incompatibleUsage = findRoleUsage(existing) { rule ->
                existing.apiGroup != registration.apiGroup ||
                    existing.resource != registration.resource ||
                    !registration.allowedVerbs.containsAll(rule.verbs.orEmpty())
            }
            if (incompatibleUsage != null) {
                throw PermissionRegistrationInUseException(
                    crdName,
                    incompatibleUsage.metadata.namespace,
                    incompatibleUsage.metadata.name,
                )
            }
        }
        policy.spec.customResources.removeIf { it.crdName == registration.crdName }
        policy.spec.customResources.add(registration)
        policy.spec.customResources.sortBy { it.crdName }
        updatePolicy(policy)
        registration
    }

    override fun unregisterCustomResource(principal: String, crdName: String): Mono<Void> = blocking {
        requireAdmin(principal)
        val policy = loadOrCreatePolicy()
        val registration = policy.spec.customResources.firstOrNull { it.crdName == crdName }
            ?: return@blocking true
        val usage = findRoleUsage(registration) { true }
        if (usage != null) {
            throw PermissionRegistrationInUseException(
                crdName,
                usage.metadata.namespace,
                usage.metadata.name,
            )
        }
        policy.spec.customResources.removeIf { it.crdName == crdName }
        updatePolicy(policy)
        true
    }.then()

    override fun users(principal: String): Mono<List<UserIdentity>> = blocking {
        requireAdmin(principal)
        users.findAll()
    }

    override fun mappings(principal: String, namespace: String): Mono<List<NamespaceMemberMapping>> = blocking {
        requireAdmin(principal)
        NamespacePermissionCatalog.requireManageableNamespace(namespace)
        requireNamespace(namespace)
        val roles = client.rbac().roles().inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .list().items.associateBy { it.metadata.name }
        client.rbac().roleBindings().inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .list().items
            .mapNotNull { binding ->
                val username = binding.metadata.labels?.get(USER_LABEL) ?: return@mapNotNull null
                val role = roles[binding.roleRef.name] ?: return@mapNotNull null
                toMapping(namespace, username, role)
            }
            .sortedBy { it.username }
    }

    override fun replace(
        principal: String,
        namespace: String,
        username: String,
        request: ReplaceNamespacePermissionsRequest,
    ): Mono<NamespaceMemberMapping> = blocking {
        requireAdmin(principal)
        NamespacePermissionCatalog.requireManageableNamespace(namespace)
        requireNamespace(namespace)
        if (!users.exists(username)) throw DeployDockUserNotFoundException(username)
        val normalized = NamespacePermissionCatalog.validateAndNormalize(request.permissions, currentCatalog())
        val resourceName = permissionResourceName(username)
        requireManagedOrAbsent(namespace, resourceName)

        val role = RoleBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(resourceName)
                    .withNamespace(namespace)
                    .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                    .addToLabels(USER_LABEL, username)
                    .build(),
            )
            .withRules(normalized.map { permission ->
                PolicyRuleBuilder()
                    .withApiGroups(permission.apiGroup)
                    .withResources(permission.resource)
                    .withVerbs(permission.verbs.sorted())
                    .build()
            })
            .build()
        client.rbac().roles().inNamespace(namespace).resource(role).createOrReplace()

        val binding = RoleBindingBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(resourceName)
                    .withNamespace(namespace)
                    .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                    .addToLabels(USER_LABEL, username)
                    .build(),
            )
            .withNewRoleRef("rbac.authorization.k8s.io", "Role", resourceName)
            .addNewSubject("rbac.authorization.k8s.io", "User", kubernetesPrincipal(username), null)
            .build()
        client.rbac().roleBindings().inNamespace(namespace).resource(binding).createOrReplace()
        reconcileCustomResourceProxy(namespace)

        NamespaceMemberMapping(namespace, username, kubernetesPrincipal(username), normalized)
    }

    override fun revoke(principal: String, namespace: String, username: String): Mono<Void> = blocking {
        requireAdmin(principal)
        NamespacePermissionCatalog.requireManageableNamespace(namespace)
        requireNamespace(namespace)
        val resourceName = permissionResourceName(username)
        requireManagedOrAbsent(namespace, resourceName)
        client.rbac().roleBindings().inNamespace(namespace).withName(resourceName).delete()
        client.rbac().roles().inNamespace(namespace).withName(resourceName).delete()
        reconcileCustomResourceProxy(namespace)
        true
    }.then()

    private fun requireAdmin(principal: String) {
        val review = SubjectAccessReviewBuilder()
            .withNewSpec()
                .withUser(principal)
                .withNewResourceAttributes()
                    .withGroup("")
                    .withResource("namespaces")
                    .withVerb("create")
                .endResourceAttributes()
            .endSpec()
            .build()
        if (client.authorization().v1().subjectAccessReview().create(review).status?.allowed != true) {
            throw PermissionManagementForbiddenException()
        }
    }

    private fun requireNamespace(namespace: String) {
        if (client.namespaces().withName(namespace).get() == null) {
            throw KubernetesNamespaceNotFoundException(namespace)
        }
    }

    private fun requireManagedOrAbsent(namespace: String, resourceName: String) {
        val role = client.rbac().roles().inNamespace(namespace).withName(resourceName).get()
        val binding = client.rbac().roleBindings().inNamespace(namespace).withName(resourceName).get()
        if ((role != null && !role.isManaged()) || (binding != null && !binding.isManaged())) {
            throw PermissionResourceConflictException(resourceName)
        }
    }

    private fun Role.isManaged(): Boolean = metadata.labels?.get(MANAGED_BY_LABEL) == MANAGED_BY_VALUE

    private fun reconcileCustomResourceProxy(namespace: String) {
        val registrations = loadOrCreatePolicy().spec.customResources
            .associateBy { it.apiGroup to it.resource }
        val proxyRules = client.rbac().roles().inNamespace(namespace)
            .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
            .list().items
            .asSequence()
            .filter { it.metadata.name != PROXY_RESOURCE_NAME }
            .flatMap { it.rules.orEmpty().asSequence() }
            .filter { rule ->
                rule.verbs.orEmpty().contains("create") &&
                    rule.apiGroups.orEmpty().size == 1 &&
                    rule.resources.orEmpty().size == 1 &&
                    registrations.containsKey(rule.apiGroups.single() to rule.resources.single())
            }
            .map { it.apiGroups.single() to it.resources.single() }
            .distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { (apiGroup, resource) ->
                PolicyRuleBuilder()
                    .withApiGroups(apiGroup)
                    .withResources(resource)
                    .withVerbs("create")
                    .build()
            }
            .toList()

        val roles = client.rbac().roles().inNamespace(namespace)
        val bindings = client.rbac().roleBindings().inNamespace(namespace)
        val existingRole = roles.withName(PROXY_RESOURCE_NAME).get()
        val existingBinding = bindings.withName(PROXY_RESOURCE_NAME).get()
        if ((existingRole != null && !existingRole.isManaged()) ||
            (existingBinding != null && !existingBinding.isManaged())
        ) {
            throw PermissionResourceConflictException(PROXY_RESOURCE_NAME)
        }
        if (proxyRules.isEmpty()) {
            bindings.withName(PROXY_RESOURCE_NAME).delete()
            roles.withName(PROXY_RESOURCE_NAME).delete()
            return
        }

        val labels = mapOf(
            MANAGED_BY_LABEL to MANAGED_BY_VALUE,
            COMPONENT_LABEL to PROXY_COMPONENT,
        )
        val proxyRole = RoleBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(PROXY_RESOURCE_NAME)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .build(),
            )
            .withRules(proxyRules)
            .build()
        roles.resource(proxyRole).createOrReplace()

        val proxyBinding = RoleBindingBuilder()
            .withMetadata(
                ObjectMetaBuilder()
                    .withName(PROXY_RESOURCE_NAME)
                    .withNamespace(namespace)
                    .addToLabels(labels)
                    .build(),
            )
            .withNewRoleRef("rbac.authorization.k8s.io", "Role", PROXY_RESOURCE_NAME)
            .addNewSubject("", "ServiceAccount", SERVICE_ACCOUNT_NAME, properties.controlNamespace)
            .build()
        bindings.resource(proxyBinding).createOrReplace()
    }

    private fun findRoleUsage(
        registration: CustomResourceRegistration,
        matches: (io.fabric8.kubernetes.api.model.rbac.PolicyRule) -> Boolean,
    ): Role? = client.rbac().roles().inAnyNamespace()
        .withLabel(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
        .list().items.firstOrNull { role ->
            role.rules.orEmpty().any { rule ->
                rule.apiGroups.orEmpty().contains(registration.apiGroup) &&
                    rule.resources.orEmpty().contains(registration.resource) &&
                    matches(rule)
            }
        }

    private fun currentCatalog(): List<PermissionResource> =
        (NamespacePermissionCatalog.resources + loadOrCreatePolicy().spec.customResources.map { it.toPermissionResource() })
            .sortedWith(compareBy({ it.apiGroup }, { it.resource }))

    private fun loadOrCreatePolicy(): DeployDockPermissionPolicy {
        val namespace = properties.controlNamespace
        val policies = client.resources(DeployDockPermissionPolicy::class.java).inNamespace(namespace)
        policies.withName(POLICY_NAME).get()?.let { return it }
        val policy = DeployDockPermissionPolicy().apply {
            metadata = ObjectMetaBuilder().withName(POLICY_NAME).withNamespace(namespace).build()
            spec = DeployDockPermissionPolicySpec()
        }
        return try {
            policies.resource(policy).create()
        } catch (exception: KubernetesClientException) {
            if (exception.code == 409) {
                policies.withName(POLICY_NAME).get()
                    ?: throw PermissionManagementException("permission policy was created but cannot be loaded", exception)
            } else {
                throw exception
            }
        }
    }

    private fun updatePolicy(policy: DeployDockPermissionPolicy) {
        client.resources(DeployDockPermissionPolicy::class.java)
            .inNamespace(properties.controlNamespace)
            .resource(policy)
            .update()
    }

    private fun io.fabric8.kubernetes.api.model.rbac.RoleBinding.isManaged(): Boolean =
        metadata.labels?.get(MANAGED_BY_LABEL) == MANAGED_BY_VALUE

    private fun toMapping(namespace: String, username: String, role: Role): NamespaceMemberMapping =
        NamespaceMemberMapping(
            namespace,
            username,
            kubernetesPrincipal(username),
            role.rules.orEmpty().map { rule ->
                PermissionRule(
                    rule.apiGroups.orEmpty().singleOrNull().orEmpty(),
                    rule.resources.orEmpty().singleOrNull().orEmpty(),
                    rule.verbs.orEmpty().toSortedSet(),
                )
            }.filter { it.resource.isNotBlank() },
        )

    private fun <T : Any> blocking(operation: () -> T): Mono<T> =
        Mono.fromCallable(operation)
            .subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(KubernetesClientException::class.java) {
                PermissionManagementException("Kubernetes permission management failed", it)
            }

    companion object {
        private const val MANAGED_BY_LABEL = "deploydock.io/permission-manager"
        private const val MANAGED_BY_VALUE = "true"
        private const val USER_LABEL = "deploydock.io/user"
        private const val COMPONENT_LABEL = "deploydock.io/component"
        private const val PROXY_COMPONENT = "custom-resource-proxy"
        private const val API_GROUP = "deploydock.io"
        private const val POLICY_NAME = "default"
        private const val PROXY_RESOURCE_NAME = "deploydock-custom-resource-proxy"
        private const val SERVICE_ACCOUNT_NAME = "deploydock"

        fun permissionResourceName(username: String): String = "deploydock-member-$username"
        fun kubernetesPrincipal(username: String): String = "deploydock:$username"
    }
}

object NamespacePermissionCatalog {
    private val protectedNamespaces = setOf("default", "deploydock-system", "local-path-storage")
    val allowedVerbs = linkedSetOf("get", "list", "watch", "create", "update", "patch", "delete")
    val resources = listOf(
        PermissionResource("", "pods", "Pods"),
        PermissionResource("", "services", "Services"),
        PermissionResource("", "configmaps", "ConfigMaps"),
        PermissionResource("", "secrets", "Secrets"),
        PermissionResource("", "persistentvolumeclaims", "PersistentVolumeClaims"),
        PermissionResource("apps", "deployments", "Deployments"),
        PermissionResource("apps", "statefulsets", "StatefulSets"),
        PermissionResource("apps", "daemonsets", "DaemonSets"),
        PermissionResource("apps", "replicasets", "ReplicaSets"),
        PermissionResource("batch", "jobs", "Jobs"),
        PermissionResource("batch", "cronjobs", "CronJobs"),
        PermissionResource("networking.k8s.io", "ingresses", "Ingresses"),
        PermissionResource("networking.k8s.io", "networkpolicies", "NetworkPolicies"),
        PermissionResource("autoscaling", "horizontalpodautoscalers", "HorizontalPodAutoscalers"),
    )
    val allowedKeys = resources.map { it.apiGroup to it.resource }.toSet()

    fun isManageableNamespace(namespace: String): Boolean =
        namespace !in protectedNamespaces && !namespace.startsWith("kube-")

    fun requireManageableNamespace(namespace: String) {
        if (!isManageableNamespace(namespace)) {
            throw InvalidPermissionRequestException("namespace '$namespace' is protected")
        }
    }

    fun validateAndNormalize(
        permissions: List<PermissionRule>,
        catalog: List<PermissionResource> = resources,
    ): List<PermissionRule> {
        if (permissions.isEmpty()) throw InvalidPermissionRequestException("at least one permission is required")
        val duplicates = permissions.groupBy { it.apiGroup to it.resource }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) throw InvalidPermissionRequestException("resource permissions must be unique")

        return permissions.map { permission ->
            val key = permission.apiGroup to permission.resource
            val catalogResource = catalog.firstOrNull { it.apiGroup to it.resource == key }
            if (catalogResource == null) {
                throw InvalidPermissionRequestException(
                    "resource '${permission.apiGroup}/${permission.resource}' is protected or unsupported",
                )
            }
            if (permission.verbs.isEmpty() || !catalogResource.allowedVerbs.containsAll(permission.verbs)) {
                throw InvalidPermissionRequestException("permission contains an unsupported verb")
            }
            permission.copy(verbs = permission.verbs.toSortedSet())
        }.sortedWith(compareBy({ it.apiGroup }, { it.resource }))
    }
}

class PermissionManagementForbiddenException : RuntimeException("administrator permission is required")
class InvalidPermissionRequestException(message: String) : RuntimeException(message)
class DeployDockUserNotFoundException(username: String) : RuntimeException("user '$username' does not exist")
class KubernetesNamespaceNotFoundException(namespace: String) : RuntimeException("namespace '$namespace' does not exist")
class PermissionResourceConflictException(name: String) : RuntimeException("resource '$name' is not managed by DeployDock")
class CustomResourceDefinitionNotFoundException(name: String) : RuntimeException("CRD '$name' does not exist")
class PermissionRegistrationInUseException(crdName: String, namespace: String, role: String) :
    RuntimeException("CRD '$crdName' is still used by Role '$namespace/$role'")
class PermissionManagementException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
