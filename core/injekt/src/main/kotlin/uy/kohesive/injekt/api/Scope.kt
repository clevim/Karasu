package uy.kohesive.injekt.api

/**
 * Not much difference than a InjektRegistrar for now...
 */
open class InjektScope(val registrar: InjektRegistrar) : InjektRegistrar by registrar {
    inline fun <reified T : Any> injectLazy(): Lazy<T> {
        return lazy { get(fullType<T>()) }
    }

    inline fun <reified T : Any> injectValue(): Lazy<T> {
        return lazyOf(get(fullType<T>()))
    }

    inline fun <reified T : Any> injectLazy(key: Any): Lazy<T> {
        return lazy { get(fullType<T>(), key) }
    }

    inline fun <reified T : Any> injectValue(key: Any): Lazy<T> {
        return lazyOf(get(fullType<T>(), key))
    }
}
