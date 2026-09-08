package com.deploy.k8s.DeployDock.api

import com.deploy.k8s.DeployDock.auth.DuplicateUserException
import com.deploy.k8s.DeployDock.auth.InvalidCredentialsException
import com.deploy.k8s.DeployDock.auth.UserStoreException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAlreadyExistsException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceCreationForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.DeployDockUserNotFoundException
import com.deploy.k8s.DeployDock.kubernetes.InvalidPermissionRequestException
import com.deploy.k8s.DeployDock.kubernetes.KubernetesNamespaceNotFoundException
import com.deploy.k8s.DeployDock.kubernetes.PermissionManagementException
import com.deploy.k8s.DeployDock.kubernetes.PermissionManagementForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceDefinitionNotFoundException
import com.deploy.k8s.DeployDock.kubernetes.PermissionRegistrationInUseException
import com.deploy.k8s.DeployDock.kubernetes.PermissionResourceConflictException
import com.deploy.k8s.DeployDock.kubernetes.RegisteredCustomResourceNotFoundException
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceCreationForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceAlreadyExistsException
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceAccessException
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

    @ExceptionHandler(PermissionManagementForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun permissionManagementForbidden() =
        ApiError("PERMISSION_MANAGEMENT_FORBIDDEN", "administrator permission is required")

    @ExceptionHandler(InvalidPermissionRequestException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidPermission(exception: InvalidPermissionRequestException) =
        ApiError("INVALID_PERMISSION", exception.message ?: "permission request is invalid")

    @ExceptionHandler(
        DeployDockUserNotFoundException::class,
        KubernetesNamespaceNotFoundException::class,
        CustomResourceDefinitionNotFoundException::class,
        RegisteredCustomResourceNotFoundException::class,
    )
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun permissionTargetNotFound(exception: RuntimeException) =
        ApiError("PERMISSION_TARGET_NOT_FOUND", exception.message ?: "permission target does not exist")

    @ExceptionHandler(PermissionResourceConflictException::class, PermissionRegistrationInUseException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun permissionResourceConflict(exception: RuntimeException) =
        ApiError("PERMISSION_RESOURCE_CONFLICT", exception.message ?: "permission resource conflicts")

    @ExceptionHandler(CustomResourceCreationForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun customResourceCreationForbidden() =
        ApiError("CUSTOM_RESOURCE_CREATION_FORBIDDEN", "custom resource creation is not allowed")

    @ExceptionHandler(CustomResourceAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun customResourceAlreadyExists(exception: CustomResourceAlreadyExistsException) =
        ApiError("CUSTOM_RESOURCE_ALREADY_EXISTS", exception.message ?: "custom resource already exists")

    @ExceptionHandler(MethodArgumentNotValidException::class, ServerWebInputException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest() = ApiError("INVALID_REQUEST", "request validation failed")

    @ExceptionHandler(
        UserStoreException::class,
        NamespaceAccessException::class,
        PermissionManagementException::class,
        CustomResourceAccessException::class,
    )
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun kubernetesUnavailable() =
        ApiError("KUBERNETES_UNAVAILABLE", "Kubernetes API is unavailable")
}
