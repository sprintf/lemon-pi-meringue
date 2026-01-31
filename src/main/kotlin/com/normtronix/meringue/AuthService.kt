package com.normtronix.meringue

import org.springframework.stereotype.Service
import java.util.*

@Service
class AuthService {

    // this is only used by the admin login from Dash. Which may not be needed anymore

    private val tokenToUserMap: MutableMap<String, String> = mutableMapOf()

    private val userToTokenMap: MutableMap<String, String> = mutableMapOf()

    fun createTokenForUser(username: String): String {
        val token = UUID.randomUUID().toString()
        tokenToUserMap[token] = username
        userToTokenMap[username] = token
        return token
    }

    fun isTokenValid(token: String?): Boolean {
        return tokenToUserMap.containsKey(token)
    }

}