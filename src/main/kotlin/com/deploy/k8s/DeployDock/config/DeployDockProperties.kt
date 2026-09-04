package com.deploy.k8s.DeployDock.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("deploydock.security")
data class SecurityProperties(
    val issuer: String = "deploydock",
    val accessTokenTtl: Duration = Duration.ofHours(1),
    val jwtSecret: String,
)

@ConfigurationProperties("deploydock.kubernetes")
data class DeployDockKubernetesProperties(
    val controlNamespace: String = "deploydock-system",
    val namespaceAccessGroup: String = "",
    val namespaceAccessResource: String = "pods",
    val namespaceAccessVerb: String = "list",
)
