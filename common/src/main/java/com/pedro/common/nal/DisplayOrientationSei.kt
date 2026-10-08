/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.common.nal

import com.pedro.common.VideoCodec

/**
 * Builds the standard display-orientation SEI (payload type 47 in H.264 and H.265): the
 * turn a receiver applies to stand sensor-oriented frames upright.
 *
 * Carried in the video rather than beside it, so it reaches every receiver the frames
 * do: over RTSP, through a recorder that stores the packets as they are, into any file
 * copy. FFmpeg's decoders attach it to every frame it persists over as a display matrix.
 * The SDP's a=x-video-rotation states the same turn but lives only in the session, and a
 * container tag only in a file.
 *
 * Sent with every frame, at 8 bytes: FFmpeg's H.264 decoder drops the declaration at the
 * next frame whatever its repetition period says (measured: keyframes -90, the frames
 * between them nothing), so no receiver may be left to rely on persistence.
 */
object DisplayOrientationSei {

  private const val PAYLOAD_TYPE_DISPLAY_ORIENTATION = 47

  /** True when this NAL body is a display-orientation SEI. See [CaptureTimeSei.isCaptureTimeSei]. */
  fun isDisplayOrientationSei(nal: java.nio.ByteBuffer, headerSize: Int): Boolean {
    if (nal.remaining() < headerSize + 2) return false
    return nal.get(headerSize).toInt() == PAYLOAD_TYPE_DISPLAY_ORIENTATION
  }

  /**
   * @param clockwiseDegrees the turn that stands a frame upright: 90, 180 or 270
   * @return a start-code-prefixed SEI NAL, or null when there is nothing to declare or the
   * codec carries no SEI
   */
  fun build(codec: VideoCodec, clockwiseDegrees: Int): ByteArray? {
    val cw = ((clockwiseDegrees % 360) + 360) % 360
    if (cw == 0 || cw % 90 != 0) return null
    val header = when (codec) {
      VideoCodec.H265 -> byteArrayOf(0x4E, 0x01)   // PREFIX_SEI, layer 0, temporal id 1
      VideoCodec.H264 -> byteArrayOf(0x06)         // nal_ref_idc 0, SEI
      else -> return null
    }
    // The syntax counts anticlockwise, in units of 2^-16 of a full turn.
    val anticlockwise = ((360 - cw) % 360) * 65536 / 360

    val bits = BitWriter()
    bits.put(0, 1)                    // display_orientation_cancel_flag
    bits.put(0, 1)                    // hor_flip
    bits.put(0, 1)                    // ver_flip
    bits.put(anticlockwise, 16)       // anticlockwise_rotation
    if (codec == VideoCodec.H264) {
      bits.put(0b010, 3)              // display_orientation_repetition_period = 1, ue(v)
      bits.put(0, 1)                  // display_orientation_extension_flag
    } else {
      bits.put(1, 1)                  // display_orientation_persistence_flag
    }
    bits.alignWithOne()               // payload bit_equal_to_one, then zeros
    val payload = bits.toByteArray()

    val rbsp = ArrayList<Byte>(payload.size + 3)
    rbsp.add(PAYLOAD_TYPE_DISPLAY_ORIENTATION.toByte())
    rbsp.add(payload.size.toByte())
    payload.forEach { rbsp.add(it) }
    rbsp.add(0x80.toByte())           // rbsp_trailing_bits

    val out = ArrayList<Byte>(rbsp.size + header.size + 8)
    out.add(0); out.add(0); out.add(0); out.add(1)
    header.forEach { out.add(it) }
    var zeros = 0
    for (b in rbsp) {
      if (zeros == 2 && (b.toInt() and 0xFF) <= 0x03) {
        out.add(0x03)
        zeros = 0
      }
      out.add(b)
      zeros = if (b.toInt() == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  private class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var current = 0
    private var used = 0

    fun put(value: Int, count: Int) {
      for (i in count - 1 downTo 0) bit((value ushr i) and 1)
    }

    fun alignWithOne() {
      bit(1)
      while (used != 0) bit(0)
    }

    private fun bit(b: Int) {
      current = (current shl 1) or b
      if (++used == 8) {
        bytes.add(current.toByte())
        current = 0
        used = 0
      }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
  }
}
