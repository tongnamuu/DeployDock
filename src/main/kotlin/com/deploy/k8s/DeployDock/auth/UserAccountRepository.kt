package com.deploy.k8s.DeployDock.auth

interface UserAccountRepository {
    fun create(username: String, passwordHash: String): StoredUser
    fun findByUsername(username: String): StoredUser?
}
