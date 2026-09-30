// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.github.scriptibus.jofi.architecture.JofiPackages.BASE
import io.github.scriptibus.jofi.setup.application.port.AiProviderPort

/**
 * Adapter rules shared by the production check and the known-bad fixture tests
 * ([AdapterRulesFixtureTest]), so both evaluate exactly the same rule.
 */
object AdapterRules {
    /** Generated jOOQ code of the whole schema lives in the shared kernel's persistence adapter (ADR-0032). */
    const val GENERATED_JOOQ = "$BASE.shared.adapter.persistence.jooq.."

    /**
     * Adapters talk to each other only through use cases and ports. One narrow exemption: every
     * context's persistence adapter may use the generated jOOQ code, which is generated for the whole
     * schema into one package. Other adapter kinds (web, ai, ...) still may not touch it.
     */
    val adaptersAreIndependent: ArchRule =
        slices()
            .matching("$BASE.(*).adapter.(*)..")
            .should()
            .notDependOnEachOther()
            .ignoreDependency(resideInAPackage("..adapter.persistence.."), resideInAPackage(GENERATED_JOOQ))
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
     * interface stays usable, since that is how the guarded client reaches the AI adapter; its
     * implementations (which would create unguarded clients) are not. ArchUnit only sees our own
     * classes: ADR-0034 lists what it cannot catch (auto-configured clients, libraries that fetch
     * on their own).
     */
    val onlyTheNetAdapterMakesOutboundHttpCalls: ArchRule =
        noClasses()
            .that(areOutsideTheNetModule())
            .should()
            .dependOnClassesThat(isHttpClient())
            .orShould()
            .callMethodWhere(readsAUrl())
            .because("adapters/net is the only outbound HTTP client (SSRF guard, threat model T1)")

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
        )

    private fun areOutsideTheNetModule(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("are outside the adapters/net module") { javaClass ->
            val inPackage = javaClass.packageName == NET_ADAPTER || javaClass.packageName.startsWith("$NET_ADAPTER.")
            val inModule = javaClass.source.map { NET_MODULE_OUTPUT in it.uri.toString() }.orElse(false)
            !(inPackage && inModule)
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

    private fun isTheProviderPort(): DescribedPredicate<JavaClass> =
        DescribedPredicate.describe("AiProviderPort itself") { it.isEquivalentTo(AiProviderPort::class.java) }
}
