package com.deploy.k8s.DeployDock.deployment

import com.deploy.k8s.DeployDock.config.DeployDockKubernetesProperties
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class DeploymentBeans {
    @Bean
    fun deploymentStore(client: KubernetesClient, properties: DeployDockKubernetesProperties): DeploymentStore =
        KubernetesDeploymentStore(client, properties.controlNamespace)

    @Bean
    fun deploymentWorkloads(client: KubernetesClient, adapters: ObjectProvider<CanaryTrafficAdapter>) =
        KubernetesDeploymentWorkloads(client, adapters.orderedStream().toList(), SubjectAccessReviewAuthorization(client))

    @Bean
    fun batchExecutionClient(store: DeploymentStore, client: KubernetesClient, clock: Clock) =
        BatchExecutionClient(store, client, SubjectAccessReviewAuthorization(client), clock)

    @Bean
    fun deploymentReconciler(store: DeploymentStore, workloads: KubernetesDeploymentWorkloads, clock: Clock) =
        DeploymentReconciler(store, workloads, clock)

    @Bean
    @ConditionalOnProperty(prefix = "deploydock.deployment.gateway-api", name = ["enabled"], havingValue = "true")
    fun gatewayTrafficAdapter(client: KubernetesClient): CanaryTrafficAdapter = GatewayApiTrafficAdapter(client)
}
