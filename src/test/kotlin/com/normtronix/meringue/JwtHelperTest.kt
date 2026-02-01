package com.normtronix.meringue

import com.auth0.jwt.exceptions.JWTVerificationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class JwtHelperTest {

    private val testSecret = "test-secret-key"

    @Test
    fun createAndDecodeToken() {
        val deviceIds = listOf("device1", "device2")
        val teamCode = "team123"
        val email = "test@example.com"

        val token = JwtHelper.createToken(deviceIds, teamCode, email, testSecret)
        val claims = JwtHelper.decodeToken(token, testSecret)

        assertEquals(deviceIds, claims.deviceIds)
        assertEquals(teamCode, claims.teamCode)
        assertEquals(email, claims.email)
    }

    @Test
    fun decodeTokenWithWrongSecret() {
        val token = JwtHelper.createToken(listOf("device1"), "team", "email@test.com", testSecret)

        assertThrows(JWTVerificationException::class.java) {
            JwtHelper.decodeToken(token, "wrong-secret")
        }
    }

    @Test
    fun decodeInvalidToken() {
        assertThrows(JWTVerificationException::class.java) {
            JwtHelper.decodeToken("invalid-token", testSecret)
        }
    }

    @Test
    fun createTokenWithEmptyDeviceIds() {
        val token = JwtHelper.createToken(emptyList(), "team", "email@test.com", testSecret)
        val claims = JwtHelper.decodeToken(token, testSecret)

        assertTrue(claims.deviceIds.isEmpty())
    }
}
