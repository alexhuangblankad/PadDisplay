package com.paddisplay.app.system

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 API 反射小工具。
 *
 * 设计原则（对应任务书第 18 节的 ColorOS 兼容性要求）：
 *  - 任何一次隐藏 API 调用都**不抛异常给 UI**，而是返回 [Result]，
 *    由上层把失败原因写进“诊断信息”，因为 ColorOS 上真正的问题是
 *    SecurityException / NoSuchMethodException / Binder 拒绝，
 *    这些必须可见才好排查。
 *  - 缓存 Method/Field 查找结果，避免每次热插拔都重新反射。
 */
object Reflect {

    private const val TAG = "PadDisplay/Reflect"

    /** 方法缓存：key = 类名#方法名#参数个数 */
    private val methodCache = HashMap<String, Method?>()
    private val fieldCache = HashMap<String, java.lang.reflect.Field?>()
    private val classCache = HashMap<String, Class<*>?>()

    fun classForName(name: String): Class<*>? = classCache.getOrPut(name) {
        try {
            Class.forName(name)
        } catch (t: Throwable) {
            Log.w(TAG, "class not found: $name (${t.javaClass.simpleName}: ${t.message})")
            null
        }
    }

    /**
     * 查找方法。
     *
     * 参数类型声明为 `Class<*>?`：因为 `Int::class.javaPrimitiveType` 这类
     * 表达式在 Kotlin 里的类型是 `Class<Int>?`（可空），
     * 写成 `vararg Class<*>` 会导致调用点编译不过。内部会过滤掉 null。
     */
    fun findMethod(
        clazz: Class<*>?,
        methodName: String,
        vararg paramTypes: Class<*>?,
    ): Method? {
        if (clazz == null) return null
        val types = paramTypes.filterNotNull().toTypedArray()
        val key = "${clazz.name}#$methodName#${types.joinToString(",") { it.name }}"
        methodCache[key]?.let { return it }
        if (methodCache.containsKey(key)) return null

        val m = try {
            clazz.getMethod(methodName, *types)
        } catch (t: Throwable) {
            try {
                clazz.getDeclaredMethod(methodName, *types)
            } catch (t2: Throwable) {
                Log.d(TAG, "method not found: ${clazz.simpleName}.$methodName(${types.joinToString { it.simpleName }})")
                null
            }
        }
        m?.isAccessible = true
        methodCache[key] = m
        return m
    }

    fun findField(clazz: Class<*>?, fieldName: String): java.lang.reflect.Field? {
        if (clazz == null) return null
        val key = "${clazz.name}#$fieldName"
        fieldCache[key]?.let { return it }
        if (fieldCache.containsKey(key)) return null
        val f = try {
            clazz.getField(fieldName)
        } catch (t: Throwable) {
            try {
                clazz.getDeclaredField(fieldName)
            } catch (t2: Throwable) {
                null
            }
        }
        f?.isAccessible = true
        fieldCache[key] = f
        return f
    }

    /** 调用静态方法。 */
    fun callStatic(clazz: Class<*>?, methodName: String, vararg args: Any?): Result<Any?> =
        invoke(null, findMethod(clazz, methodName, *argTypes(args)), methodName, *args)

    /** 调用实例方法。 */
    fun call(
        target: Any?,
        methodName: String,
        vararg args: Any?,
    ): Result<Any?> {
        if (target == null) return Result.failure(IllegalStateException("target is null for $methodName"))
        return invoke(target, findMethod(target.javaClass, methodName, *argTypes(args)), methodName, *args)
    }

    private fun invoke(
        target: Any?,
        method: Method?,
        methodName: String,
        vararg args: Any?,
    ): Result<Any?> {
        if (method == null) {
            return Result.failure(NoSuchMethodException("找不到方法 $methodName"))
        }
        return try {
            Result.success(method.invoke(target, *args))
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            Log.w(TAG, "$methodName 抛出 ${cause.javaClass.name}: ${cause.message}")
            Result.failure(cause)
        } catch (t: Throwable) {
            Log.w(TAG, "$methodName 调用失败 ${t.javaClass.name}: ${t.message}")
            Result.failure(t)
        }
    }

    /** 读静态字段。 */
    fun getStaticField(clazz: Class<*>?, fieldName: String): Result<Any?> {
        val f = findField(clazz, fieldName)
            ?: return Result.failure(NoSuchFieldException("找不到字段 $fieldName"))
        return try {
            Result.success(f.get(null))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** 读实例字段。 */
    fun getField(target: Any?, fieldName: String): Result<Any?> {
        if (target == null) return Result.failure(IllegalStateException("target is null"))
        val f = findField(target.javaClass, fieldName)
            ?: return Result.failure(NoSuchFieldException("找不到字段 $fieldName"))
        return try {
            Result.success(f.get(target))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun argTypes(args: Array<out Any?>): Array<Class<*>> =
        args.map { arg ->
            when (arg) {
                null -> Any::class.java
                is Int -> Int::class.javaPrimitiveType!!
                is Long -> Long::class.javaPrimitiveType!!
                is Boolean -> Boolean::class.javaPrimitiveType!!
                is Float -> Float::class.javaPrimitiveType!!
                is Double -> Double::class.javaPrimitiveType!!
                else -> arg.javaClass
            }
        }.toTypedArray()

    /** 把异常转成一行可读文本，写进诊断日志用。 */
    fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException) t.targetException ?: t else t
        return "${cause.javaClass.name}: ${cause.message ?: "(无消息)"}"
    }
}
