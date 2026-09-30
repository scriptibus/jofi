// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A request cancelled while its connection is still being set up leaves no socket behind (issue
 * #137): HttpClient closes the connection and returns it to the pool, and then used to connect the
 * socket anyway, which stayed open with nobody to close it.
 */
class CancelWhileConnectingTest {
    @Test
    fun `a request cancelled while its host is resolved never connects`() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            val resolving = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val resolver =
                HostResolver { _ ->
                    resolving.countDown()
                    cancelled.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    listOf(LOOPBACK)
                }
            val allowlist = DestinationAllowlist.of(listOf(Destination.of(HOST, server.localPort)))
            val timeouts = ClientTimeouts(connect = { TIMEOUT }, read = { TIMEOUT })
            GuardedHttpClients.create(DestinationGuard(allowlist, resolver), timeouts, "Jofi/test").use { client ->
                val request = HttpGet("http://$HOST:${server.localPort}/")
                val call = CompletableFuture.runAsync { client.executeOpen(null, request, null).close() }

                resolving.await(WAIT_SECONDS, TimeUnit.SECONDS) shouldBe true
                request.cancel()
                cancelled.countDown()

                shouldThrow<Exception> { call.get(WAIT_SECONDS, TimeUnit.SECONDS) }
                server.shouldHaveNoOpenConnection()
            }
        }
    }

    /**
     * The call has ended, so a connection it made is already waiting in the backlog. It must not
     * exist, or at least be closed; one that stays silent and open is the leak.
     */
    private fun ServerSocket.shouldHaveNoOpenConnection() {
        soTimeout = BACKLOG_CHECK_MILLIS
        val connection =
            try {
                accept()
            } catch (_: SocketTimeoutException) {
                return
            }
        connection.use {
            it.soTimeout = HANG_UP_MILLIS
            val hungUp =
                try {
                    it.getInputStream().read() == -1
                } catch (_: SocketTimeoutException) {
                    false
                } catch (_: IOException) {
                    true
                }
            withClue("the cancelled request left its connection open") { hungUp shouldBe true }
        }
    }

    private companion object {
        const val HOST = "provider.test"
        const val WAIT_SECONDS = 5L
        const val BACKLOG_CHECK_MILLIS = 500
        const val HANG_UP_MILLIS = 2000
        val LOOPBACK: InetAddress = InetAddress.getLoopbackAddress()
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
