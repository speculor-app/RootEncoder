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
import com.pedro.rtsp.Utils
import com.pedro.rtsp.rtsp.Protocol
import com.pedro.rtsp.rtsp.RtpFrame
import com.pedro.rtsp.utils.RtpConstants
import com.pedro.rtsp.utils.RtpTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Created by pedro on 9/9/23.
 */
@RunWith(MockitoJUnitRunner::class)
class RtcpReportTest {

  @Mock
  private lateinit var udpSocket: UdpStreamSocket
  @Mock
  private lateinit var tcpSocket: TcpStreamSocket

  private val timeUtilsMocked = Mockito.mockStatic(TimeUtils::class.java)
  private var fakeTime = 7502849023L

  @Before
  fun setup() {
    timeUtilsMocked.`when`<Long>(TimeUtils::getCurrentTimeMillis).then { fakeTime }
  }

  @After
  fun teardown() {
    fakeTime = 7502849023L
  }

  @Test
  fun `GIVEN multiple video or audio rtp frames WHEN update rtcp tcp send THEN send only 1 of video and 1 of audio each 3 seconds`() = runTest {
    Utils.useStatics(listOf(timeUtilsMocked)) {
      val rtpTracks = RtpTracks()
      val senderReportTcp = BaseSenderReport.getInstance(rtpTracks, SocketType.JAVA, Protocol.TCP, "127.0.0.1",
        StreamSocket.DEFAULT_TIMEOUT, 0, 1, 2, 3)
      senderReportTcp.setSocket(tcpSocket)
      senderReportTcp.setSSRC(0, 1)
      val fakeFrameVideo = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, rtpTracks.trackVideo)
      val fakeFrameAudio = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, rtpTracks.trackAudio)

      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportTcp.update(frame)
      }
      val resultValue = argumentCaptor<ByteArray>()
      withContext(Dispatchers.IO) {
        verify(tcpSocket, times((2))).write(resultValue.capture())
      }
      fakeTime += 3_000 //wait until next interval
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportTcp.update(frame)
      }
      withContext(Dispatchers.IO) {
        verify(tcpSocket, times((4))).write(resultValue.capture())
      }
    }
  }

  @Test
  fun `GIVEN a clock provider with no trustworthy time WHEN update video THEN no report is sent until it recovers`() = runTest {
    Utils.useStatics(listOf(timeUtilsMocked)) {
      val rtpTracks = RtpTracks()
      val senderReportTcp = BaseSenderReport.getInstance(rtpTracks, SocketType.JAVA, Protocol.TCP, "127.0.0.1",
        StreamSocket.DEFAULT_TIMEOUT, 0, 1, 2, 3)
      senderReportTcp.setSocket(tcpSocket)
      senderReportTcp.setSSRC(0, 1)
      val fakeFrameVideo = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, rtpTracks.trackVideo)
      val previous = BaseSenderReport.ntpClockProvider
      try {
        // Undisciplined: the provider says "no trustworthy time yet". The old fallback
        // stamped a boot-relative reading into the epoch field (a stream captured "in
        // 1970"); the contract now is silence — the receiver anchors on arrival.
        BaseSenderReport.ntpClockProvider = { 0L }
        (0..10).forEach { _ -> senderReportTcp.update(fakeFrameVideo) }
        // sendReport writes the 4-byte interleaved header via write(ByteArray) and the
        // report body via write(buffer, offset, size) — verify BOTH stayed silent.
        val header = argumentCaptor<ByteArray>()
        val body = argumentCaptor<ByteArray>()
        withContext(Dispatchers.IO) {
          verify(tcpSocket, times(0)).write(header.capture())
          verify(tcpSocket, times(0)).write(
            body.capture(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }

        // The clock disciplines: reporting resumes at the next interval, carrying the
        // supplied epoch time (plus the RFC 3550 1900-epoch offset), nothing else.
        val epochNs = 1_700_000_000_000_000_000L
        BaseSenderReport.ntpClockProvider = { epochNs }
        fakeTime += 3_000
        (0..10).forEach { _ -> senderReportTcp.update(fakeFrameVideo) }
        withContext(Dispatchers.IO) {
          verify(tcpSocket, times(1)).write(
            body.capture(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        }
        val report = body.firstValue
        val ntpSeconds =
          ((report[8].toLong() and 0xFF) shl 24) or
          ((report[9].toLong() and 0xFF) shl 16) or
          ((report[10].toLong() and 0xFF) shl 8) or
          (report[11].toLong() and 0xFF)
        org.junit.Assert.assertEquals(1_700_000_000L + 2_208_988_800L, ntpSeconds)
      } finally {
        BaseSenderReport.ntpClockProvider = previous
      }
    }
  }

  @Test
  fun `GIVEN multiple video or audio rtp frames WHEN update rtcp udp send THEN send only 1 of video and 1 of audio each 3 seconds`() = runTest {
    Utils.useStatics(listOf(timeUtilsMocked)) {
      val rtpTracks = RtpTracks()
      val senderReportUdp = SenderReportUdp(rtpTracks, udpSocket, udpSocket)
      senderReportUdp.setSocket(tcpSocket)
      senderReportUdp.setSSRC(0, 1)
      val fakeFrameVideo = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, rtpTracks.trackVideo)
      val fakeFrameAudio = RtpFrame(byteArrayOf(0x00, 0x00, 0x00), 0, 3, rtpTracks.trackAudio)
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportUdp.update(frame)
      }
      val resultValue = argumentCaptor<ByteArray>()
      withContext(Dispatchers.IO) {
        verify(udpSocket, times((2))).write(resultValue.capture())
      }
      fakeTime += 3_000 //wait until next interval
      (0..10).forEach { value ->
        val frame = if (value % 2 == 0) fakeFrameVideo else fakeFrameAudio
        senderReportUdp.update(frame)
      }
      withContext(Dispatchers.IO) {
        verify(udpSocket, times((4))).write(resultValue.capture())
      }
    }
  }
}