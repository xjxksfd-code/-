package com.autumn.douyin.liquidglass.root

import java.io.DataInput
import java.io.DataInputStream

data class CompositeFrameHeader(
    val magic: Int,
    val status: Int,
    val width: Int,
    val height: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val byteCount: Int,
    val frameTimestamp: Long,
)

object CompositeFrameProtocol {
    const val Magic = 0x4c475043
    const val StatusOk = 1
    const val StatusError = 2
    const val StatusIdle = 3

    fun read(input: DataInput): CompositeFrameHeader {
        val magic = input.readInt()
        val status = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        val left = input.readInt()
        val top = input.readInt()
        val right = input.readInt()
        val bottom = input.readInt()
        val byteCount = input.readInt()

        val validStatus = status == StatusOk ||
            status == StatusError ||
            status == StatusIdle
        if (magic != Magic || !validStatus) {
            throw IllegalArgumentException(
                "bad composite response magic=$magic status=$status",
            )
        }

        // Error packets carry a UTF message instead of a timestamp. Reading the
        // timestamp unconditionally used to shift the stream and force reconnects.
        val frameTimestamp = if (status == StatusOk || status == StatusIdle) {
            input.readLong()
        } else {
            0L
        }
        return CompositeFrameHeader(
            magic = magic,
            status = status,
            width = width,
            height = height,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            byteCount = byteCount,
            frameTimestamp = frameTimestamp,
        )
    }

    fun readError(input: DataInputStream): String = input.readUTF()
}
