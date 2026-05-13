/*
 * Copyright (C) 2026 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.api

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Issues and validates per-bind session tokens for the keyboard API.
 *
 * The signature-level permission already gates which apps can bind to the
 * service. The token adds a second factor that ties each call to a specific
 * handshake — making confused-deputy attacks where an unrelated component
 * obtains a binder reference and replays calls considerably harder.
 */
class SessionManager {
    private val sessions = ConcurrentHashMap<String, SessionState>()
    private val random = SecureRandom()
    private val counter = AtomicLong(0L)

    fun newSession(callerUid: Int, callerPid: Int): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val token = bytes.toHex() + "-" + counter.incrementAndGet().toString(16)
        sessions[token] = SessionState(callerUid, callerPid, System.currentTimeMillis())
        return token
    }

    fun isValid(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return sessions.containsKey(token)
    }

    fun revoke(token: String?) {
        if (token != null) sessions.remove(token)
    }

    fun revokeAll() {
        sessions.clear()
    }

    private data class SessionState(
        val callerUid: Int,
        val callerPid: Int,
        val createdAtMs: Long,
    )

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
