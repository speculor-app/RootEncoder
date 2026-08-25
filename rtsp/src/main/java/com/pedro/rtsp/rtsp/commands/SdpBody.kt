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

package com.pedro.rtsp.rtsp.commands

import com.pedro.common.config.AacAudioSpecificConfig
import com.pedro.common.config.AudioObjectType
import com.pedro.rtsp.utils.RtpConstants

/**
 * Created by pedro on 21/02/17.
 */
object SdpBody {

  // RFC 4566 a=framerate. Omitted entirely at 0, so a caller that does not know
  // the rate declares nothing rather than declaring zero.
  private fun frameRateAttr(fps: Int): String =
    if (fps > 0) "a=framerate:$fps\r\n" else ""

  // Display rotation of the coded frames, degrees clockwise. SDP has no
  // standard field for a static video rotation, so this is a private attribute
  // (RFC 4566 says receivers must ignore attributes they do not know) — the
  // sender that encodes SENSOR-oriented frames is the only party that knows how
  // to stand them up, and saying nothing forced every receiver to guess.
  // Omitted at 0: an upright stream declares nothing.
  private fun rotationAttr(rotation: Int): String =
    if (rotation != 0) "a=x-video-rotation:$rotation\r\n" else ""


  /**
   * Opus only support sample rate 48khz and stereo channel but Android encoder accept others values.
   * The encoder internally transform the sample rate to 48khz and channels to stereo
   */
  fun createOpusBody(trackAudio: Int, secured: Boolean = false): String {
    val payload = RtpConstants.payloadType + trackAudio
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
      "a=mid:$trackAudio\r\n"
    } else "a=control:streamid=$trackAudio\r\n"
    return "m=audio 0 $type ${payload}\r\n" +
        "a=rtpmap:$payload OPUS/48000/2\r\n" +
        identifier
  }

  fun createG711Body(trackAudio: Int, sampleRate: Int, isStereo: Boolean, secured: Boolean = false): String {
    val channel = if (isStereo) 2 else 1
    val payload = RtpConstants.payloadTypeG711
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
      "a=mid:$trackAudio\r\n"
    } else "a=control:streamid=$trackAudio\r\n"
    return "m=audio 0 $type ${payload}\r\n" +
        "a=rtpmap:$payload PCMA/$sampleRate/$channel\r\n" +
        identifier
  }

  fun createAacBody(trackAudio: Int, sampleRate: Int, isStereo: Boolean, secured: Boolean = false): String {
    val channels = if (isStereo) 2 else 1
    val config = AacAudioSpecificConfig(AudioObjectType.AAC_LC, sampleRate, channels)
    val configHex = config.calculate().toHexString()
    val payload = RtpConstants.payloadType + trackAudio
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
      "a=mid:$trackAudio\r\n"
    } else "a=control:streamid=$trackAudio\r\n"
    return "m=audio 0 $type ${payload}\r\n" +
        "a=rtpmap:$payload MPEG4-GENERIC/$sampleRate/$channels\r\n" +
        "a=fmtp:$payload profile-level-id=1; mode=AAC-hbr; config=$configHex; sizelength=13; indexlength=3; indexdeltalength=3\r\n" +
        identifier
  }

  fun createAV1Body(trackVideo: Int, secured: Boolean = false): String {
    val payload = RtpConstants.payloadType + trackVideo
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
      "a=mid:$trackVideo\r\n"
    } else "a=control:streamid=$trackVideo\r\n"
    return "m=video 0 $type $payload\r\n" +
        "a=rtpmap:$payload AV1/${RtpConstants.clockVideoFrequency}\r\n" +
        "a=fmtp:$payload profile=0; level-idx=0;\r\n" +
        identifier
  }

  /** @param fps frames per second to DECLARE, or 0 to say nothing. See [createH265Body]. */
  fun createH264Body(trackVideo: Int, sps: String, pps: String, secured: Boolean = false, fps: Int = 0, rotation: Int = 0): String {
    val payload = RtpConstants.payloadType + trackVideo
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
          "a=mid:$trackVideo\r\n"
    } else "a=control:streamid=$trackVideo\r\n"
    return "m=video 0 $type $payload\r\n" +
        "a=rtpmap:$payload H264/${RtpConstants.clockVideoFrequency}\r\n" +
        "a=fmtp:$payload packetization-mode=1; sprop-parameter-sets=$sps,$pps\r\n" +
        frameRateAttr(fps) +
        rotationAttr(rotation) +
        identifier
  }

  /**
   * @param fps frames per second to DECLARE, or 0 to say nothing.
   *
   * Without it a receiver infers the rate from packet arrival, which is a guess
   * made from the first few: three consecutive connections to one unchanged
   * stream had FFmpeg report 29.83, 29.92 and 90000 fps, the last being the RTP
   * clock, i.e. giving up. The sender knows the answer, so it should say it.
   */
  fun createH265Body(trackVideo: Int, sps: String, pps: String, vps: String, secured: Boolean = false, fps: Int = 0, rotation: Int = 0): String {
    val payload = RtpConstants.payloadType + trackVideo
    val type = if (secured) "UDP/TLS/RTP/SAVPF" else "RTP/AVP"
    val identifier = if (secured) {
      "a=sendonly\r\n" +
          "a=mid:$trackVideo\r\n"
    } else "a=control:streamid=$trackVideo\r\n"
    return "m=video 0 $type ${payload}\r\n" +
        "a=rtpmap:$payload H265/${RtpConstants.clockVideoFrequency}\r\n" +
        "a=fmtp:$payload packetization-mode=1; sprop-sps=$sps; sprop-pps=$pps; sprop-vps=$vps\r\n" +
        frameRateAttr(fps) +
        rotationAttr(rotation) +
        identifier
  }
}