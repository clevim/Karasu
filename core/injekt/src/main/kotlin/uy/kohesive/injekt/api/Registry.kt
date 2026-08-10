package uy.kohesive.injekt.api

interface InjektRegistry {
    fun <T: Any> hasFactory(forType: TypeReference<T>): Boolean
}

inline fun <reified T: Any> InjektRegistry.hasFactory(): Boolean {
    return hasFactory(fullType<T>())
}
