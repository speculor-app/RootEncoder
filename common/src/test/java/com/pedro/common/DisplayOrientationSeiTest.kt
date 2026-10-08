package com.pedro.common

import com.pedro.common.nal.DisplayOrientationSei
import com.pedro.common.nal.NalReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class DisplayOrientationSeiTest {

  private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

  @Test
  fun `HEVC turned 90 clockwise is the SEI FFmpeg reads as -90`() {
    // 270 anticlockwise (0xC000), persistent, aligned: the exact NAL FFmpeg 8 decoded
    // to a -90 display matrix on every frame, on software, Vulkan and D3D11 decode.
    assertArrayEquals(
      bytes(0, 0, 0, 1, 0x4E, 0x01, 0x2F, 0x03, 0x18, 0x00, 0x18, 0x80),
      DisplayOrientationSei.build(VideoCodec.H265, 90),
    )
  }

  @Test
  fun `H264 carries a repetition period and an extension flag instead of persistence`() {
    // cancel 0, flips 0, 0xC000, repetition_period ue(1) = 010, extension 0, align 1
    assertArrayEquals(
      bytes(0, 0, 0, 1, 0x06, 0x2F, 0x03, 0x18, 0x00, 0x09, 0x80),
      DisplayOrientationSei.build(VideoCodec.H264, 90),
    )
  }

  @Test
  fun `nothing is declared for an upright stream or a codec without SEI`() {
    assertNull(DisplayOrientationSei.build(VideoCodec.H265, 0))
    assertNull(DisplayOrientationSei.build(VideoCodec.H265, 45))
    assertNull(DisplayOrientationSei.build(VideoCodec.AV1, 90))
  }

  @Test
  fun `the packetiser keeps it while stripping other SEI`() {
    val sei = DisplayOrientationSei.build(VideoCodec.H265, 270)!!
    val other = bytes(0, 0, 0, 1, 0x4E, 0x01, 0x04, 0x01, 0x00, 0x80)    // payload type 4
    val slice = bytes(0, 0, 0, 1, 0x26, 0x01, 0xAF, 0x00)
    val nals = NalReader.extractNals(ByteBuffer.wrap(sei + other + slice), VideoCodec.H265, true)
    assertEquals(2, nals.size)
    assertEquals(0x2F, nals[0].get(2).toInt() and 0xFF)
  }
}
