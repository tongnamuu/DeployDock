package com.deploy.k8s.DeployDock.kubernetes

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Plural
import io.fabric8.kubernetes.model.annotation.Version

@Group("deploydock.io")
@Version("v1alpha1")
@Kind("DeployDockUser")
@Plural("deploydockusers")
class DeployDockUser : CustomResource<DeployDockUserSpec, Void>(), Namespaced

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeployDockUserSpec(
    var principal: String = "",
    var credentialsSecretName: String = "",
)
