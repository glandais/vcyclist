package io.github.glandais.fit

/**
 * Byte-level reference output for [FitReferenceCourse], committed so that any accidental change
 * to message order, field order, units or local message numbering fails a test instead of
 * silently shipping a different file.
 *
 * ## Why there are two constants and not one
 *
 * [WEB] is what Kotlin/JS and Kotlin/Wasm produce — **byte for byte identical to each other**,
 * which is the contract that keeps the two hand-written `actual`s from drifting.
 *
 * [JVM] differs, and the difference is entirely attributable to the two Garmin SDKs. It was
 * chased down field by field; what remains is:
 *
 * | Offset | Java SDK | JavaScript SDK | Why |
 * |---|---|---|---|
 * | header byte 1 | `0x20` | `0x02` | Protocol version. The Java SDK encodes V2.0 as `major shl 4`; the JS SDK hardcodes the literal `2` in `encoder.js#updateFileHeader`. Neither exposes a setting. |
 * | header bytes 12-13 | differs | differs | Header CRC, a consequence of the byte above. |
 * | every definition's architecture byte | `1` (big-endian) | `0` (little-endian) | The Java SDK writes big-endian; the JS SDK has no architecture support at all and always writes little-endian. Consequently every multi-byte field value is byte-swapped between the two files. |
 *
 * Everything else matches: same 277 bytes, same messages in the same order, same local message
 * numbers, same definition field lists, same values. Both files pass the *other* SDK's
 * integrity check and decode to identical field values — asserted from both sides, so this is
 * a verified interoperability claim rather than an assumption.
 *
 * Neither difference is a defect: the architecture byte exists in the FIT format precisely so a
 * reader can handle either endianness, and both decoders accept both protocol-version encodings.
 * Forcing byte-identity would mean patching a vendor SDK.
 *
 * Regenerate by encoding [FitReferenceCourse] on the relevant target and printing the bytes as
 * lowercase hex.
 */
object FitReferenceBytes {
    /** Kotlin/JS and Kotlin/Wasm output. */
    val WEB: ByteArray =
        hex(
            "0e02d552050100002e4649541dd840000000000600010201028402028403048c" +
                "05028404048600060f0039303930000046f18015cb444100001f000205140704" +
                "010201436f6c206465206c61204d6164656c65696e6500024200001300080204" +
                "86fd04860704860804860904861502841602840d0284028015cb449415cb4420" +
                "4e0000204e0000dc37000000000100ee1b430000150004000102010102040102" +
                "fd0486030000008015cb44440000140007fd0486000485010485050486020284" +
                "060284070284048015cb44c8ea7b2090608c04000000009b1000002d00048a15" +
                "cb4483087c204b648c04ee1b00009910ee1b0401049415cb443b137c20c4688c" +
                "04dc3700009810ee1bff00030004009415cb44908c",
        )

    /** JVM output — see the class KDoc for the two documented differences. */
    val JVM: ByteArray =
        hex(
            "0e20d552050100002e464954bda040000100000600010001028402028403048c" +
                "0502840404860006000f303900003039f14644cb1580410001001f0205140704" +
                "010001436f6c206465206c61204d6164656c65696e6500024200010013080204" +
                "86fd04860704860804860904861502841602840d02840244cb158044cb159400" +
                "004e2000004e20000037dc000000011bee430001001504000100010100040102" +
                "fd04860300000044cb1580440001001407fd0486000485010485050486020284" +
                "0602840702840444cb1580207beac8048c609000000000109b0000002d0444cb" +
                "158a207c0883048c644b00001bee10991bee01040444cb1594207c133b048c68" +
                "c4000037dc10981bee00ff0300040044cb159439d4",
        )

    private fun hex(h: String): ByteArray =
        ByteArray(h.length / 2) { i ->
            ((h[i * 2].digit() shl 4) or h[i * 2 + 1].digit()).toByte()
        }

    private fun Char.digit(): Int = if (this in '0'..'9') this - '0' else this - 'a' + 10
}
