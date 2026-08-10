package uy.kohesive.injekt.api

import java.lang.reflect.Type
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatformTools

interface InjektRegistrar: InjektRegistry, InjektFactory

open class KoinRegistrar: InjektRegistrar {

    // REF: https://github.com/InsertKoinIO/koin/blob/74f91987ef94e63e8ea23ac9ed0ce24d6650d742/projects/core/koin-core/src/commonMain/kotlin/org/koin/core/component/KoinComponent.kt#L33
    private fun getKoin() = KoinPlatformTools.defaultContext().get()
    private fun getKoinOrNull() = KoinPlatformTools.defaultContext().getOrNull()

    override fun <T : Any> hasFactory(forType: TypeReference<T>): Boolean {
        return getKoinOrNull()?.getOrNull<T>(forType.type.kotlinClass) != null
    }

    override fun <R : Any> getInstance(forType: Type): R {
        return getKoin().get(forType.kotlinClass)
    }

    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass) ?: default
    }

    override fun <R : Any> getInstanceOrElse(forType: Type, default: () -> R): R {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass) ?: default.invoke()
    }

    override fun <R : Any> getInstanceOrNull(forType: Type): R? {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass)
    }

    override fun <R : Any, K : Any> getKeyedInstance(forType: Type, key: K): R {
        return getKoin().get(forType.kotlinClass, named(key as? String ?: key.toString()))
    }

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: R): R {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass, named(key as? String ?: key.toString())) ?: default
    }

    override fun <R : Any, K : Any> getKeyedInstanceOrElse(forType: Type, key: K, default: () -> R): R {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass, named(key as? String ?: key.toString())) ?: default.invoke()
    }

    override fun <R : Any, K : Any> getKeyedInstanceOrNull(forType: Type, key: K): R? {
        return getKoinOrNull()?.getOrNull(forType.kotlinClass, named(key as? String ?: key.toString()))
    }
}
