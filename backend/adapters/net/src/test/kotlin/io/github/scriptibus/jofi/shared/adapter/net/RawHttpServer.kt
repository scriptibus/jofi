// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

/**
 * A loopback server that answers every connection with [respond], for responses WireMock cannot
 * produce: a lying `Content-Length`, an endless body. Counts the bytes it managed to send, so a
 * test can tell whether the client kept draining.
 */
class RawHttpServer(
    private val respond: (OutputStream, AtomicLong) -> Unit,
) : AutoCloseable {
    private val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val bytesSent = AtomicLong()

    init {
        Thread.ofVirtual().start {
            while (!socket.isClosed) {
                val connection =
                    try {
                        socket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                Thread.ofVirtual().start {
                    connection.use {
                        try {
                            it.getInputStream().read(ByteArray(REQUEST_BUFFER))
                            respond(it.getOutputStream(), bytesSent)
                        } catch (_: IOException) {
                            // The client hung up, which is what the tests expect.
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val BACKLOG = 50
        const val REQUEST_BUFFER = 8192
    }
}
