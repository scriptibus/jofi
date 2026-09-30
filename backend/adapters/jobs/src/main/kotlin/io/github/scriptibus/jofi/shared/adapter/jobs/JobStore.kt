// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

package io.github.scriptibus.jofi.shared.adapter.jobs

import org.jobrunr.jobs.mappers.JobMapper
import org.jobrunr.storage.StorageProvider
import org.jobrunr.storage.StorageProviderUtils.DatabaseOptions
import org.jobrunr.storage.sql.postgres.PostgresStorageProvider
import org.jobrunr.utils.mapper.JsonMapper
import org.jobrunr.utils.mapper.jackson3.Jackson3JsonMapper
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.DeserializationConfig
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.deser.ValueDeserializerModifier
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.type.ArrayType
import tools.jackson.databind.type.CollectionLikeType
import tools.jackson.databind.type.CollectionType
import tools.jackson.databind.type.MapLikeType
import tools.jackson.databind.type.MapType
import tools.jackson.databind.type.ReferenceType
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource
import tools.jackson.databind.json.JsonMapper as JacksonJsonMapper

/**
 * The job store: JobRunr's tables in our PostgreSQL, created by our Flyway migration (ADR-0009),
 * read through two allowlists (ADR-0038): [JobJsonGuard] checks the raw JSON before JobRunr loads any
 * class it names, and [AllowlistModule] keeps JobRunr's Jackson mapper from building anything else.
 */
object JobStore {
    /**
     * JobRunr on PostgreSQL without its own table creation or validation: Flyway owns the schema
     * (`JobRunrSchemaTest` proves it matches JobRunr's), and nothing connects while the context
     * starts, so the image's AOT training run works without a database.
     */
    fun storageProvider(
        dataSource: DataSource,
        jobMapper: JobMapper,
    ): StorageProvider =
        PostgresStorageProvider(dataSource, null, DatabaseOptions.NO_VALIDATE).apply { setJobMapper(jobMapper) }

    /** JobRunr's Jackson 3 mapper that builds no class outside [isAllowed] (see [AllowlistModule]). */
    fun jsonMapper(): JsonMapper = Jackson3JsonMapper(JacksonJsonMapper.builder().addModule(AllowlistModule()))

    /** JobRunr's own model classes (states, job details, enums). */
    private const val JOBRUNR_PACKAGE = "org.jobrunr."

    /** The exact JDK types the job JSON contains; anything wider (e.g. `InetAddress`) could act on read. */
    private val ALLOWED_CLASSES: Set<Class<*>> =
        setOf(
            JofiJobRequest::class.java,
            Any::class.java,
            String::class.java,
            Long::class.javaObjectType,
            Int::class.javaObjectType,
            Boolean::class.javaObjectType,
            Double::class.javaObjectType,
            UUID::class.java,
            Instant::class.java,
            Duration::class.java,
            Map::class.java,
            LinkedHashMap::class.java,
            HashMap::class.java,
            ConcurrentMap::class.java,
            ConcurrentHashMap::class.java,
            List::class.java,
            Collection::class.java,
            ArrayList::class.java,
            CopyOnWriteArrayList::class.java,
            ConcurrentLinkedQueue::class.java,
        )

    fun isAllowed(type: Class<*>): Boolean =
        when {
            type.isPrimitive -> true
            type.isArray -> isAllowed(type.componentType)
            else -> type in ALLOWED_CLASSES || type.name.startsWith(JOBRUNR_PACKAGE)
        }
}

/**
 * Second layer: replaces the deserializer of every class outside [JobStore.isAllowed] (beans, maps,
 * collections, enums, arrays, references) by one that refuses to build it. The first layer,
 * [JobJsonGuard], already rejects such JSON before JobRunr sees it.
 */
internal class AllowlistModule : SimpleModule("jofi-job-allowlist") {
    init {
        setDeserializerModifier(Guard())
    }

    private class Guard : ValueDeserializerModifier() {
        private fun allowOrRefuse(
            type: Class<*>,
            deserializer: ValueDeserializer<*>,
        ): ValueDeserializer<*> = if (JobStore.isAllowed(type)) deserializer else Refused(type.name)

        override fun modifyDeserializer(
            config: DeserializationConfig,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(description.beanClass, deserializer)

        override fun modifyEnumDeserializer(
            config: DeserializationConfig,
            type: JavaType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyReferenceDeserializer(
            config: DeserializationConfig,
            type: ReferenceType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyArrayDeserializer(
            config: DeserializationConfig,
            type: ArrayType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyCollectionDeserializer(
            config: DeserializationConfig,
            type: CollectionType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyCollectionLikeDeserializer(
            config: DeserializationConfig,
            type: CollectionLikeType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyMapDeserializer(
            config: DeserializationConfig,
            type: MapType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)

        override fun modifyMapLikeDeserializer(
            config: DeserializationConfig,
            type: MapLikeType,
            description: BeanDescription.Supplier,
            deserializer: ValueDeserializer<*>,
        ) = allowOrRefuse(type.rawClass, deserializer)
    }

    private class Refused(
        private val className: String,
    ) : ValueDeserializer<Any>() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): Any = error("Class $className is not allowed in the job store")
    }
}
