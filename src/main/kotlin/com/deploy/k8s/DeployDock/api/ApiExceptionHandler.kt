package com.deploy.k8s.DeployDock.api

import com.deploy.k8s.DeployDock.auth.DuplicateUserException
import com.deploy.k8s.DeployDock.auth.InvalidCredentialsException
import com.deploy.k8s.DeployDock.auth.UserStoreException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAlreadyExistsException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceCreationForbiddenException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ServerWebInputException

data class ApiError(val code: String, val message: String)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(DuplicateUserException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun duplicateUser(exception: DuplicateUserException) =
        ApiError("USER_ALREADY_EXISTS", exception.message ?: "user already exists")

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun invalidCredentials() =
        ApiError("INVALID_CREDENTIALS", "invalid username or password")

    @ExceptionHandler(NamespaceCreationForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun namespaceCreationForbidden() =
        ApiError("NAMESPACE_CREATION_FORBIDDEN", "namespace creation is not allowed")

    @ExceptionHandler(NamespaceAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun namespaceAlreadyExists(exception: NamespaceAlreadyExistsException) =
        ApiError("NAMESPACE_ALREADY_EXISTS", exception.message ?: "namespace already exists")

    @ExceptionHandler(MethodArgumentNotValidException::class, ServerWebInputException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest() = ApiError("INVALID_REQUEST", "request validation failed")

    @ExceptionHandler(UserStoreException::class, NamespaceAccessException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun kubernetesUnavailable() =
        ApiError("KUBERNETES_UNAVAILABLE", "Kubernetes API is unavailable")
}
