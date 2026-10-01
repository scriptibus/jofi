// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.conditions.ArchConditions.callMethodWhere
import com.tngtech.archunit.lang.conditions.ArchConditions.never
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.setup.application.port.AiProviderPort
import io.github.scriptibus.jofi.shared.application.port.EmbeddingPort
import io.github.scriptibus.jofi.shared.application.port.LlmPort

/**
 * Adapter rules shared by the production check and the known-bad fixture tests
 * ([AdapterRulesFixtureTest]), so both evaluate exactly the same rule.
 */
object AdapterRules {
    /**
     * The shared kernel's persistence code (ADR-0032): the generated jOOQ code of the whole schema, and the
     * helpers every context's repositories need for its shared columns and errors (`ActorColumns`, the
     * violated constraint's name).
     */
    const val SHARED_PERSISTENCE = "$BASE.shared.adapter.persistence.."

    /** The web conventions every context's controllers share: problem details, confirmations (ADR-0033/0039). */
    const val SHARED_WEB = "$BASE.shared.adapter.web.."

    /** The MCP server and the tool contract every context's tools implement (ADR-0053). */
    const val SHARED_MCP = "$BASE.shared.adapter.mcp.."

    /**
     * Adapters talk to each other only through use cases and ports. Two narrow exemptions: every
     * context's persistence adapter may use the shared persistence code (the jOOQ code generated for the
     * whole schema into one package, and the kernel's column and error helpers for that schema, #82); and
     * every context's web adapter may use the shared web conventions
     * (`Confirmations`, `ValidationProblem`, `ProblemResponses`), which ADR-0039 and ADR-0041 require; and every
     * context's MCP tools implement the shared tool contract and its result types (ADR-0053).
     * Other adapter kinds still may not touch either.
     */
    val adaptersAreIndependent: ArchRule =
        slices()
            .matching("$BASE.(*).adapter.(*)..")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(resideInAPackage("..adapter.persistence.."), resideInAPackage(SHARED_PERSISTENCE))
            .ignoreDependency(resideInAPackage("..adapter.web.."), resideInAPackage(SHARED_WEB))
            .ignoreDependency(resideInAPackage("..adapter.mcp.."), resideInAPackage(SHARED_MCP))
            .because("adapters talk to each other only through use cases and ports")

    /**
     * Only the AI gateway and the provider adapter (`setup.adapter.ai`) may use [AiProviderPort];
     * everything else calls the task-based `LlmPort`/`EmbeddingPort`, so no AI call can bypass
     * routing, the "never send to AI" filter or metering (ADR-0032).
     */
    val onlyTheAiAdapterUsesAiProviderPort: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackage("$BASE.setup.adapter.ai..")
            .and(DescribedPredicate.not(isTheProviderPort()))
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(AiProviderPort::class.java)
            .because("callers use the task-based LlmPort/EmbeddingPort behind the AI gateway")

    /** The AI gateway: routing, capability check, budget, "never send to AI" filter and meter (ADR-0043). */
    const val AI_GATEWAY = "$BASE.setup.adapter.ai.AiGatewayAdapter"

    /**
     * The gateway is the one implementation of `LlmPort` and `EmbeddingPort`: a second one would be a
     * path to a provider around the privacy filter and the meter (ADR-0032, ADR-0043).
     */
    val onlyTheGatewayImplementsTheAiPorts: ArchRule =
        classes()
            .that()
            .implement(LlmPort::class.java)
            .or()
            .implement(EmbeddingPort::class.java)
            .should()
            .haveFullyQualifiedName(AI_GATEWAY)
            .because("every AI call goes through the gateway's filter and meter")

    /**
     * Inside `setup.adapter.ai` too, only the gateway calls [AiProviderPort]; the provider adapter
     * implements it and calls nothing through it.
     */
    val onlyTheGatewayCallsTheProviderPort: ArchRule =
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(AI_GATEWAY)
            .should()
            .callMethodWhere(isAProviderPortMethod())
            .because("a provider call that skips the gateway skips the privacy filter and the meter")

    /** The job store adapter and its wiring: the only places JobRunr types may appear (ADR-0038). */
    const val JOBS_ADAPTER = "$BASE.shared.adapter.jobs"
    private const val SHARED_CONFIG = "$BASE.shared.config"

    /**
     * JobRunr stays behind `JobSchedulerPort`, `JobLogPort` and `JobHandlerPort`: only `shared.adapter.jobs`
     * (and the bean wiring in `shared.config`) may use `org.jobrunr` types, like provider types in adapters/ai.
     */
    val onlyTheJobsAdapterUsesJobRunr: ArchRule =
        noClasses()
            .that()
            .resideOutsideOfPackages("$JOBS_ADAPTER..", "$SHARED_CONFIG..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("org.jobrunr..")
            .because("JobRunr stays behind the job ports (ADR-0038)")

    /**
     * Every job is a `JofiJobRequest` run by `JofiJobRequestHandler`; the job store allowlist rejects
     * anything else. JobRunr's lambda jobs, `@Job`/`@Recurring`/`@AsyncJob` and the `JobScheduler` /
     * `BackgroundJob` APIs would write jobs the allowlist quarantines, so nothing may use them.
     */
    val noJobRunrLambdasOrAnnotations: ArchRule =
        noClasses()
            .should()
            .dependOnClassesThat(isJobRunrLambdaOrAnnotationApi())
            .because("every job is a JofiJobRequest; the job store rejects lambda and annotated jobs (ADR-0038)")

    private val JOBRUNR_BANNED =
        setOf(
            "org.jobrunr.scheduling.JobScheduler",
            "org.jobrunr.scheduling.BackgroundJob",
            "org.jobrunr.scheduling.BackgroundJobRequest",
            "org.jobrunr.jobs.lambdas.JobLambda",
            "org.jobrunr.jobs.lambdas.IocJobLambda",
            "org.jobrunr.jobs.lambdas.JobLambdaFromStream",
            "org.jobrunr.jobs.lambdas.IocJobLambdaFromStream",
            "org.jobrunr.jobs.annotations.Job",
            "org.jobrunr.jobs.annotations.Recurring",
            "org.jobrunr.jobs.annotations.AsyncJob",
        )

    private fun isJobRunrLambdaOrAnnotationApi(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe(
            "JobRunr's lambda, annotation or JobScheduler job APIs",
        ) { it.name in JOBRUNR_BANNED }

    /** The SSRF guard (threat model T1, ADR-0034). */
    const val NET_ADAPTER = "$BASE.shared.adapter.net"

    /** Where the Gradle module `adapters/net` puts its compiled classes (class dirs and jar). */
    private const val NET_MODULE_OUTPUT = "/adapters/net/build/"

    /**
     * Only `adapters/net` may use an HTTP client, a socket or a `URL`, so every outbound request
     * passes the SSRF guard. The exemption needs both the package and the module: a class that
     * merely declares the package in another module is not exempt. The `ClientHttpRequestFactory`
     * interface stays usable; its implementations (which would create unguarded clients) are not.
     *
     * One narrow exemption (ADR-0040): the AI provider adapter (`setup.adapter.ai`, module
     * `adapters/ai`) may use the named vendor SDK types in [AI_ADAPTER_SDK_TYPES] to build SDK
     * clients over the guarded transport that `adapters/net` provides. None of them is a
     * transport, and Spring AI's own SDK client builders stay banned everywhere. ArchUnit only sees
     * our own classes: ADR-0034 lists what it cannot catch (auto-configured clients, libraries that
     * fetch on their own).
     */
    val onlyTheNetAdapterMakesOutboundHttpCalls: ArchRule =
        classes()
            .that(areOutsideTheNetModule())
            .should(notUseHttpClients())
            .andShould(never(callMethodWhere(readsAUrl())))
            .because("adapters/net is the only outbound HTTP client (SSRF guard, threat model T1)")

    /** The AI provider adapter (ADR-0040). */
    const val AI_ADAPTER = "$BASE.setup.adapter.ai"

    /** Where the Gradle module `adapters/ai` puts its compiled classes (class dirs and jar). */
    private const val AI_MODULE_OUTPUT = "/adapters/ai/build/"

    /**
     * The vendor SDK types `setup.adapter.ai` may use (ADR-0040): the clients, built from client
     * options that carry Jofi's guarded transport; the transport interface as a type (only
     * `adapters/net` implements it); the error types mapped to sealed results; and the model
     * listing. Adding a type needs review.
     */
    val AI_ADAPTER_SDK_TYPES: Set<String> =
        listOf("com.openai" to "OpenAI", "com.anthropic" to "Anthropic")
            .flatMap { (sdk, prefix) ->
                listOf(
                    "$sdk.client.${prefix}Client",
                    "$sdk.client.${prefix}ClientImpl",
                    "$sdk.client.${prefix}ClientAsync",
                    "$sdk.client.${prefix}ClientAsyncImpl",
                    "$sdk.core.ClientOptions",
                    "$sdk.core.ClientOptions\$Builder",
                    "$sdk.core.ClientOptions\$Companion",
                    "$sdk.core.LogLevel",
                    "$sdk.core.Sleeper",
                    "$sdk.core.AutoPager",
                    "$sdk.core.http.HttpClient",
                    "$sdk.core.http.Headers",
                    "$sdk.errors.${prefix}ServiceException",
                    "$sdk.errors.${prefix}IoException",
                    "$sdk.services.blocking.ModelService",
                    "$sdk.models.models.ModelListPage",
                )
            }.toSet() + setOf("com.openai.models.models.Model", "com.anthropic.models.models.ModelInfo")

    private val HTTP_CLIENT_PACKAGES =
        arrayOf(
            "java.net.http..",
            "org.apache.hc..",
            "org.apache.http..",
            "okhttp3..",
            "com.squareup.okhttp..",
            "io.ktor.client..",
            "org.eclipse.jetty.client..",
            "reactor.netty.http.client..",
            "io.netty.handler.codec.http..",
            "jakarta.ws.rs.client..",
            "org.asynchttpclient..",
            "io.vertx..http..",
            "feign..",
            "retrofit2..",
            "com.openai..",
            "com.anthropic..",
            "com.google.genai..",
            // Spring AI's own SDK client builders create unguarded OkHttp clients (ADR-0040).
            "org.springframework.ai.openai.setup..",
            "org.springframework.ai.openai.http..",
            "org.springframework.ai.anthropic.http..",
            "org.jsoup..",
            "org.springframework.web.client..",
            "org.springframework.web.reactive.function.client..",
            "org.springframework.http.client.reactive..",
            "org.springframework.boot.http.client..",
            "org.springframework.boot.restclient..",
            "org.springframework.boot.webclient..",
        )

    private val CONNECTION_CLASSES =
        setOf(
            // Use URI outside adapters/net; a URL can open connections (and has a DNS-resolving equals).
            "java.net.URL",
            "java.net.URLClassLoader",
            "java.net.URLConnection",
            "java.net.HttpURLConnection",
            "javax.net.ssl.HttpsURLConnection",
            "java.net.Socket",
            "javax.net.ssl.SSLSocket",
            "java.net.DatagramSocket",
            "java.nio.channels.SocketChannel",
            "java.nio.channels.AsynchronousSocketChannel",
            "java.nio.channels.DatagramChannel",
            "javax.net.SocketFactory",
            "javax.net.ssl.SSLSocketFactory",
            "org.springframework.ai.anthropic.AnthropicSetup",
        )

    private fun areOutsideTheNetModule(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("are outside the adapters/net module") { javaClass ->
            val inPackage = javaClass.packageName == NET_ADAPTER || javaClass.packageName.startsWith("$NET_ADAPTER.")
            val inModule = javaClass.source.map { NET_MODULE_OUTPUT in it.uri.toString() }.orElse(false)
            !(inPackage && inModule)
        }

    private fun notUseHttpClients(): ArchCondition<JavaClass> =
        object : ArchCondition<JavaClass>("not use HTTP clients, sockets or URLs") {
            override fun check(
                item: JavaClass,
                events: ConditionEvents,
            ) {
                item.directDependenciesFromSelf
                    .filter { isHttpClient().test(it.targetClass) && !isAllowedSdkUse(item, it.targetClass) }
                    .forEach { events.add(SimpleConditionEvent.violated(it, it.description)) }
            }
        }

    /** Both the package and the `adapters/ai` module, like the net exemption, and a named type. */
    private fun isAllowedSdkUse(
        origin: JavaClass,
        target: JavaClass,
    ): Boolean {
        val inPackage = origin.packageName == AI_ADAPTER || origin.packageName.startsWith("$AI_ADAPTER.")
        val inModule = origin.source.map { AI_MODULE_OUTPUT in it.uri.toString() }.orElse(false)
        return inPackage && inModule && target.name in AI_ADAPTER_SDK_TYPES
    }

    private fun isHttpClient(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("HTTP clients, sockets or URLs") { javaClass ->
            resideInAnyPackage(*HTTP_CLIENT_PACKAGES).test(javaClass) ||
                javaClass.name in CONNECTION_CLASSES ||
                isSpringRequestFactoryImplementation(javaClass)
        }

    private fun isSpringRequestFactoryImplementation(javaClass: JavaClass): Boolean =
        javaClass.packageName == "org.springframework.http.client" &&
            javaClass.simpleName.endsWith("RequestFactory") &&
            !javaClass.isInterface

    /** Library calls that fetch a `URL` passed along: `ImageIO.read(URL)`, Kotlin's `URL.readText()`. */
    private fun readsAUrl(): DescribedPredicate<JavaMethodCall> =
        DescribedPredicate.describe("a method that reads from a URL") { call ->
            val target = call.target
            val takesUrl = target.rawParameterTypes.any { it.name == "java.net.URL" }
            takesUrl && target.owner.name in setOf("javax.imageio.ImageIO", "kotlin.io.TextStreamsKt")
        }

    /**
     * The vendor SDKs' `fromEnv()` reads keys, base URLs, custom headers and log levels from
     * environment variables and system properties (`OPENAI_BASE_URL`, `ANTHROPIC_CUSTOM_HEADERS`, ...).
     * Jofi's providers come only from the user's configuration, so nothing may call it (ADR-0040).
     */
    val noAiSdkReadsTheEnvironment: ArchRule =
        noClasses()
            .should()
            .callMethodWhere(readsSdkEnvironment())
            .because("AI provider settings come from the user's configuration, never from the environment")

    private fun readsSdkEnvironment(): DescribedPredicate<JavaMethodCall> =
        DescribedPredicate.describe("an AI SDK's fromEnv()") { call ->
            val owner = call.target.owner.name
            call.target.name == "fromEnv" && (owner.startsWith("com.openai.") || owner.startsWith("com.anthropic."))
        }

    private val PROVIDER_PORT_METHODS: Set<Pair<String, List<String>>> =
        AiProviderPort::class.java.methods
            .map { method -> method.name to method.parameterTypes.map { it.name } }
            .toSet()

    private fun isAProviderPortMethod(): DescribedPredicate<JavaMethodCall> =
        DescribedPredicate.describe("a method of AiProviderPort") { call ->
            call.targetOwner.isAssignableTo(AiProviderPort::class.java) &&
                (call.name to call.target.rawParameterTypes.map { it.name }) in PROVIDER_PORT_METHODS
        }

    private fun isTheProviderPort(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("AiProviderPort itself") { it.isEquivalentTo(AiProviderPort::class.java) }
}
