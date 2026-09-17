package kr.decacross.compat.serial

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kr.decacross.compat.model.PackFormat
import kotlin.time.Instant

/** `kotlin.time.Instant` ↔ ISO-8601 문자열. 픽스처 JSON 과 데몬 API 가 공유한다. */
public object InstantIsoSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kr.decacross.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant): Unit = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** `PackFormat` ↔ "34" / "88.0" / "101.1". 숫자로 내보내지 않는다 (불변식 3). */
public object PackFormatSerializer : KSerializer<PackFormat> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kr.decacross.PackFormat", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PackFormat): Unit = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): PackFormat {
        val s = decoder.decodeString()
        return PackFormat.parse(s) ?: throw SerializationException("잘못된 pack_format: '$s'")
    }
}
