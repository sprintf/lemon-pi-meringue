package com.normtronix.meringue

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.util.Date

data class JwtClaims(
    val deviceIds: List<String>,
    val teamCode: String,
    val email: String,
)

object JwtHelper {

    const val CLAIM_DEVICE_IDS = "deviceIds"
    const val CLAIM_TEAM_CODE = "teamCode"
    const val CLAIM_EMAIL = "email"

    private const val TOKEN_VALIDITY_DAYS = 90L

    fun createToken(
        deviceIds: List<String>,
        teamCode: String,
        email: String,
        jwtSecret: String
    ): String {
        return JWT.create()
            .withClaim(CLAIM_DEVICE_IDS, deviceIds)
            .withClaim(CLAIM_TEAM_CODE, teamCode)
            .withClaim(CLAIM_EMAIL, email)
            .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_VALIDITY_DAYS * 24 * 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }

    fun decodeToken(token: String, jwtSecret: String): JwtClaims {
        val verifier = JWT.require(Algorithm.HMAC256(jwtSecret)).build()
        val decoded = verifier.verify(token)
        return JwtClaims(
            deviceIds = decoded.getClaim(CLAIM_DEVICE_IDS).asList(String::class.java),
            teamCode = decoded.getClaim(CLAIM_TEAM_CODE).asString(),
            email = decoded.getClaim(CLAIM_EMAIL).asString(),
        )
    }
}
