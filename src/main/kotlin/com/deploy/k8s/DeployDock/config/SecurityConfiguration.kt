package com.deploy.k8s.DeployDock.config

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.OctetSequenceKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.time.Clock

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {
    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeExchange {
                it.pathMatchers("/", "/css/**", "/js/**", "/api/auth/signup", "/api/auth/login").permitAll()
                    .anyExchange().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
            .build()

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun jwtSecretKey(properties: SecurityProperties): SecretKey {
        val bytes = properties.jwtSecret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= 32) { "deploydock.security.jwt-secret must be at least 32 bytes" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }

    @Bean
    fun jwtEncoder(secretKey: SecretKey): JwtEncoder {
        val jwk = OctetSequenceKey.Builder(secretKey).keyID("deploydock-hs256").build()
        return NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(jwk)))
    }

    @Bean
    fun jwtDecoder(secretKey: SecretKey, properties: SecurityProperties): ReactiveJwtDecoder {
        val decoder = NimbusReactiveJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        val validator = DelegatingOAuth2TokenValidator<Jwt>(
            JwtTimestampValidator(),
            JwtIssuerValidator(properties.issuer),
        )
        decoder.setJwtValidator(validator)
        return decoder
    }
}
