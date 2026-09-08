package com.deploy.k8s.DeployDock

import com.deploy.k8s.DeployDock.kubernetes.InvalidPermissionRequestException
import com.deploy.k8s.DeployDock.kubernetes.NamespacePermissionCatalog
import com.deploy.k8s.DeployDock.kubernetes.PermissionRule
import com.deploy.k8s.DeployDock.kubernetes.PermissionResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class NamespacePermissionCatalogTests {
    @Test
    fun `namespace cluster scoped and rbac resources are never grantable`() {
        val keys = NamespacePermissionCatalog.allowedKeys

        assertFalse("" to "namespaces" in keys)
        assertFalse("" to "nodes" in keys)
        assertFalse("rbac.authorization.k8s.io" to "roles" in keys)
        assertFalse("rbac.authorization.k8s.io" to "rolebindings" in keys)
    }

    @Test
    fun `system namespaces are never manageable`() {
        assertFalse(NamespacePermissionCatalog.isManageableNamespace("default"))
        assertFalse(NamespacePermissionCatalog.isManageableNamespace("deploydock-system"))
        assertFalse(NamespacePermissionCatalog.isManageableNamespace("kube-system"))
        assertFalse(NamespacePermissionCatalog.isManageableNamespace("local-path-storage"))
    }

    @Test
    fun `protected resources and unsupported verbs are rejected`() {
        assertFailsWith<InvalidPermissionRequestException> {
            NamespacePermissionCatalog.validateAndNormalize(
                listOf(PermissionRule("", "namespaces", setOf("delete"))),
            )
        }
        assertFailsWith<InvalidPermissionRequestException> {
            NamespacePermissionCatalog.validateAndNormalize(
                listOf(PermissionRule("", "pods", setOf("impersonate"))),
            )
        }
    }

    @Test
    fun `valid permissions are normalized deterministically`() {
        val normalized = NamespacePermissionCatalog.validateAndNormalize(
            listOf(
                PermissionRule("apps", "deployments", setOf("update", "get")),
                PermissionRule("", "pods", setOf("list", "get")),
            ),
        )

        assertEquals(listOf("pods", "deployments"), normalized.map { it.resource })
        assertEquals(listOf("get", "list"), normalized.first().verbs.toList())
    }

    @Test
    fun `registered custom resources use their own verb allowlist`() {
        val catalog = NamespacePermissionCatalog.resources +
            PermissionResource("apps.example.com", "widgets", "Widgets", setOf("get", "list"))

        val normalized = NamespacePermissionCatalog.validateAndNormalize(
            listOf(PermissionRule("apps.example.com", "widgets", setOf("list", "get"))),
            catalog,
        )
        assertEquals(setOf("get", "list"), normalized.single().verbs)

        assertFailsWith<InvalidPermissionRequestException> {
            NamespacePermissionCatalog.validateAndNormalize(
                listOf(PermissionRule("apps.example.com", "widgets", setOf("delete"))),
                catalog,
            )
        }
    }
}
