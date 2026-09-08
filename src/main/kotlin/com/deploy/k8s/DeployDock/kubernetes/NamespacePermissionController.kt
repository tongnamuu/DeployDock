package com.deploy.k8s.DeployDock.kubernetes

import com.deploy.k8s.DeployDock.auth.UserIdentity
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/admin")
class NamespacePermissionController(private val permissions: NamespacePermissionProvider) {
    @GetMapping("/permission-catalog")
    fun catalog(@AuthenticationPrincipal jwt: Jwt): Mono<List<PermissionResource>> =
        permissions.catalog(requireNotNull(jwt.subject))

    @GetMapping("/permission-catalog/custom-resources")
    fun customResourceRegistrations(@AuthenticationPrincipal jwt: Jwt): Mono<List<CustomResourceRegistration>> =
        permissions.customResourceRegistrations(requireNotNull(jwt.subject))

    @PutMapping("/permission-catalog/custom-resources/{crdName}")
    fun registerCustomResource(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable crdName: String,
        @RequestBody request: RegisterCustomResourceRequest,
    ): Mono<CustomResourceRegistration> =
        permissions.registerCustomResource(requireNotNull(jwt.subject), crdName, request)

    @DeleteMapping("/permission-catalog/custom-resources/{crdName}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unregisterCustomResource(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable crdName: String,
    ): Mono<Void> = permissions.unregisterCustomResource(requireNotNull(jwt.subject), crdName)

    @GetMapping("/users")
    fun users(@AuthenticationPrincipal jwt: Jwt): Mono<List<UserIdentity>> =
        permissions.users(requireNotNull(jwt.subject))

    @GetMapping("/namespaces/{namespace}/members")
    fun mappings(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable namespace: String,
    ): Mono<List<NamespaceMemberMapping>> =
        permissions.mappings(requireNotNull(jwt.subject), namespace)

    @PutMapping("/namespaces/{namespace}/members/{username}")
    fun replace(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable namespace: String,
        @PathVariable username: String,
        @RequestBody request: ReplaceNamespacePermissionsRequest,
    ): Mono<NamespaceMemberMapping> =
        permissions.replace(requireNotNull(jwt.subject), namespace, username, request)

    @DeleteMapping("/namespaces/{namespace}/members/{username}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable namespace: String,
        @PathVariable username: String,
    ): Mono<Void> = permissions.revoke(requireNotNull(jwt.subject), namespace, username)
}
