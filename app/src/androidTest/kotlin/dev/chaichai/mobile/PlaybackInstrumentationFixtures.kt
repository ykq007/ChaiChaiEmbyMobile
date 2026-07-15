package dev.chaichai.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

internal fun waitForInstrumentationState(
    description: String = "Media3 state",
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    check(condition()) { "Timed out waiting for $description" }
}

internal fun <T> onInstrumentationMain(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
    return result.get().getOrThrow()
}

internal fun silentWav(durationSeconds: Int = 1): ByteArray {
    val sampleRate = 8_000
    val dataSize = sampleRate * 2 * durationSeconds
    return ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + dataSize)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1.toShort())
        putShort(1.toShort())
        putInt(sampleRate)
        putInt(sampleRate * 2)
        putShort(2.toShort())
        putShort(16.toShort())
        put("data".toByteArray())
        putInt(dataSize)
        put(ByteArray(dataSize))
    }.array()
}
