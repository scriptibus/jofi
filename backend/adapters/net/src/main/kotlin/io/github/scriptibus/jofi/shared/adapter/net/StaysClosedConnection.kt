// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.net

import org.apache.hc.client5.http.io.ManagedHttpClientConnection
import org.apache.hc.core5.io.CloseMode
import org.apache.hc.core5.util.Identifiable
import java.io.InterruptedIOException
import java.net.Socket
import javax.net.ssl.SSLSocket

/**
 * A pooled connection that stays closed once it was closed. Cancelling a request that is still
 * connecting (resolving the host, say) closes its connection before a socket is bound, and the pool
 * lets go of it. HttpClient 5.6 then binds and connects the new socket anyway: its connection's
 * `bind(Socket)` skips the closed check that `bind(SocketHolder)` has. The request fails, but the
 * socket stays open, owned by nobody, until garbage collection. Here a socket bound after the
 * close is closed at once and the connect fails, as HttpClient's own closed check would do.
 */
internal class StaysClosedConnection(
    private val connection: ManagedHttpClientConnection,
) : ManagedHttpClientConnection by connection,
    Identifiable {
    private val lock = Any()
    private var closed = false

    override fun bind(socket: Socket) = bindUnlessClosed(socket) { connection.bind(socket) }

    override fun bind(
        sslSocket: SSLSocket,
        socket: Socket,
    ) = bindUnlessClosed(sslSocket, socket) { connection.bind(sslSocket, socket) }

    override fun close() = closeFor { connection.close() }

    override fun close(closeMode: CloseMode) = closeFor { connection.close(closeMode) }

    /** Keeps the connection's id (`http-outgoing-n`) in HttpClient's debug logs. */
    override fun getId(): String = (connection as? Identifiable)?.id ?: connection.toString()

    override fun toString(): String = connection.toString()

    /** Bind and close exclude each other, so a close either comes first or closes the bound socket. */
    private fun bindUnlessClosed(
        vararg sockets: Socket,
        bind: () -> Unit,
    ) {
        synchronized(lock) {
            if (!closed) return bind()
        }
        sockets.forEach(Socket::close)
        throw InterruptedIOException("Connection already closed")
    }

    private fun closeFor(close: () -> Unit) =
        synchronized(lock) {
            closed = true
            close()
        }
}
