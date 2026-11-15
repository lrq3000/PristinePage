package app.pristinepage.core

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier

/** Keeps the initial absent-implementation RED in JUnit rather than failing Kotlin test compilation. All assertions exercise production behavior; this bridge can be removed when callers adopt the implemented types. */
internal object CoreApi {
    fun type(name: String): Class<*> = try {
        Class.forName("app.pristinepage.core.$name")
    } catch (missing: ClassNotFoundException) {
        throw AssertionError("Required core implementation is absent: $name", missing)
    }

    private fun compatible(types: Array<Class<*>>, args: Array<out Any?>): Boolean = types.size == args.size && types.indices.all { i ->
        args[i] == null || box(types[i]).isInstance(args[i])
    }

    private fun box(type: Class<*>): Class<*> = when (type) {
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        else -> type
    }

    private fun <T> unwrap(action: () -> T): T = try { action() } catch (error: InvocationTargetException) { throw error.targetException }

    fun create(name: String, vararg args: Any?): Any = unwrap {
        type(name).constructors.single { compatible(it.parameterTypes, args) }.newInstance(*args)
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? = unwrap {
        target.javaClass.methods.single { it.name == name && compatible(it.parameterTypes, args) }.invoke(target, *args)
    }

    fun function(type: String, name: String, vararg args: Any?): Any? = unwrap {
        val owner = this.type(type)
        val method = owner.methods.single { it.name == name && compatible(it.parameterTypes, args) }
        method.invoke(if (Modifier.isStatic(method.modifiers)) null else owner.getField("INSTANCE").get(null), *args)
    }
}
