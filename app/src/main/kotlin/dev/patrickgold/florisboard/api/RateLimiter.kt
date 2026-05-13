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

/**
 * Token-bucket rate limiter used to cap how fast a remote agent can push
 * characters into the active editor. The default capacity (240) lets short
 * bursts (a few words) through unimpeded while sustaining the configured
 * refill rate (120 chars/sec).
 *
 * Thread-safe; all state mutation happens under a single intrinsic lock.
 */
class RateLimiter(
    private val refillPerSecond: Double = 120.0,
    private val burstCapacity: Double = 240.0,
) {
    private val lock = Any()
    private var tokens: Double = burstCapacity
    private var lastRefillNs: Long = System.nanoTime()

    /** Returns true if [cost] tokens were available and consumed. */
    fun tryConsume(cost: Int): Boolean {
        if (cost <= 0) return true
        synchronized(lock) {
            refill()
            if (tokens >= cost) {
                tokens -= cost
                return true
            }
            return false
        }
    }

    fun reset() {
        synchronized(lock) {
            tokens = burstCapacity
            lastRefillNs = System.nanoTime()
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillNs) / 1_000_000_000.0
        if (elapsedSec > 0) {
            tokens = (tokens + elapsedSec * refillPerSecond).coerceAtMost(burstCapacity)
            lastRefillNs = now
        }
    }
}
