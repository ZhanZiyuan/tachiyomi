package eu.kanade.tachiyomi.extension.util

import dalvik.system.PathClassLoader
import java.net.URL
import java.util.Collections
import java.util.Enumeration

// Adapted from Mihon (Apache-2.0), e588e04359fe1058206a2cbac1b01018ad787359.
/**
 * A [PathClassLoader] that looks up classes like Android's delegate-last class loader: the boot class loader first, then its own
 * dex path, then the parent.
 */
internal class ExtensionClassLoader(
    dexPath: String,
    parent: ClassLoader,
) : PathClassLoader(dexPath, null, parent) {

    private val bootClassLoader: ClassLoader? = Any::class.java.classLoader

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }

        if (bootClassLoader != null) {
            try {
                return bootClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        val fromSuper = try {
            return findClass(name)
        } catch (e: ClassNotFoundException) {
            e
        }

        return try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            throw fromSuper
        }
    }

    override fun getResource(name: String?): URL? {
        return bootClassLoader?.getResource(name)
            ?: findResource(name)
            ?: parent?.getResource(name)
    }

    override fun getResources(name: String?): Enumeration<URL> {
        val resources = buildList {
            bootClassLoader?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        }
        return Collections.enumeration(resources)
    }
}
