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

package com.pedro.rtsp.rtsp

import com.pedro.common.VideoCodec

/**
 * Builds an unregistered SEI NAL carrying the absolute capture time of a frame.
 *
 * Why in the bitstream rather than in RTCP: a Sender Report states when *one* frame was
 * captured and leaves the receiver to map every other frame onto it. That mapping is the
 * receiver's, and it is not reliably exposed — FFmpeg pins start_time_realtime to the
 * first Sender Report's NTP while numbering pts from the first RTP packet it happened to
 * receive, so the two describe different frames and every frame inherits the gap. It is
 * constant within a session and unknowable from outside, which measured 564 ms on one
 * stream and 615 ms on the next.
 *
 * Travelling with the access unit removes the mapping entirely: the time arrives attached
 * to the frame it describes, survives packet loss and reordering, and is read back as
 * frame side data. The same idea as WebRTC's abs-capture-time header extension, carried
 * as SEI because an RTP header extension needs receiver support that FFmpeg lacks.
 *
 * Costs one ~30-byte NAL per frame.
 */
object CaptureTimeSei {

  /** Identifies our payload among any other unregistered SEI in the stream. */
  private val UUID = "SPC-CAPTURE-TIME".toByteArray(Charsets.US_ASCII)

  private const val PAYLOAD_TYPE_USER_DATA_UNREGISTERED = 5

  /**
   * @param captureUtcNs capture time, nanoseconds since the Unix epoch
   * @return a start-code-prefixed SEI NAL, or null for codecs that carry no SEI
   */
  fun build(codec: VideoCodec, captureUtcNs: Long): ByteArray? {
    val header = when (codec) {
      // nal_unit_type 39 (PREFIX_SEI), layer 0, temporal id 1
      VideoCodec.H265 -> byteArrayOf(0x4E, 0x01)
      // nal_ref_idc 0, nal_unit_type 6 (SEI)
      VideoCodec.H264 -> byteArrayOf(0x06)
      else -> return null
    }

    val payload = ByteArray(UUID.size + 8)
    UUID.copyInto(payload)
    for (i in 0 until 8) {
      payload[UUID.size + i] = (captureUtcNs ushr (56 - 8 * i)).toByte()
    }

    // sei_message(): payload type and size are byte-extended, both fit in one byte here.
    val rbsp = ArrayList<Byte>(payload.size + 3)
    rbsp.add(PAYLOAD_TYPE_USER_DATA_UNREGISTERED.toByte())
    rbsp.add(payload.size.toByte())
    payload.forEach { rbsp.add(it) }
    rbsp.add(0x80.toByte())   // rbsp_trailing_bits

    val out = ArrayList<Byte>(rbsp.size + header.size + 8)
    out.add(0); out.add(0); out.add(0); out.add(1)   // Annex-B start code
    header.forEach { out.add(it) }

    // Emulation prevention: a 00 00 0x sequence inside the payload would otherwise be
    // read as a start code and truncate the NAL.
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
}
