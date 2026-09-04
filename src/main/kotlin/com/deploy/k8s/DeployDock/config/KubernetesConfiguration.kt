package com.deploy.k8s.DeployDock.config

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KubernetesConfiguration {
    @Bean(destroyMethod = "close")
    fun kubernetesClient(): KubernetesClient = KubernetesClientBuilder().build()
}
