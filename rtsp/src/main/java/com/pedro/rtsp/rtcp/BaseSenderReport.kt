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

package com.pedro.rtsp.rtcp

import com.pedro.common.TimeUtils
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.StreamSocket
import com.pedro.common.socket.base.TcpStreamSocket
import com.pedro.common.socket.base.UdpStreamSocket
import com.pedro.common.toUInt32
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.utils.CryptoProperties
import com.pedro.rtsp.utils.CryptoUtils
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.rtsp.utils.RtpTracks
import com.pedro.rtsp.utils.setLong
import java.io.IOException

/**
 * Created by pedro on 7/11/18.
 */
abstract class BaseSenderReport internal constructor(private val rtpTracks: RtpTracks) {

  private val interval: Long = 3000
  private val videoBuffer = ByteArray(RtpConstants.REPORT_PACKET_LENGTH)
  private val audioBuffer = ByteArray(RtpConstants.REPORT_PACKET_LENGTH)
  private var videoTime: Long = 0
  private var audioTime: Long = 0
  private var videoPacketCount = 0L
  private var videoOctetCount = 0L
  private var audioPacketCount = 0L
  private var audioOctetCount = 0L
  private var srtcpVideoIndex = 0
  private var srtcpAudioIndex = 0
  private var cryptoUtils: CryptoUtils? = null

  private var ssrcVideo = 0L
  private var ssrcAudio = 0L

  // RTP timestamp of the most recently queued video frame, paired with the
  // CLOCK_BOOTTIME reading taken as it was handed over.
  //
  // Per instance, never shared. Each connected client rebases frame timestamps by its
  // own connection time (ServerClient passes startTs into toMediaFrameInfo), so one
  // client's RTP timeline says nothing about another's — holding this globally makes
  // every report after the second connection wrong by the gap between the two
  // connections, as a stable offset that looks exactly like latency.
  //
  // Written on the encoder thread, read on the sender thread; @Volatile on each is
  // enough, because a torn pair costs one report's accuracy and never correctness, and
  // locking a per-frame path to protect a 3-second report is the wrong trade.
  @Volatile
  private var lastVideoRtpTs: Long = 0

  @Volatile
  private var lastVideoWallNs: Long = 0

  /**
   * Record when a video frame was handed to the sender, before it enters the send queue.
   * The RTP timestamp must be derived exactly as the packetiser derives it, from the same
   * frame, so the two describe the same instant.
   */
  fun noteVideoTimestamp(rtpTs: Long, elapsedRealtimeNs: Long) {
    lastVideoRtpTs = rtpTs
    lastVideoWallNs = elapsedRealtimeNs
  }

  /** Capture instant of [rtpTs] on CLOCK_BOOTTIME, or 0 before any frame is seen. */
  private fun captureTimeOf(rtpTs: Long): Long {
    val wall = lastVideoWallNs
    if (wall == 0L) return 0
    val deltaNs = (rtpTs - lastVideoRtpTs) * 1_000_000_000L / RtpConstants.clockVideoFrequency
    return wall + deltaNs
  }

  companion object {
    /**
     * Seconds between the NTP epoch (1900-01-01) and the Unix epoch (1970-01-01).
     *
     * RFC 3550 §4 defines the Sender Report's NTP timestamp field on the 1900 epoch,
     * while every clock available here counts from 1970. Omitting this offset makes a
     * receiver that honours the field place the stream seventy years in the past —
     * FFmpeg reports a `start_time_realtime` of -2208988800 s and any sanity check
     * rejects it.
     */
    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

    /**
     * The default, and the marker for "no caller has supplied a clock".
     *
     * Epoch wall clock, not [TimeUtils.getCurrentTimeNano]: that reads
     * SystemClock.elapsedRealtimeNanos — nanoseconds since BOOT — and stamping it into
     * an epoch field advertised the stream's capture time as 1970-plus-uptime. A
     * receiver honouring the field measured such a stream fifty-six years out and
     * rejected it; one that did not would have silently mis-fused it.
     */
    @JvmStatic
    val deviceClockProvider: (Long) -> Long = { TimeUtils.getCurrentTimeMillis() * 1_000_000L }


    /**
     * Wall-clock source for the NTP field of RTCP Sender Reports, in nanoseconds since
     * the Unix epoch. Defaults to the device clock read now, which is the historical
     * behaviour, so nothing changes unless a caller replaces it.
     *
     * The Sender Report is what lets a receiver map RTP timestamps back to the time a
     * frame was CAPTURED, and two things spoil that. One is the clock: a device clock
     * measured 250 ms off a disciplined reference, enough to place video a quarter
     * second away from other sensors on the same device. The other is *when* the clock
     * is read — reports are written from the sender loop, after the frame has waited in
     * the send queue, so pairing "now" with a frame encoded earlier overstates its age
     * by the whole encode-and-queue latency. Measured on one 4K HEVC stream that was a
     * further 680 ms.
     *
     * The parameter is when this report's frame was captured, as a CLOCK_BOOTTIME
     * reading in nanoseconds, or 0 if no frame has been packetised yet. A caller holding
     * a disciplined clock converts that instead of reading its own clock, which fixes
     * both problems at once.
     *
     * Return 0 to say "no trustworthy time yet" — while a time source is still settling,
     * or after it has lost its master and started drifting. NO Sender Report is emitted
     * for that interval (and no capture-time SEI), so a receiver anchors the stream on
     * its own arrival clock: late, but never wrong. A receiver cannot distinguish an
     * undisciplined absolute time from a disciplined one, so sending any fallback here
     * hands it a confident wrong answer — the previous fallback stamped a boot-relative
     * reading into the epoch field, which advertised the stream as captured in 1970.
     * Reporting resumes automatically at the first interval the provider returns a
     * time again.
     *
     * Applied to the video track only: audio is packetised on a different capture
     * timeline, so the same conversion would not be valid for it.
     *
     * Must be cheap and non-blocking: called on the sender path once per report
     * interval.
     */
    @JvmStatic
    var ntpClockProvider: (captureElapsedRealtimeNs: Long) -> Long = deviceClockProvider

    @JvmStatic
    fun getInstance(
      rtpTracks: RtpTracks,
      socketType: SocketType,
      protocol: Protocol, host: String,
      timeout: Long,
      videoSourcePort: Int?, audioSourcePort: Int?,
      videoServerPort: Int?, audioServerPort: Int?,
    ): BaseSenderReport {
      return if (protocol === Protocol.TCP) {
        SenderReportTcp(rtpTracks)
      } else {
        val videoSocket = if (videoServerPort != null) {
          StreamSocket.createUdpSocket(socketType, host, videoServerPort, timeout, sourcePort = videoSourcePort)
        } else null
        val audioSocket = if (audioServerPort != null) {
          StreamSocket.createUdpSocket(socketType, host, audioServerPort, timeout, sourcePort = audioSourcePort)
        } else null
        SenderReportUdp(rtpTracks, videoSocket, audioSocket)
      }
    }

    @JvmStatic
    fun getInstance(rtpTracks: RtpTracks, socket: UdpStreamSocket): BaseSenderReport {
      return SenderReportUdpMux(rtpTracks, socket)
    }
  }

  init {
    /*							     Version(2)  Padding(0)					 					*/
    /*									 ^		  ^			PT = 0	    						*/
    /*									 |		  |				^								*/
    /*									 | --------			 	|								*/
    /*									 | |---------------------								*/
    /*									 | ||													*/
    /*									 | ||													*/
    videoBuffer[0] = 0x80.toByte()
    audioBuffer[0] = 0x80.toByte()

    /* Packet Type PT */
    videoBuffer[1] = 200.toByte()
    audioBuffer[1] = 200.toByte()

    /* Byte 2,3          ->  Length		                     */
    videoBuffer.setLong(RtpConstants.REPORT_PACKET_LENGTH / 4 - 1L, 2, 4)
    audioBuffer.setLong(RtpConstants.REPORT_PACKET_LENGTH / 4 - 1L, 2, 4)
    /* Byte 4,5,6,7      ->  SSRC                            */
    /* Byte 8,9,10,11    ->  NTP timestamp hb				 */
    /* Byte 12,13,14,15  ->  NTP timestamp lb				 */
    /* Byte 16,17,18,19  ->  RTP timestamp		             */
    /* Byte 20,21,22,23  ->  packet count				 	 */
    /* Byte 24,25,26,27  ->  octet count			         */
  }

  fun setSSRC(ssrcVideo: Long, ssrcAudio: Long) {
    this.ssrcVideo = ssrcVideo
    this.ssrcAudio = ssrcAudio
    videoBuffer.setLong(ssrcVideo, 4, 8)
    audioBuffer.setLong(ssrcAudio, 4, 8)
  }

  fun setCrypto(properties: CryptoProperties) {
    cryptoUtils = CryptoUtils(properties)
  }

  @Throws(IOException::class)
  abstract suspend fun setSocket(socket: TcpStreamSocket)

  @Throws(IOException::class)
  suspend fun update(rtpFrame: RtpFrame): Boolean {
    return if (rtpFrame.channelIdentifier == rtpTracks.trackVideo) {
      updateVideo(rtpFrame)
    } else {
      updateAudio(rtpFrame)
    }
  }

  @Throws(IOException::class)
  abstract suspend fun sendReport(buffer: ByteArray, rtpFrame: RtpFrame)

  @Throws(IOException::class)
  private suspend fun updateVideo(rtpFrame: RtpFrame): Boolean {
    videoPacketCount++
    videoOctetCount += rtpFrame.length
    videoBuffer.setLong(videoPacketCount, 20, 24)
    videoBuffer.setLong(videoOctetCount, 24, 28)
    if (TimeUtils.getCurrentTimeMillis() - videoTime >= interval) {
      videoTime = TimeUtils.getCurrentTimeMillis()
      // 0 from the provider means it has no trustworthy time yet — send NOTHING rather
      // than something wrong. The old fallback stamped a boot-relative reading into the
      // epoch field, so an undisciplined phone advertised its capture epoch as
      // 1970-plus-uptime and a receiver's sanity gate measured it 56 years out. With no
      // report the receiver anchors on arrival (late, never wrong); the counters keep
      // accumulating and the next interval retries, so reporting resumes by itself the
      // moment the clock disciplines.
      val supplied = ntpClockProvider(captureTimeOf(rtpFrame.timeStamp))
      if (supplied <= 0L) return false
      setData(videoBuffer, supplied, rtpFrame.timeStamp)
      cryptoUtils?.let {
        sendReport(encrypt(videoBuffer, srtcpVideoIndex++, ssrcVideo, it), rtpFrame)
      } ?: sendReport(videoBuffer, rtpFrame)
      return true
    }
    return false
  }

  @Throws(IOException::class)
  private suspend fun updateAudio(rtpFrame: RtpFrame): Boolean {
    audioPacketCount++
    audioOctetCount += rtpFrame.length
    audioBuffer.setLong(audioPacketCount, 20, 24)
    audioBuffer.setLong(audioOctetCount, 24, 28)
    if (TimeUtils.getCurrentTimeMillis() - audioTime >= interval) {
      audioTime = TimeUtils.getCurrentTimeMillis()
      // Device wall clock, not ntpClockProvider: audio presentation times are on the
      // capture timeline of AudioRecord, which the provider's conversion does not
      // describe. Epoch clock, not getCurrentTimeNano — see deviceClockProvider.
      setData(audioBuffer, TimeUtils.getCurrentTimeMillis() * 1_000_000L, rtpFrame.timeStamp)
      cryptoUtils?.let {
        sendReport(encrypt(audioBuffer, srtcpAudioIndex++, ssrcAudio, it), rtpFrame)
      } ?: sendReport(audioBuffer, rtpFrame)
      return true
    }
    return false
  }

  fun reset() {
    videoOctetCount = 0
    videoPacketCount = 0
    audioOctetCount = 0
    audioPacketCount = 0
    audioTime = 0
    videoTime = 0
    srtcpVideoIndex = 0
    srtcpAudioIndex  = 0
    videoBuffer.setLong(videoPacketCount, 20, 24)
    videoBuffer.setLong(videoOctetCount, 24, 28)
    audioBuffer.setLong(audioPacketCount, 20, 24)
    audioBuffer.setLong(audioOctetCount, 24, 28)
  }

  abstract suspend fun close()

  private fun setData(buffer: ByteArray, ntpts: Long, rtpts: Long) {
    val hb = ntpts / 1000000000
    val lb = (ntpts - hb * 1000000000) * 4294967296L / 1000000000
    buffer.setLong(hb + NTP_EPOCH_OFFSET_SECONDS, 8, 12)
    buffer.setLong(lb, 12, 16)
    buffer.setLong(rtpts, 16, 20)
  }

  private fun encrypt(
    buffer: ByteArray, index: Int, ssrc: Long, cryptoUtils: CryptoUtils,
  ): ByteArray {
    var encryptedData = buffer
    val i = index or (1 shl 31)
    encryptedData = encryptedData.plus(i.toUInt32())
    val payload = encryptedData.copyOfRange(8, encryptedData.size - 4)
    val encryptPayload = cryptoUtils.encrypt(payload, getIvData(ssrc, index, cryptoUtils))
    encryptPayload.copyInto(encryptedData, 8)
    val hmac = cryptoUtils.calculateHmac(encryptedData)
    return encryptedData.plus(hmac)
  }

  private fun getIvData(ssrc: Long, index: Int, cryptoUtils: CryptoUtils): ByteArray {
    return cryptoUtils.generateIv(ssrc, index.toLong())
  }
}