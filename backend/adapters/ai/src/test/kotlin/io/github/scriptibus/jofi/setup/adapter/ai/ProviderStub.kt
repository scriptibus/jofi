// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.setup.adapter.ai

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.scriptibus.jofi.setup.domain.ModelName
import io.github.scriptibus.jofi.setup.domain.ProviderConfig
import io.github.scriptibus.jofi.setup.domain.ProviderId
import io.github.scriptibus.jofi.setup.domain.ProviderKind
import io.github.scriptibus.jofi.setup.domain.ResolvedModel
import io.github.scriptibus.jofi.shared.adapter.net.AnthropicSdkHttpClient
import io.github.scriptibus.jofi.shared.adapter.net.Destination
import io.github.scriptibus.jofi.shared.adapter.net.DestinationAllowlist
import io.github.scriptibus.jofi.shared.adapter.net.GuardedAiTransport
import io.github.scriptibus.jofi.shared.adapter.net.OpenAiSdkHttpClient
import io.github.scriptibus.jofi.shared.application.port.SecretStorePort
import io.github.scriptibus.jofi.shared.domain.secret.SecretId
import io.github.scriptibus.jofi.shared.domain.secret.SecretResult
import io.github.scriptibus.jofi.shared.domain.secret.SecretValue
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * One WireMock server on loopback standing in for every provider API (recorded fixtures under
 * `src/test/resources/fixtures`), reached through the real guarded transport from `adapters/net`
 * with the server's exact destination allowlisted, as the app allowlists a configured endpoint.
 */
class ProviderStub : AutoCloseable {
    val server = WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1")).apply { start() }
    val base = "http://127.0.0.1:${server.port()}"
    val secrets = InMemorySecrets()

    /** The cloud endpoints, moved onto the stub under one path prefix per provider. */
    val endpoints =
        ProviderEndpoints(
            mapOf(
                ProviderKind.ANTHROPIC to URI("$base/anthropic"),
                ProviderKind.OPENAI to URI("$base/openai/v1"),
                ProviderKind.GEMINI to URI("$base/gemini/v1beta/openai"),
                ProviderKind.MISTRAL to URI("$base/mistral/v1"),
            ),
        )

    private val transport =
        GuardedAiTransport.create(
            DestinationAllowlist.of(listOf(Destination.of("127.0.0.1", server.port()))),
            "Jofi/test",
        )

    fun models(
        transport: GuardedAiTransport = this.transport,
        endpoints: ProviderEndpoints = this.endpoints,
    ): ProviderModels =
        ProviderModels({ OpenAiSdkHttpClient(transport) }, { AnthropicSdkHttpClient(transport) }, endpoints)

    fun adapter(transport: GuardedAiTransport = this.transport): SpringAiProviderAdapter =
        SpringAiProviderAdapter(models(transport), secrets)

    /** An adapter whose cloud endpoints all point at a loopback server on [port] (allowlisted). */
    fun adapterOn(port: Int): SpringAiProviderAdapter {
        val onPort = "http://127.0.0.1:$port"
        val endpoints =
            ProviderEndpoints(ProviderKind.entries.filterNot { it.needsBaseUri }.associateWith { URI(onPort) })
        val transport =
            GuardedAiTransport.create(
                DestinationAllowlist.of(listOf(Destination.of("127.0.0.1", port))),
                "Jofi/test",
            )
        return SpringAiProviderAdapter(models(transport, endpoints), secrets)
    }

    fun catalog(): ModelCatalogAdapter = ModelCatalogAdapter(models(), secrets, CLOCK)

    /** A provider of [kind] whose key [KEY] is in the secret store; OpenAI-compatible ones point at the stub. */
    fun provider(kind: ProviderKind): ProviderConfig {
        val keyId = SecretId(UUID.randomUUID())
        secrets.put(keyId, SecretValue(KEY))
        return ProviderConfig(
            id = ProviderId(UUID.randomUUID()),
            displayName = kind.name,
            kind = kind,
            apiKey = keyId,
            baseUri = if (kind.needsBaseUri) URI("$base/local/v1") else null,
        )
    }

    fun target(
        kind: ProviderKind,
        model: String = "test-model",
    ): ResolvedModel = ResolvedModel(provider(kind), ModelName(model))

    /** The path prefix of [kind]'s API on the stub, e.g. `/openai/v1`. */
    fun pathOf(kind: ProviderKind): String =
        if (kind ==
            ProviderKind.OPENAI_COMPATIBLE
        ) {
            "/local/v1"
        } else {
            endpoints.of(provider(kind)).path
        }

    override fun close() {
        transport.close()
        server.stop()
    }

    companion object {
        const val KEY = "fixture-provider-key"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)

        fun fixture(path: String): String =
            requireNotNull(
                ProviderStub::class.java.getResource("/fixtures/$path"),
            ) { "Missing fixture $path" }.readText()

        private val json = JsonMapper.builder().build()

        /** An OpenAI server-sent event stream from a fixture holding the chunks as a JSON array. */
        fun openAiStream(path: String): String = openAiEvents(path, Int.MAX_VALUE) + "data: [DONE]\n\n"

        /** The first [count] chunks of an OpenAI stream, without its end marker. */
        fun openAiEvents(
            path: String,
            count: Int,
        ): String =
            json
                .readTree(fixture(path))
                .toList()
                .take(count)
                .joinToString("") { "data: $it\n\n" }

        /** An Anthropic event stream: each event named by its `type`. */
        fun anthropicStream(path: String): String = anthropicEvents(path, Int.MAX_VALUE)

        /** The first [count] events of an Anthropic stream. */
        fun anthropicEvents(
            path: String,
            count: Int,
        ): String =
            json.readTree(fixture(path)).toList().take(count).joinToString("") {
                "event: ${it["type"].asString()}\ndata: $it\n\n"
            }
    }
}

/** The secret store, in memory (the Tink adapter arrives with #16). */
class InMemorySecrets : SecretStorePort {
    private val stored = mutableMapOf<SecretId, SecretValue>()

    override fun put(
        id: SecretId,
        value: SecretValue,
    ): SecretResult<Unit> {
        stored[id] = value
        return SecretResult.Success(Unit)
    }

    override fun get(id: SecretId): SecretResult<SecretValue> =
        stored[id]?.let { SecretResult.Success(it) } ?: SecretResult.NotFound

    override fun delete(id: SecretId): SecretResult<Unit> =
        if (stored.remove(id) !=
            null
        ) {
            SecretResult.Success(Unit)
        } else {
            SecretResult.NotFound
        }
}
