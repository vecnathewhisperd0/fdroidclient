package org.fdroid.index.v2

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * A class using Kotlin reflection to implement JSON Merge Patch (RFC 7386) against data classes.
 * This approach loses type-safety, so you need to ensure that the structure of the [JsonObject]
 * matches exactly the structure of the data class you want to apply the diff on.
 * If something unexpected happens, [SerializationException] gets thrown.
 */
public object ReflectionDiffer {

    @Throws(SerializationException::class)
    public fun applyDiff(
        obj: Map<String, *>,
        diff: JsonObject,
        isFileV2: Boolean = false,
    ): Map<String, *> = obj.toMutableMap().apply {
        diff.entries.forEach { (key, value) ->
            when (value) {
                is JsonNull -> remove(key)
                is JsonPrimitive -> set(key, value.jsonPrimitive.content)
                is JsonObject -> {
                    val newValue: Any = if (isFileV2) {
                        constructFromJson(FileV2::class.primaryConstructor!!, value.jsonObject)
                    } else {
                        applyDiff(HashMap<String, LocalizedTextV2>(), value.jsonObject)
                    }
                    set(key, newValue)
                }
                else -> e("unsupported map value: $value")
            }
        }
    }

    @Throws(SerializationException::class)
    public fun <T : Any> applyDiff(obj: T, diff: JsonObject): T {
        val constructor = obj::class.primaryConstructor ?: e("no primary constructor")
        val params = HashMap<KParameter, Any?>()
        constructor.parameters.forEach { parameter ->
            val prop = obj::class.memberProperties.find { memberProperty ->
                memberProperty.name == parameter.name
            } ?: e("no member property for constructor, is data class?")
            if (prop.name !in diff) {
                params[parameter] = prop.getter.call(obj)
                return@forEach
            }
            if (diff[prop.name] is JsonNull) {
                if (parameter.type.isMarkedNullable) params[parameter] = null
                else if (!parameter.isOptional) e("not nullable: ${parameter.name}")
                return@forEach
            }
            @Suppress("UNCHECKED_CAST")
            params[parameter] = when (prop.returnType.classifier) {
                Int::class -> diff[prop.name]?.primitiveOrNull()?.intOrNull
                    ?: e("${prop.name} no int")
                Long::class -> diff[prop.name]?.primitiveOrNull()?.longOrNull
                    ?: e("${prop.name} no long")
                String::class -> diff[prop.name]?.primitiveOrNull()?.contentOrNull
                    ?: e("${prop.name} no string")
                List::class -> diff[prop.name]?.jsonArrayOrNull()?.map {
                    it.primitiveOrNull()?.contentOrNull ?: e("${prop.name} non-primitive array")
                } ?: e("${prop.name} no array")
                Map::class -> if (prop.name == "icon") applyDiff(
                    obj = prop.getter.call(obj) as? Map<String, *> ?: emptyMap<String, FileV2>(),
                    diff = diff[prop.name]?.jsonObjectOrNull() ?: e("${prop.name} no map"),
                    isFileV2 = true, // yes this is super hacky
                ) else applyDiff(
                    obj = prop.getter.call(obj) as? Map<String, *> ?: emptyMap<String, String>(),
                    diff = diff[prop.name]?.jsonObjectOrNull() ?: e("${prop.name} no map"),
                )
                else -> {
                    val newObj = prop.getter.call(obj)
                    val jsonObject = diff[prop.name] as? JsonObject ?: e("${prop.name} no dict")
                    if (newObj == null) {
                        val factory = (prop.returnType.classifier as KClass<*>).primaryConstructor!!
                        constructFromJson(factory, jsonObject)
                    } else {
                        applyDiff(newObj, jsonObject)
                    }
                }
            }
        }
        return constructor.callBy(params)
    }

    /**
     * Used when the diff introduces a new object.
     * As the object did not exist before, we can not apply a diff to it,
     * but must construct it from scratch.
     * We use the given [factory] for that which is typically a constructor of the object <T>.
     */
    @Throws(SerializationException::class)
    internal fun <T : Any> constructFromJson(
        factory: KFunction<T>,
        diff: JsonObject,
    ): T {
        val params = HashMap<KParameter, Any?>()
        factory.parameters.forEach { prop ->
            if (prop.name !in diff) {
                if (prop.isOptional) return@forEach
                else e("${prop.name} required but not found")
            }
            if (diff[prop.name] is JsonNull) {
                if (prop.type.isMarkedNullable) params[prop] = null
                else if (!prop.isOptional) e("not nullable: ${prop.name}")
                return@forEach
            }
            params[prop] = when (prop.type.classifier) {
                Int::class -> diff[prop.name]?.primitiveOrNull()?.intOrNull
                    ?: e("${prop.name} no int")
                Long::class -> diff[prop.name]?.primitiveOrNull()?.longOrNull
                    ?: e("${prop.name} no long")
                String::class -> diff[prop.name]?.primitiveOrNull()?.contentOrNull
                    ?: e("${prop.name} no string")
                List::class -> diff[prop.name]?.jsonArrayOrNull()?.map {
                    it.primitiveOrNull()?.contentOrNull ?: e("${prop.name} non-primitive array")
                } ?: e("${prop.name} no array")
                Map::class -> applyDiff(
                    obj = HashMap<String, String>(),
                    diff = diff[prop.name]?.jsonObjectOrNull() ?: e("${prop.name} no dict"),
                )
                else -> constructFromJson(
                    factory = (prop.type.classifier as KClass<*>).primaryConstructor!!,
                    diff = diff[prop.name]?.jsonObjectOrNull() ?: e("${prop.name} no dict"),
                )
            }
        }
        return factory.callBy(params)
    }

    private fun JsonElement.primitiveOrNull(): JsonPrimitive? = try {
        jsonPrimitive
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = try {
        jsonArray
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = try {
        jsonObject
    } catch (e: IllegalArgumentException) {
        null
    }

    @Throws(SerializationException::class)
    private fun e(msg: String): Nothing = throw SerializationException(msg)

    public inline fun <reified T> Json.decodeOr(
        key: String,
        json: JsonObject,
        default: () -> T,
    ): T {
        return if (json.containsKey(key)) {
            decodeFromJsonElement(serializersModule.serializer(), json)
        } else {
            default()
        }
    }

}
