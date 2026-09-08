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
@Kind("DeployDockPermissionPolicy")
@Plural("deploydockpermissionpolicies")
class DeployDockPermissionPolicy : CustomResource<DeployDockPermissionPolicySpec, Void>(), Namespaced

@JsonIgnoreProperties(ignoreUnknown = true)
data class DeployDockPermissionPolicySpec(
    var customResources: MutableList<CustomResourceRegistration> = mutableListOf(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CustomResourceRegistration(
    var crdName: String = "",
    var apiGroup: String = "",
    var resource: String = "",
    var displayName: String = "",
    var allowedVerbs: MutableSet<String> = linkedSetOf(),
) {
    fun toPermissionResource() = PermissionResource(apiGroup, resource, displayName, allowedVerbs)
}
