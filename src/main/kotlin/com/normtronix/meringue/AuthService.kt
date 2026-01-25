package com.normtronix.meringue

import org.springframework.stereotype.Service
import java.util.*

@Service
class AuthService {

    // todo : this has to move into firestore or this will not survive server restarts and horizontol scale
    // might it be ok to cache for 1 minute ... yes sure

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