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

import android.os.SystemClock
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.base.BaseSender
import com.pedro.common.frame.MediaFrame
import com.pedro.common.nal.CaptureTimeSei
import com.pedro.common.onMainThread
import com.pedro.common.removeInfo
import com.pedro.common.socket.base.SocketType
import com.pedro.common.socket.base.TcpStreamSocket
import com.pedro.common.validMessage
import com.pedro.rtsp.rtcp.BaseSenderReport
import com.pedro.rtsp.rtp.packets.AacPacket
import com.pedro.rtsp.rtp.packets.Av1Packet
import com.pedro.rtsp.rtp.packets.BasePacket
import com.pedro.rtsp.rtp.packets.G711Packet
import com.pedro.rtsp.rtp.packets.H264Packet
import com.pedro.rtsp.rtp.packets.H265Packet
import com.pedro.rtsp.rtp.packets.OpusPacket
import com.pedro.rtsp.rtp.sockets.BaseRtpSocket
import com.pedro.rtsp.rtp.sockets.RtpSocketTcp
import com.pedro.rtsp.rtsp.commands.CommandsManager
import com.pedro.rtsp.utils.RtpConstants
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Random

/**
 * Created by pedro on 7/11/18.
 */
class RtspSender(
  connectChecker: ConnectChecker,
  private val commandsManager: CommandsManager
): BaseSender(connectChecker, "RtspSender") {

  /**
   * Note when each video frame was handed over, before it enters the send queue.
   *
   * This has to happen here and not in the packetiser: getRtpPackets runs after
   * queue.take(), so a reading taken there is a send-time reading and reproduces exactly
   * the error the Sender Report hook exists to remove. The RTP timestamp is derived the
   * same way the packetiser derives it, from the same field, so the two agree by
   * construction rather than by assuming anything about shared origins.
   */
  override fun sendMediaFrame(mediaFrame: MediaFrame) {
    if (mediaFrame.type != MediaFrame.Type.VIDEO) {
      super.sendMediaFrame(mediaFrame)
      return
    }
    val captureNs = SystemClock.elapsedRealtimeNanos()
    // This sender's own report: frame timestamps are rebased per connection, so the
    // pairing is only meaningful to the client it came from.
    baseSenderReport?.noteVideoTimestamp(
      mediaFrame.info.timestamp * RtpConstants.clockVideoFrequency / 1_000_000L,
      captureNs,
    )
    super.sendMediaFrame(withCaptureTimeSei(mediaFrame, captureNs))
  }

  /**
   * Prepend a capture-time SEI to the access unit, taken here because this is the last
   * point before the send queue — a reading taken after it measures how long the frame
   * waited, not when it was captured.
   *
   * Returns the frame unchanged when no provider is installed, so the default build
   * emits an unmodified bitstream.
   */
  private fun withCaptureTimeSei(mediaFrame: MediaFrame, captureNs: Long): MediaFrame {
    val provider = BaseSenderReport.ntpClockProvider
    if (provider === BaseSenderReport.deviceClockProvider) return mediaFrame
    val sei = CaptureTimeSei.build(commandsManager.videoCodec, provider(captureNs))
    if (sei == null) {
      if (!seiUnsupportedLogged) {
        seiUnsupportedLogged = true
        Log.w(TAG, "capture-time SEI unsupported for codec ${commandsManager.videoCodec}")
      }
      return mediaFrame
    }
    if (!seiLogged) {
      seiLogged = true
      Log.i(TAG, "capture-time SEI: ${sei.size} bytes, codec ${commandsManager.videoCodec}")
    }
    // Build from the payload Info describes, not from the whole buffer, and restate the
    // size. The packetiser slices by offset/size, so reusing the original Info would cut
    // exactly the SEI's length off the tail of every frame — which decodes as a stream
    // that only advances on keyframes while still reporting full frame rate.
    val payload = mediaFrame.data.removeInfo(mediaFrame.info)
    val merged = ByteBuffer.allocate(sei.size + payload.remaining())
    merged.put(sei)
    merged.put(payload)
    merged.flip()
    return MediaFrame(
      merged,
      mediaFrame.info.copy(offset = 0, size = merged.remaining()),
      mediaFrame.type,
    )
  }

  private var seiLogged = false
  private var seiUnsupportedLogged = false

  private var videoPacket: BasePacket = H264Packet(commandsManager.rtpTracks.trackVideo)
  private var audioPacket: BasePacket = AacPacket(commandsManager.rtpTracks.trackAudio)
  private var rtpSocket: BaseRtpSocket? = null
  private var baseSenderReport: BaseSenderReport? = null

  @Throws(IOException::class)
  fun setSocketsInfo(
    socketType: SocketType,
    protocol: Protocol, host: String, timeout: Long,
    videoSourcePorts: Array<Int?>, audioSourcePorts: Array<Int?>,
    videoServerPorts: Array<Int?>, audioServerPorts: Array<Int?>,
  ) {
    rtpSocket = BaseRtpSocket.getInstance(commandsManager.rtpTracks, socketType, protocol, host, timeout, videoSourcePorts[0], audioSourcePorts[0], videoServerPorts[0], audioServerPorts[0])
    baseSenderReport = BaseSenderReport.getInstance(commandsManager.rtpTracks, socketType, protocol, host, timeout, videoSourcePorts[1], audioSourcePorts[1], videoServerPorts[1], audioServerPorts[1])
  }

  @Throws(IOException::class)
  suspend fun setSocket(socket: TcpStreamSocket) {
    rtpSocket?.setSocket(socket)
    baseSenderReport?.setSocket(socket)
  }

  override fun setVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
    videoPacket = when (commandsManager.videoCodec) {
      VideoCodec.H264 -> {
        if (pps == null) throw IllegalArgumentException("pps can't be null with h264")
        H264Packet(commandsManager.rtpTracks.trackVideo).apply { sendVideoInfo(sps, pps) }
      }
      VideoCodec.H265 -> {
        if (vps == null || pps == null) throw IllegalArgumentException("pps or vps can't be null with h265")
        H265Packet(commandsManager.rtpTracks.trackVideo).apply { sendVideoInfo(sps, pps, vps) }
      }
      VideoCodec.AV1 -> Av1Packet(commandsManager.rtpTracks.trackVideo)
    }
  }

  override fun setAudioInfo(sampleRate: Int, isStereo: Boolean) {
    audioPacket = when (commandsManager.audioCodec) {
      AudioCodec.G711 -> G711Packet(commandsManager.rtpTracks.trackAudio).apply { setAudioInfo(sampleRate) }
      AudioCodec.AAC -> AacPacket(commandsManager.rtpTracks.trackAudio).apply { setAudioInfo(sampleRate) }
      AudioCodec.OPUS -> OpusPacket(commandsManager.rtpTracks.trackAudio).apply { setAudioInfo(sampleRate) }
    }
  }

  override suspend fun onRun() {
    val ssrcVideo = Random().nextInt().toLong()
    val ssrcAudio = Random().nextInt().toLong()
    baseSenderReport?.setSSRC(ssrcVideo, ssrcAudio)
    videoPacket.setSSRC(ssrcVideo)
    audioPacket.setSSRC(ssrcAudio)
    val isTcp = rtpSocket is RtpSocketTcp
    while (scope.isActive && running) {
      val error = runCatching {
        val mediaFrame = runInterruptible { queue.take() }
        getRtpPackets(mediaFrame) { rtpFrames ->
          var size = 0L
          var isVideo = false
          rtpFrames.forEach { rtpFrame ->
            rtpSocket?.sendFrame(rtpFrame)
            //4 is tcp header length
            val packetSize = (if (isTcp) rtpFrame.length + 4 else rtpFrame.length).toLong()
            bytesSend.addAndGet(packetSize)
            bytesSendPerSecond.addAndGet(packetSize)
            size += packetSize
            isVideo = rtpFrame.isVideoFrame(commandsManager.rtpTracks.trackVideo)
            if (isVideo) videoFramesSent.incrementAndGet()
            else audioFramesSent.incrementAndGet()
            if (baseSenderReport?.update(rtpFrame) == true) {
              //4 is tcp header length
              val reportSize = (if (isTcp) RtpConstants.REPORT_PACKET_LENGTH + 4 else RtpConstants.REPORT_PACKET_LENGTH).toLong()
              bytesSend.addAndGet(reportSize)
              bytesSendPerSecond.addAndGet(reportSize)
              if (isEnableLogs) Log.i(TAG, "wrote report")
            }
          }
          rtpSocket?.flush()
          if (isEnableLogs) {
            val type = if (isVideo) "Video" else "Audio"
            Log.i(TAG, "wrote $type packet, size $size")
          }
        }
      }.exceptionOrNull()
      if (error != null) {
        onMainThread {
          connectChecker.onConnectionFailed("Error send packet, ${error.validMessage()}")
        }
        Log.e(TAG, "send error: ", error)
        running = false
        return
      }
    }
  }

  override suspend fun stopImp(clear: Boolean) {
    baseSenderReport?.reset()
    baseSenderReport?.close()
    rtpSocket?.close()
    audioPacket.reset()
    videoPacket.reset()
  }

  private suspend fun getRtpPackets(mediaFrame: MediaFrame?, callback: suspend (List<RtpFrame>) -> Unit) {
    if (mediaFrame == null) return
    when (mediaFrame.type) {
      MediaFrame.Type.VIDEO -> videoPacket.createAndSendPacket(mediaFrame) { callback(it) }
      MediaFrame.Type.AUDIO -> audioPacket.createAndSendPacket(mediaFrame) { callback(it) }
    }
  }
}