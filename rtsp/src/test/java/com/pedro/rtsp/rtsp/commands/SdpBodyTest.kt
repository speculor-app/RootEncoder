package com.pedro.rtsp.rtsp.commands

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared attributes are the receiver's only truth about a stream it has
 * not yet decoded, so what goes on the wire — and what stays off it — is worth
 * pinning: a zero must say NOTHING, because declaring "framerate:0" or
 * "rotation:0" reads as information when it is actually ignorance or the
 * default.
 */
class SdpBodyTest {

  @Test
  fun `h264 body declares framerate and rotation when known`() {
    val body = SdpBody.createH264Body(0, "sps", "pps", fps = 240, rotation = 90)
    assertTrue(body.contains("a=framerate:240\r\n"))
    assertTrue(body.contains("a=x-video-rotation:90\r\n"))
  }

  @Test
  fun `h265 body declares framerate and rotation when known`() {
    val body = SdpBody.createH265Body(0, "sps", "pps", "vps", fps = 120, rotation = 270)
    assertTrue(body.contains("a=framerate:120\r\n"))
    assertTrue(body.contains("a=x-video-rotation:270\r\n"))
  }

  @Test
  fun `zero declares nothing`() {
    val body = SdpBody.createH265Body(0, "sps", "pps", "vps", fps = 0, rotation = 0)
    assertFalse(body.contains("a=framerate"))
    assertFalse(body.contains("a=x-video-rotation"))
  }
}
