package com.deploy.k8s.DeployDock.kubernetes

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/namespaces")
class NamespaceController(private val namespaceAccessService: NamespaceAccessProvider) {
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt): Mono<List<NamespaceSummary>> =
        namespaceAccessService.findAccessible(requireNotNull(jwt.subject))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateNamespaceRequest,
    ): Mono<NamespaceSummary> =
        namespaceAccessService.create(requireNotNull(jwt.subject), request.name)
}
