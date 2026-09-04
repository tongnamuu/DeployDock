package com.deploy.k8s.DeployDock.auth

import com.deploy.k8s.DeployDock.config.SecurityProperties
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Instant

@Service
class AuthService(
    private val users: UserAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtEncoder: JwtEncoder,
    private val properties: SecurityProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun signUp(request: SignUpRequest): Mono<SignUpResponse> = blocking {
        val passwordHash = requireNotNull(passwordEncoder.encode(request.password))
        val user = users.create(request.username, passwordHash)
        SignUpResponse(user.username, user.kubernetesPrincipal)
    }

    fun login(request: LoginRequest): Mono<TokenResponse> = blocking {
        val user = users.findByUsername(request.username)
        if (user == null || !passwordEncoder.matches(request.password, user.passwordHash)) {
            throw InvalidCredentialsException()
        }

        val issuedAt = Instant.now(clock)
        val expiresAt = issuedAt.plus(properties.accessTokenTtl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(user.kubernetesPrincipal)
            .claim("username", user.username)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).keyId("deploydock-hs256").build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        TokenResponse(token, expiresIn = properties.accessTokenTtl.seconds)
    }

    private fun <T : Any> blocking(operation: () -> T): Mono<T> =
        Mono.fromCallable(operation).subscribeOn(Schedulers.boundedElastic())
}

class DuplicateUserException(username: String) :
    RuntimeException("user '$username' already exists")

class InvalidCredentialsException : RuntimeException("invalid username or password")

class UserStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
