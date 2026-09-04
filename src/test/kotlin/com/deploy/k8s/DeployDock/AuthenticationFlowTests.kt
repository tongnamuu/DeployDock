package com.deploy.k8s.DeployDock

import com.deploy.k8s.DeployDock.auth.StoredUser
import com.deploy.k8s.DeployDock.auth.UserAccountRepository
import com.deploy.k8s.DeployDock.kubernetes.KubernetesUserAccountRepository
import com.deploy.k8s.DeployDock.kubernetes.NamespaceAccessProvider
import com.deploy.k8s.DeployDock.kubernetes.NamespaceCreationForbiddenException
import com.deploy.k8s.DeployDock.kubernetes.NamespaceSummary
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

        client.get().uri("/api/namespaces")
            .headers { it.setBearerAuth(accessToken) }
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .json("""[{"name":"team-a","phase":"Active"}]""")

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

    fun clear() = users.clear()
}
