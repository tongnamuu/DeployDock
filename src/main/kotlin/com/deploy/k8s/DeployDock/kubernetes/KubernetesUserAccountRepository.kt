package com.deploy.k8s.DeployDock.kubernetes

import com.deploy.k8s.DeployDock.auth.DuplicateUserException
import com.deploy.k8s.DeployDock.auth.StoredUser
import com.deploy.k8s.DeployDock.auth.UserAccountRepository
import com.deploy.k8s.DeployDock.auth.UserStoreException
import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.util.Base64

@Repository
class KubernetesUserAccountRepository(
    private val client: KubernetesClient,
    private val properties: DeployDockKubernetesProperties,
) : UserAccountRepository {
    override fun create(username: String, passwordHash: String): StoredUser {
        val namespace = properties.controlNamespace
        val secretName = credentialsSecretName(username)
        val principal = kubernetesPrincipal(username)
        val user = DeployDockUser().apply {
            metadata = ObjectMetaBuilder()
                .withName(username)
                .withNamespace(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .build()
            spec = DeployDockUserSpec(principal, secretName)
        }
        val secret = SecretBuilder()
            .withNewMetadata()
                .withName(secretName)
                .withNamespace(namespace)
                .addToLabels(MANAGED_BY_LABEL, MANAGED_BY_VALUE)
                .addToLabels(USER_LABEL, username)
            .endMetadata()
            .withType("Opaque")
            .addToStringData(PASSWORD_HASH_KEY, passwordHash)
            .build()

        try {
            client.secrets().inNamespace(namespace).resource(secret).create()
        } catch (exception: KubernetesClientException) {
            if (exception.code == 409) throw DuplicateUserException(username)
            throw UserStoreException("failed to create credentials for '$username'", exception)
        }

        try {
            client.resources(DeployDockUser::class.java)
                .inNamespace(namespace)
                .resource(user)
                .create()
        } catch (exception: KubernetesClientException) {
            runCatching { client.secrets().inNamespace(namespace).withName(secretName).delete() }
            if (exception.code == 409) throw DuplicateUserException(username)
            throw UserStoreException("failed to create user '$username'", exception)
        }

        return StoredUser(username, principal, passwordHash)
    }

    override fun findByUsername(username: String): StoredUser? {
        val namespace = properties.controlNamespace
        val user = try {
            client.resources(DeployDockUser::class.java)
                .inNamespace(namespace)
                .withName(username)
                .get()
        } catch (exception: KubernetesClientException) {
            if (exception.code == 404) return null
            throw UserStoreException("failed to load user '$username'", exception)
        } ?: return null

        val secretName = user.spec.credentialsSecretName
        val secret = try {
            client.secrets().inNamespace(namespace).withName(secretName).get()
        } catch (exception: KubernetesClientException) {
            throw UserStoreException("failed to load credentials for '$username'", exception)
        } ?: throw UserStoreException("credentials for '$username' do not exist")
        val encodedHash = secret.data?.get(PASSWORD_HASH_KEY)
            ?: throw UserStoreException("credentials for '$username' are invalid")
        val passwordHash = String(Base64.getDecoder().decode(encodedHash), StandardCharsets.UTF_8)
        return StoredUser(username, user.spec.principal, passwordHash)
    }

    companion object {
        private const val PASSWORD_HASH_KEY = "passwordHash"
        private const val MANAGED_BY_LABEL = "app.kubernetes.io/managed-by"
        private const val MANAGED_BY_VALUE = "deploydock"
        private const val USER_LABEL = "deploydock.io/user"

        fun kubernetesPrincipal(username: String): String = "deploydock:$username"
        fun credentialsSecretName(username: String): String = "deploydock-user-$username-credentials"
    }
}
