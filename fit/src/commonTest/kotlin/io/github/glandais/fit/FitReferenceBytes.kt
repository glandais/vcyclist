package io.github.glandais.fit

/**
 * Byte-level reference output for [FitReferenceCourse], committed so that any accidental change
 * to message order, field order, units or local message numbering fails a test instead of
 * silently shipping a different file.
 *
 * ## Why there is one constant and no longer two
 *
 * Before w12 there were two: `JVM`, produced by `com.garmin:fit`, and `WEB`, produced by
 * `@garmin/fitsdk`. They were the same 277 bytes carrying the same values, and differed in two
 * places no caller controlled — the protocol-version byte (`0x20` from the Java SDK, `0x02`
 * from the JavaScript one) and the architecture byte of every message definition (big-endian
 * from Java, little-endian from JavaScript, hence every multi-byte value byte-swapped).
 *
 * One multiplatform encoder means one answer. [FitEncoderTest] asserts it on JVM, JS and
 * wasmWasi, so cross-target byte identity — which the two vendor SDKs made impossible — is now
 * a test rather than a caveat. The file is little-endian, like the JavaScript SDK's output and
 * like every Garmin producer's.
 *
 * The interoperability claim is still checked from the outside: `FitEncoderJsTest` decodes
 * these very bytes with `@garmin/fitsdk`, the vendor implementation.
 *
 * Regenerate by encoding [FitReferenceCourse] on any target and printing the bytes as
 * lowercase hex. Expect exactly four bytes to move on an `fit-kotlin-sdk` version bump — the
 * little-endian profile version at offsets 2–3 (`21.213.0` → `dd52`) and the header CRC at
 * offsets 12–13 that covers it. Any change beyond those four is a real encoder change.
 */
object FitReferenceBytes {
    /** Output of [FitEncoder], identical on every target. */
    val REFERENCE: ByteArray =
        hex(
            "0e20dd52050100002e4649543c4a40000000000600010001028402028403048c" +
                "05028404048600060f0039303930000046f18015cb444100001f000205140704" +
                "010001436f6c206465206c61204d6164656c65696e6500024200001300080204" +
                "86fd04860704860804860904861502841602840d0284028015cb449415cb4420" +
                "4e0000204e0000dc37000000000100ee1b430000150004000100010100040102" +
                "fd0486030000008015cb44440000140007fd0486000485010485050486020284" +
                "060284070284048015cb44c8ea7b2090608c04000000009a1000002d00048a15" +
                "cb4483087c204b648c04ee1b00009810ee1b0401049415cb443b137c20c4688c" +
                "04dc3700009810ee1bff00030004009415cb445025",
        )

    private fun hex(h: String): ByteArray =
        ByteArray(h.length / 2) { i ->
            ((h[i * 2].digit() shl 4) or h[i * 2 + 1].digit()).toByte()
        }

    private fun Char.digit(): Int = if (this in '0'..'9') this - '0' else this - 'a' + 10
}
