package io.github.gdlbo.makerplay.runtime.api

import java.util.Collections

class ImmutableList<out T> private constructor(
    private val values: List<T>,
) : List<T> by values {
    override fun equals(other: Any?): Boolean = other is List<*> && values == other
    override fun hashCode(): Int = values.hashCode()

    companion object {
        fun <T> copyOf(values: Iterable<T>): ImmutableList<T> =
            ImmutableList(Collections.unmodifiableList(values.toList()))

        fun <T> empty(): ImmutableList<T> = copyOf(emptyList())
    }
}

class ImmutableSet<out T> private constructor(
    private val values: Set<T>,
) : Set<T> by values {
    override fun equals(other: Any?): Boolean = other is Set<*> && values == other
    override fun hashCode(): Int = values.hashCode()

    companion object {
        fun <T> copyOf(values: Iterable<T>): ImmutableSet<T> =
            ImmutableSet(Collections.unmodifiableSet(values.toSet()))

        fun <T> empty(): ImmutableSet<T> = copyOf(emptySet())
    }
}

class ImmutableMap<K, out V> private constructor(
    private val entriesBacking: Map<K, V>,
) : Map<K, V> by entriesBacking {
    override fun equals(other: Any?): Boolean = other is Map<*, *> && entriesBacking == other
    override fun hashCode(): Int = entriesBacking.hashCode()

    companion object {
        fun <K, V> copyOf(values: Map<K, V>): ImmutableMap<K, V> =
            ImmutableMap(Collections.unmodifiableMap(values.toMap()))

        fun <K, V> empty(): ImmutableMap<K, V> = copyOf(emptyMap())
    }
}
