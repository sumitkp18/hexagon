package co.there4.hexagon.serialization

import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.core.JsonToken.START_OBJECT
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

import java.io.InputStream
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalTime
import java.util.*
import kotlin.reflect.KClass

val defaultFormat = JacksonSerializer.contentTypes.first()

fun Any.convertToMap(): Map<*, *> = JacksonSerializer.toMap (this)
fun <T : Any> Map<*, *>.convertToObject(type: KClass<T>) = JacksonSerializer.toObject(this, type)

fun Any.serialize (contentType: String = defaultFormat) =
    JacksonSerializer.serialize(this, contentType)

fun <T : Any> String.parse (type: KClass<T>, contentType: String = defaultFormat) =
    JacksonSerializer.parse (this, type, contentType)
fun <T : Any> String.parseList (type: KClass<T>, contentType: String = defaultFormat) =
    JacksonSerializer.parseList (this, type, contentType)

fun <T : Any> InputStream.parse (type: KClass<T>, contentType: String = defaultFormat) =
    JacksonSerializer.parse (this, type, contentType)
fun <T : Any> InputStream.parseList (type: KClass<T>, contentType: String = defaultFormat) =
    JacksonSerializer.parseList (this, type, contentType)

fun createObjectMapper(mapperFactory: JsonFactory = MappingJsonFactory()): ObjectMapper {
    val mapper = ObjectMapper (mapperFactory)
    mapper.configure (FAIL_ON_UNKNOWN_PROPERTIES, false)
    mapper.configure (FAIL_ON_EMPTY_BEANS, false)
    mapper.setSerializationInclusion (NON_EMPTY)
    mapper.registerModule (Jdk8Module ())
    mapper.registerModule (JavaTimeModule ())
    mapper.registerModule (KotlinModule ())
    mapper.registerModule (object : SimpleModule() {
        init {
            addSerializer (ByteBuffer::class.java, ByteBufferSerializer)
            addDeserializer (ByteBuffer::class.java, ByteBufferDeserializer)
            addSerializer (LocalTime::class.java, LocalTimeSerializer)
            addDeserializer (LocalTime::class.java, LocalTimeDeserializer)
            addSerializer (LocalDate::class.java, LocalDateSerializer)
            addDeserializer (LocalDate::class.java, LocalDateDeserializer)
            addSerializer (ClosedRange::class.java, ClosedRangeSerializer)
            addDeserializer (ClosedRange::class.java, ClosedRangeDeserializer)
        }
    })
    return mapper
}

object ByteBufferSerializer: JsonSerializer<ByteBuffer>() {
    override fun serialize(value: ByteBuffer, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString (Base64.getEncoder ().encodeToString (value.array()))
    }
}

object ByteBufferDeserializer: JsonDeserializer<ByteBuffer>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ByteBuffer =
        ByteBuffer.wrap (Base64.getDecoder ().decode (p.text))
}

object LocalTimeSerializer: JsonSerializer<LocalTime> () {
    override fun serialize(
        value: LocalTime, gen: JsonGenerator, serializers: SerializerProvider) {

        gen.writeStartArray()
        gen.writeNumber(value.hour)
        gen.writeNumber(value.minute)
        gen.writeNumber(value.second)
        gen.writeNumber(value.nano)
        gen.writeEndArray()
    }
}

object LocalTimeDeserializer: JsonDeserializer<LocalTime> () {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): LocalTime {
        if (p.currentToken != JsonToken.START_ARRAY)
            error ("Local time should start with an array")
        val result = LocalTime.of(
            p.nextIntValue(0),
            p.nextIntValue(0),
            p.nextIntValue(0),
            p.nextIntValue(0)
        )
        if (p.nextToken() != JsonToken.END_ARRAY)
            error ("Local time should end with an array")
        return result
    }
}

object LocalDateSerializer: JsonSerializer<LocalDate> () {
    override fun serialize(
        value: LocalDate, gen: JsonGenerator, serializers: SerializerProvider) {

        gen.writeStartArray()
        gen.writeNumber(value.year)
        gen.writeNumber(value.monthValue)
        gen.writeNumber(value.dayOfMonth)
        gen.writeEndArray()
    }
}

object LocalDateDeserializer: JsonDeserializer<LocalDate> () {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): LocalDate {
        if (p.currentToken != JsonToken.START_ARRAY)
            error ("Local date should start with an array")
        val result = LocalDate.of(
            p.nextIntValue(0),
            p.nextIntValue(0),
            p.nextIntValue(0)
        )
        if (p.nextToken() != JsonToken.END_ARRAY)
            error ("Local date should end with an array")
        return result
    }
}

object ClosedRangeSerializer: JsonSerializer<ClosedRange<*>> () {
    override fun serialize(
        value: ClosedRange<*>, gen: JsonGenerator, serializers: SerializerProvider) {

        val start = value.start
        val end = value.endInclusive
        val valueSerializer = serializers.findValueSerializer(start.javaClass)

        gen.writeStartObject()

        gen.writeFieldName("start")
        valueSerializer.serialize(start, gen, serializers)

        gen.writeFieldName("endInclusive")
        valueSerializer.serialize(end, gen, serializers)

        gen.writeEndObject()
    }
}

object ClosedRangeDeserializer: JsonDeserializer<ClosedRange<*>> (), ContextualDeserializer {
    private var valueType: ThreadLocal<JavaType?> = ThreadLocal.withInitial { null }

    override fun createContextual(
        ctxt: DeserializationContext, property: BeanProperty): JsonDeserializer<*> {

        valueType.set(property.type.containedType(0))
        return ClosedRangeDeserializer
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ClosedRange<*> {
        if (p.currentToken != START_OBJECT)
            error ("Closed range should be an object")
        check(p.nextFieldName() == "start") { "Ranges should start with 'start' field" }
        p.nextToken() // Start object
        val type = valueType.get()
        @Suppress("UNCHECKED_CAST") val start = ctxt.readValue<Any>(p, type) as Comparable<Any>
        check(p.nextFieldName() == "endInclusive") { "Ranges should end with 'endInclusive' field" }
        p.nextToken() // End array
        @Suppress("UNCHECKED_CAST") val end = ctxt.readValue<Any>(p, type) as Comparable<Any>
        p.nextToken() // End array
        return start .. end
    }
}

