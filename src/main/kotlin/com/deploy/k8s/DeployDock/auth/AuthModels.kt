package com.deploy.k8s.DeployDock.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class SignUpRequest(
    @field:NotBlank
    @field:Size(max = 63)
    @field:Pattern(
        regexp = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?",
        message = "must be a lowercase Kubernetes-compatible name",
    )
    val username: String,
    @field:Size(min = 8, max = 72)
    val password: String,
)

data class LoginRequest(
    @field:NotBlank
    @field:Size(max = 63)
    @field:Pattern(
        regexp = "[a-z0-9](?:[-a-z0-9]*[a-z0-9])?",
        message = "must be a lowercase Kubernetes-compatible name",
    )
    val username: String,
    @field:NotBlank val password: String,
)

data class SignUpResponse(
    val username: String,
    val kubernetesPrincipal: String,
)

data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

data class StoredUser(
    val username: String,
    val kubernetesPrincipal: String,
    val passwordHash: String,
)
