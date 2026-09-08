package com.deploy.k8s.DeployDock

import com.deploy.k8s.DeployDock.auth.StoredUser
import com.deploy.k8s.DeployDock.auth.UserAccountRepository
import com.deploy.k8s.DeployDock.auth.UserIdentity
import com.deploy.k8s.DeployDock.kubernetes.KubernetesUserAccountRepository
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceRegistration
import com.deploy.k8s.DeployDock.kubernetes.CreatableCustomResource
import com.deploy.k8s.DeployDock.kubernetes.CreateCustomResourceRequest
import com.deploy.k8s.DeployDock.kubernetes.CreatedCustomResource
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceCreationForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.CustomResourceInstanceProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceMemberMapping
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceCreationForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.NamespacePermissionCatalog
import com.deploy.k8s.DeployDock.kubernetes.NamespacePermissionProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceSummary
import com.deploy.k8s.DeployDock.kubernetes.PermissionManagementForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.PermissionResource
import com.deploy.k8s.DeployDock.kubernetes.PermissionRegistrationInUseException
import com.deploy.k8s.DeployDock.kubernetes.PermissionRule
import com.deploy.k8s.DeployDock.kubernetes.ReplaceNamespacePermissionsRequest
import com.deploy.k8s.DeployDock.kubernetes.RegisterCustomResourceRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthenticationFlowTests.TestBeans::class)
class AuthenticationFlowTests(
    @LocalServerPort private val port: Int,
) {
    @Autowired
    private lateinit var users: InMemoryUserAccountRepository

    @Test
    fun `signup login and namespace access flow`() {
        users.clear()
        val client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()

        client.get().uri("/")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { body -> check(body?.contains("DeployDock") == true) }

        client.get().uri("/admin/crds.html")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { body -> check(body?.contains("CRD 등록") == true) }

        client.get().uri("/custom-resources.html")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java)
            .value { body -> check(body?.contains("CR 생성") == true) }

        client.post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"Alice","password":"short"}""")
            .exchange()
            .expectStatus().isBadRequest

        client.post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"alice","password":"correct-horse"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .jsonPath("$.username").isEqualTo("alice")
            .jsonPath("$.kubernetesPrincipal").isEqualTo("deploydock:alice")

        client.post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"alice","password":"another-password"}""")
            .exchange()
            .expectStatus().isEqualTo(409)

        client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"alice","password":"wrong-password"}""")
            .exchange()
            .expectStatus().isUnauthorized

        val accessToken = client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"alice","password":"correct-horse"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.tokenType").isEqualTo("Bearer")
            .returnResult()
            .responseBody
            ?.toString(Charsets.UTF_8)
            ?.let { Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(it)?.groupValues?.get(1) }
            ?: error("login response did not contain an access token")

        client.get().uri("/api/namespaces")
            .exchange()
            .expectStatus().isUnauthorized

        client.get().uri("/api/custom-resources/creatable")
            .exchange()
            .expectStatus().isUnauthorized

        client.get().uri("/api/namespaces")
            .headers { it.setBearerAuth(accessToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json("""[{"name":"team-a","phase":"Active"}]""")

        client.get().uri("/api/custom-resources/creatable")
            .headers { it.setBearerAuth(accessToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].namespace").isEqualTo("team-a")
            .jsonPath("$[0].crdName").isEqualTo("widgets.example.com")

        client.post().uri("/api/custom-resources/team-a/widgets.example.com")
            .headers { it.setBearerAuth(accessToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"sample-widget","spec":{"message":"hello"}}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.namespace").isEqualTo("team-a")
            .jsonPath("$.name").isEqualTo("sample-widget")
            .jsonPath("$.kind").isEqualTo("Widget")

        client.post().uri("/api/namespaces")
            .headers { it.setBearerAuth(accessToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"team-b"}""")
            .exchange()
            .expectStatus().isForbidden

        client.post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"admin","password":"admin-local-password"}""")
            .exchange()
            .expectStatus().isCreated

        val adminToken = client.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"username":"admin","password":"admin-local-password"}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .returnResult()
            .responseBody
            ?.toString(Charsets.UTF_8)
            ?.let { Regex("\\\"accessToken\\\":\\\"([^\\\"]+)\\\"").find(it)?.groupValues?.get(1) }
            ?: error("admin login response did not contain an access token")

        client.post().uri("/api/namespaces")
            .headers { it.setBearerAuth(adminToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"name":"team-b"}""")
            .exchange()
            .expectStatus().isCreated
            .expectBody()
            .json("""{"name":"team-b","phase":"Active"}""")

        client.get().uri("/api/admin/users")
            .headers { it.setBearerAuth(accessToken) }
            .exchange()
            .expectStatus().isForbidden

        client.get().uri("/api/admin/permission-catalog")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].resource").isEqualTo("pods")

        client.put().uri("/api/admin/permission-catalog/custom-resources/widgets.example.com")
            .headers { it.setBearerAuth(adminToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"displayName":"Widgets","allowedVerbs":["get","list","update"]}""")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.crdName").isEqualTo("widgets.example.com")
            .jsonPath("$.resource").isEqualTo("widgets")

        client.get().uri("/api/admin/permission-catalog/custom-resources")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].crdName").isEqualTo("widgets.example.com")

        client.put().uri("/api/admin/namespaces/team-a/members/alice")
            .headers { it.setBearerAuth(adminToken) }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"permissions":[{"apiGroup":"","resource":"pods","verbs":["get","list"]},{"apiGroup":"example.com","resource":"widgets","verbs":["get"]}]}""",
            )
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.username").isEqualTo("alice")
            .jsonPath("$.permissions[0].resource").isEqualTo("pods")

        client.get().uri("/api/admin/namespaces/team-a/members")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$[0].username").isEqualTo("alice")

        client.delete().uri("/api/admin/permission-catalog/custom-resources/widgets.example.com")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isEqualTo(409)

        client.delete().uri("/api/admin/namespaces/team-a/members/alice")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isNoContent

        client.delete().uri("/api/admin/permission-catalog/custom-resources/widgets.example.com")
            .headers { it.setBearerAuth(adminToken) }
            .exchange()
            .expectStatus().isNoContent
    }

    @TestConfiguration(proxyBeanMethods = false)
    class TestBeans {
        @Bean
        @Primary
        fun testUsers() = InMemoryUserAccountRepository()

        @Bean
        @Primary
        fun testNamespaceAccess(): NamespaceAccessProvider = object : NamespaceAccessProvider {
            override fun findAccessible(principal: String): Mono<List<NamespaceSummary>> =
                Mono.just(listOf(NamespaceSummary("team-a", "Active")))

            override fun create(principal: String, name: String): Mono<NamespaceSummary> =
                if (principal == "deploydock:admin") {
                    Mono.just(NamespaceSummary(name, "Active"))
                } else {
                    Mono.error(NamespaceCreationForbiddenException())
                }
        }

        @Bean
        @Primary
        fun testNamespacePermissions(users: InMemoryUserAccountRepository): NamespacePermissionProvider =
            TestNamespacePermissionProvider(users)

        @Bean
        @Primary
        fun testCustomResources(): CustomResourceInstanceProvider = TestCustomResourceInstanceProvider()
    }
}

class TestCustomResourceInstanceProvider : CustomResourceInstanceProvider {
    private val resource = CreatableCustomResource(
        "team-a",
        "widgets.example.com",
        "example.com",
        "example.com/v1",
        "widgets",
        "Widget",
        "Widgets",
    )

    override fun findCreatable(principal: String): Mono<List<CreatableCustomResource>> =
        if (principal == "deploydock:alice") Mono.just(listOf(resource))
        else Mono.just(emptyList())

    override fun create(
        principal: String,
        namespace: String,
        crdName: String,
        request: CreateCustomResourceRequest,
    ): Mono<CreatedCustomResource> =
        if (principal == "deploydock:alice" && namespace == resource.namespace && crdName == resource.crdName) {
            Mono.just(CreatedCustomResource(namespace, request.name, resource.apiVersion, resource.kind, "1"))
        } else {
            Mono.error(CustomResourceCreationForbiddenException())
        }
}

class InMemoryUserAccountRepository : UserAccountRepository {
    private val users = ConcurrentHashMap<String, StoredUser>()

    override fun create(username: String, passwordHash: String): StoredUser {
        val user = StoredUser(
            username,
            KubernetesUserAccountRepository.kubernetesPrincipal(username),
            passwordHash,
        )
        if (users.putIfAbsent(username, user) != null) {
            throw com.deploy.k8s.DeployDock.auth.DuplicateUserException(username)
        }
        return user
    }

    override fun findByUsername(username: String): StoredUser? = users[username]

    override fun exists(username: String): Boolean = users.containsKey(username)

    override fun findAll(): List<UserIdentity> = users.values
        .map { UserIdentity(it.username, it.kubernetesPrincipal) }
        .sortedBy { it.username }

    fun clear() = users.clear()
}

class TestNamespacePermissionProvider(
    private val users: InMemoryUserAccountRepository,
) : NamespacePermissionProvider {
    private val mappings = ConcurrentHashMap<String, NamespaceMemberMapping>()
    private val registrations = ConcurrentHashMap<String, CustomResourceRegistration>()

    override fun catalog(principal: String): Mono<List<PermissionResource>> = adminOnly(principal) {
        NamespacePermissionCatalog.resources + registrations.values.map { it.toPermissionResource() }
    }

    override fun customResourceRegistrations(principal: String): Mono<List<CustomResourceRegistration>> =
        adminOnly(principal) { registrations.values.sortedBy { it.crdName } }

    override fun registerCustomResource(
        principal: String,
        crdName: String,
        request: RegisterCustomResourceRequest,
    ): Mono<CustomResourceRegistration> = adminOnly(principal) {
        val registration = CustomResourceRegistration(
            crdName,
            crdName.substringAfter('.'),
            crdName.substringBefore('.'),
            request.displayName ?: crdName.substringBefore('.'),
            request.allowedVerbs.toMutableSet(),
        )
        registrations[crdName] = registration
        registration
    }

    override fun unregisterCustomResource(principal: String, crdName: String): Mono<Void> =
        adminOnly(principal) {
            val registration = registrations[crdName] ?: return@adminOnly true
            val inUse = mappings.values.any { mapping ->
                mapping.permissions.any {
                    it.apiGroup == registration.apiGroup && it.resource == registration.resource
                }
            }
            if (inUse) throw PermissionRegistrationInUseException(crdName, "team-a", "deploydock-member-alice")
            registrations.remove(crdName)
            true
        }.then()

    override fun users(principal: String): Mono<List<UserIdentity>> = adminOnly(principal) { users.findAll() }

    override fun mappings(principal: String, namespace: String): Mono<List<NamespaceMemberMapping>> =
        adminOnly(principal) { mappings.values.filter { it.namespace == namespace } }

    override fun replace(
        principal: String,
        namespace: String,
        username: String,
        request: ReplaceNamespacePermissionsRequest,
    ): Mono<NamespaceMemberMapping> = adminOnly(principal) {
        NamespaceMemberMapping(namespace, username, "deploydock:$username", request.permissions)
            .also { mappings["$namespace/$username"] = it }
    }

    override fun revoke(principal: String, namespace: String, username: String): Mono<Void> =
        adminOnly(principal) { mappings.remove("$namespace/$username"); true }.then()

    private fun <T : Any> adminOnly(principal: String, operation: () -> T): Mono<T> =
        if (principal == "deploydock:admin") Mono.fromCallable(operation)
        else Mono.error(PermissionManagementForbiddenException())
}
