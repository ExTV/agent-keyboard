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

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogInfo

/**
 * Bound service that exposes the [IKeyboardApi] AIDL surface to agent apps
 * signed with the same key as the keyboard (enforced via signature-level
 * permission declared in AndroidManifest.xml).
 *
 * The service is intentionally separate from [dev.patrickgold.florisboard.FlorisImeService]:
 * the IME service is bound by the system on its own permission
 * (BIND_INPUT_METHOD); mixing the two would require holding both permissions.
 * Keeping them separate lets the OS manage the IME lifecycle normally while
 * agents bind to this service on demand. The API still reaches into the live
 * IME via [dev.patrickgold.florisboard.FlorisImeService] companion helpers.
 */
class KeyboardApiService : Service() {

    private val sessionManager = SessionManager()
    private val rateLimiter = RateLimiter()
    private val impl by lazy { KeyboardApiImpl(sessionManager, rateLimiter) }

    override fun onBind(intent: Intent?): IBinder {
        flogInfo(LogTopic.IMS_EVENTS) { "Agent bound to KeyboardApiService" }
        return impl
    }

    override fun onUnbind(intent: Intent?): Boolean {
        flogInfo(LogTopic.IMS_EVENTS) { "Agent unbound from KeyboardApiService" }
        sessionManager.revokeAll()
        rateLimiter.reset()
        return false
    }

    override fun onDestroy() {
        sessionManager.revokeAll()
        super.onDestroy()
    }

    companion object {
        const val ACTION_BIND = "dev.patrickgold.florisboard.api.action.BIND"
        const val PERMISSION = "dev.patrickgold.florisboard.permission.AGENT_KEYBOARD_API"
    }
}
