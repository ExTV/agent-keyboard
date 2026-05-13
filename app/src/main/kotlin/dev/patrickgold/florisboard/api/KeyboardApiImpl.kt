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

import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.lib.devtools.LogTopic
import dev.patrickgold.florisboard.lib.devtools.flogInfo
import dev.patrickgold.florisboard.lib.devtools.flogWarning
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Implementation of the agent-keyboard AIDL surface.
 *
 * Each binder call:
 *   1. Validates the session token (except [connect]).
 *   2. Refuses to operate on password / sensitive fields where applicable.
 *   3. For text-producing calls, charges the rate limiter.
 *   4. Hops to the main thread to talk to InputConnection safely.
 */
class KeyboardApiImpl(
    private val sessionManager: SessionManager,
    private val rateLimiter: RateLimiter,
) : IKeyboardApi.Stub() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun connect(): String? {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val token = sessionManager.newSession(uid, pid)
        flogInfo(LogTopic.IMS_EVENTS) { "Issued session token to uid=$uid pid=$pid" }
        rateLimiter.reset()
        return token
    }

    override fun getApiVersion(): Int = API_VERSION

    override fun typeText(token: String?, text: String?): Boolean {
        if (!sessionManager.isValid(token)) return false
        val payload = text ?: return false
        if (payload.isEmpty()) return true
        if (!rateLimiter.tryConsume(payload.length)) {
            flogWarning(LogTopic.IMS_EVENTS) { "typeText rejected: rate limit" }
            return false
        }
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain false
            if (info.isSensitive()) {
                flogWarning(LogTopic.IMS_EVENTS) { "typeText rejected: sensitive field" }
                return@runOnMain false
            }
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            ic.commitText(payload, 1)
            true
        }
    }

    override fun pressKey(token: String?, keyCode: Int): Boolean {
        if (!sessionManager.isValid(token)) return false
        if (!rateLimiter.tryConsume(1)) return false
        return runOnMain {
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            val a = ic.sendKeyEvent(down)
            val b = ic.sendKeyEvent(up)
            a && b
        }
    }

    override fun deleteChars(token: String?, count: Int): Boolean {
        if (!sessionManager.isValid(token)) return false
        if (count <= 0) return true
        if (!rateLimiter.tryConsume(count)) return false
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain false
            if (info.isSensitive()) return@runOnMain false
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            ic.deleteSurroundingText(count, 0)
        }
    }

    override fun clearField(token: String?): Boolean {
        if (!sessionManager.isValid(token)) return false
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain false
            if (info.isSensitive()) return@runOnMain false
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            val before = ic.getTextBeforeCursor(MAX_FIELD_READ, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(MAX_FIELD_READ, 0)?.length ?: 0
            if (before + after == 0) return@runOnMain true
            if (!rateLimiter.tryConsume(before + after)) return@runOnMain false
            ic.beginBatchEdit()
            try {
                ic.deleteSurroundingText(before, after)
            } finally {
                ic.endBatchEdit()
            }
            true
        }
    }

    override fun getCurrentText(token: String?): String? {
        if (!sessionManager.isValid(token)) return null
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain null
            if (info.isSensitive()) return@runOnMain null
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain null
            val req = ExtractedTextRequest().apply {
                hintMaxChars = MAX_FIELD_READ
                hintMaxLines = MAX_FIELD_LINES
            }
            ic.getExtractedText(req, 0)?.text?.toString()
        }
    }

    override fun getSelectedText(token: String?): String? {
        if (!sessionManager.isValid(token)) return null
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain null
            if (info.isSensitive()) return@runOnMain null
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain null
            ic.getSelectedText(0)?.toString()
        }
    }

    override fun getEditorInfo(token: String?): EditorInfoBundle? {
        if (!sessionManager.isValid(token)) return null
        return runOnMain {
            val info = FlorisImeService.currentInputEditorInfo() ?: return@runOnMain null
            val ic = FlorisImeService.currentInputConnection()
            val extracted = ic?.getExtractedText(ExtractedTextRequest(), 0)
            EditorInfoBundle(
                packageName = info.packageName,
                fieldHint = info.hintText?.toString(),
                inputType = info.inputType,
                imeOptions = info.imeOptions,
                isPassword = info.isPassword(),
                isMultiLine = info.isMultiLine(),
                selectionStart = extracted?.selectionStart ?: info.initialSelStart,
                selectionEnd = extracted?.selectionEnd ?: info.initialSelEnd,
            )
        }
    }

    override fun setCursorPosition(token: String?, pos: Int): Boolean {
        if (!sessionManager.isValid(token)) return false
        if (pos < 0) return false
        return runOnMain {
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            ic.setSelection(pos, pos)
        }
    }

    override fun selectRange(token: String?, start: Int, end: Int): Boolean {
        if (!sessionManager.isValid(token)) return false
        if (start < 0 || end < 0) return false
        return runOnMain {
            val ic = FlorisImeService.currentInputConnection() ?: return@runOnMain false
            ic.setSelection(start, end)
        }
    }

    /** Runs [block] on the IME main thread and blocks until it returns. */
    private fun <T> runOnMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        val ref = AtomicReference<Any?>(null)
        val errRef = AtomicReference<Throwable?>(null)
        mainHandler.post {
            try {
                @Suppress("UNCHECKED_CAST")
                ref.set(block() as Any?)
            } catch (t: Throwable) {
                errRef.set(t)
            } finally {
                latch.countDown()
            }
        }
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            @Suppress("UNCHECKED_CAST")
            return null as T
        }
        errRef.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return ref.get() as T
    }

    companion object {
        const val API_VERSION = 1
        private const val MAX_FIELD_READ = 1_000_000
        private const val MAX_FIELD_LINES = 100_000

        private fun EditorInfo.isPassword(): Boolean {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        private fun EditorInfo.isMultiLine(): Boolean {
            return (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
        }

        private fun EditorInfo.isSensitive(): Boolean {
            if (isPassword()) return true
            val noPersonalizedLearning =
                (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            // Don't auto-reject just because of no-personalized-learning; password is the
            // only hard block. But we do refuse if the field declares NO_LEARNING *and*
            // has a sensitive class flag set.
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val sensitiveVariation = cls == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) &&
                noPersonalizedLearning
            return sensitiveVariation
        }
    }
}
