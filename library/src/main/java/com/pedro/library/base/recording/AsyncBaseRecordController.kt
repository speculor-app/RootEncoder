/*
 * Copyright (C) 2026 pedroSG94.
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
package com.pedro.library.base.recording

import android.media.MediaCodec
import com.pedro.common.AudioCodec
import com.pedro.common.BitrateManager
import com.pedro.common.TimeUtils.getCurrentTimeMicro
import com.pedro.common.VideoCodec
import com.pedro.common.clone
import com.pedro.common.frame.MediaFrame
import com.pedro.common.toMediaFrameInfo
import com.pedro.library.base.recording.RecordController.RecordTracks
import com.pedro.library.base.recording.RecordController.RequestKeyFrame
import com.pedro.rtsp.utils.RtpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.concurrent.Volatile
import kotlin.math.max

/**
 * Record async to avoid block the thread used to send frames to protocol module.
 */
abstract class AsyncBaseRecordController : RecordController {

  companion object {
    const val TAG: String = "AsyncRecordController"
    // Big enough for a flushed pre-roll ring (3 s at 240 fps is ~720 video
    // frames plus audio) without blocking the flush behind the muxer.
    private const val CAPACITY = 4096
  }

  @Volatile
  protected var recordStatus: RecordController.Status = RecordController.Status.STOPPED
  protected var listener: RecordController.Listener? = null
  protected var bitrateManager: BitrateManager? = null
  protected var tracks: RecordTracks = RecordTracks.ALL
  protected var myRequestKeyFrame: RequestKeyFrame? = null
  private var videoCodec: VideoCodec = VideoCodec.H264
  private var audioCodec: AudioCodec = AudioCodec.AAC
  private var pauseMoment: Long = 0
  private var pauseTime: Long = 0
  @Volatile
  private var startTs: Long = 0
  private val scope = CoroutineScope(Dispatchers.IO)
  private var muxerChannel: Channel<MediaFrame>? = null
  private var muxerJob: Job? = null

  // ── pre-roll ─────────────────────────────────────────────────────────────
  //
  // While STOPPED every encoded frame is dropped on the floor. With a pre-roll
  // the last [preRollUs] of them are KEPT instead — encoded, so a few MB per
  // second rather than the ~750 MB/s of raw 1080p240 — and on the next
  // startRecord they are written ahead of the live frames. A take then begins
  // before the tap that asked for it: the seconds a warm pipeline spends
  // between the event and the finger are in the file, and the settle gate has
  // nothing to hold, because a ring from a warm encoder has no cold cadence.
  // The ring always begins on a video keyframe so the track can open on it.

  /** Microseconds of encoded frames kept while stopped; 0 keeps none. */
  @Volatile var preRollUs = 0L
  /** Hard cap on the ring, bytes, whatever [preRollUs] asks. */
  @Volatile var preRollMaxBytes = 96L shl 20
  /** Frames written from the ring by the last startRecord; 0 when none were. */
  @Volatile var preRollFlushedFrames = 0
    private set
  /** Raw span the last flushed ring covered, µs, from its first video frame to the tap. */
  @Volatile var preRollFlushedUs = 0L
    private set
  private val ringLock = Any()
  private val ring = ArrayDeque<MediaFrame>()   // raw encoder timestamps, not rebased
  private var ringBytes = 0L
  /** Newest VIDEO timestamp in the ring; how a timeline restart is noticed. */
  private var ringNewestVideoUs = Long.MIN_VALUE
  private var ringPending = false                // start() has run, the ring is not flushed yet

  /** Empty the ring and forget where its timeline was. */
  private fun clearRing() {
    ring.clear()
    ringBytes = 0
    ringNewestVideoUs = Long.MIN_VALUE
  }

  private fun ringAdd(buffer: ByteBuffer, info: MediaCodec.BufferInfo, type: MediaFrame.Type) {
    var raw = info.toMediaFrameInfo()
    // The NAL header is read as well as the flag, as the muxer does: a ring
    // that begins on a frame the muxer will not open the track on is dropped
    // up to a GOP later.
    if (type == MediaFrame.Type.VIDEO && !raw.isKeyFrame && isKeyFrame(buffer)) raw = raw.copy(isKeyFrame = true)
    // A VIDEO frame OLDER than the ring's newest means the ENCODER RESTARTED ITS
    // TIMELINE — every prepareVideo does, and the geometry may have changed with
    // it. The frames behind it describe a configuration that no longer exists:
    // flushed into the next file they arrive ahead of the take carrying
    // timestamps from a dead clock, and [trimRing] can no longer even measure the
    // ring's age, because `newest - head` goes NEGATIVE, reads as younger than
    // [preRollUs], and trims nothing — so the ring grows to [preRollMaxBytes] and
    // all of it is written.
    //
    // Measured on a Magic5 Pro (2026-09-15) switching capture mode between takes:
    // `preRoll=2406f/-224609ms`, a 109 MB ring on a 13-second take, and a
    // manifest placing the video 224 s AFTER the tap. The file played for a
    // moment and then failed, which is what frames of another configuration look
    // like to a decoder.
    //
    // Noticed from INSIDE rather than announced from outside: a restart has many
    // causes — a mode change, a lens change, a codec change, a recovery reopen —
    // and a ring that can see one needs to be told about none of them.
    if (type == MediaFrame.Type.VIDEO) {
      if (raw.timestamp < ringNewestVideoUs) clearRing()
      ringNewestVideoUs = raw.timestamp
    }
    val frame = MediaFrame(buffer.clone(), raw, type)
    ring.addLast(frame)
    ringBytes += raw.size
    // Only a VIDEO timestamp measures the ring's age: the audio encoder's
    // timeline is its own (measured: a P20 Pro's audio ran ~3 s ahead of its
    // video, and trimming on it cut the ring to 1.9 s of an asked 3).
    if (type == MediaFrame.Type.VIDEO) trimRing(raw.timestamp)
  }

  /** Drop everything before the latest video keyframe that is at least [preRollUs] old, and keep under the byte cap. */
  private fun trimRing(newestUs: Long) {
    val head = ring.firstOrNull { it.type == MediaFrame.Type.VIDEO } ?: return
    if (newestUs - head.info.timestamp <= preRollUs && ringBytes <= preRollMaxBytes) return
    var cut = -1
    var i = 0
    for (f in ring) {
      if (f.type == MediaFrame.Type.VIDEO && f.info.isKeyFrame && newestUs - f.info.timestamp >= preRollUs) cut = i
      i++
    }
    repeat(cut.coerceAtLeast(0)) { ringBytes -= ring.removeFirst().info.size }
    // Over the byte cap even so: drop to the next keyframe, and again, until under it.
    while (ringBytes > preRollMaxBytes && ring.size > 1) {
      ringBytes -= ring.removeFirst().info.size
      while (ring.size > 1 && !(ring.first().type == MediaFrame.Type.VIDEO && ring.first().info.isKeyFrame)) ringBytes -= ring.removeFirst().info.size
    }
  }

  /**
   * Called once the muxer exists: the ring goes first, rebased like everything
   * after it. The lock is held only to swap the ring out — never across a send,
   * which can block on the channel while the encoder thread waits to append —
   * and the flushed COUNT is published before the first frame is sent, because
   * the muxer consults it (to bypass the settle gate) on another thread.
   */
  private fun flushPreRoll() {
    val channel = muxerChannel
    var firstVideo = -1L
    var lastVideo = -1L
    var videoFrames = 0
    while (true) {
      val batch: List<MediaFrame>
      synchronized(ringLock) {
        if (ring.isEmpty() || channel == null) {
          ringPending = false
          clearRing()
          batch = emptyList()
        } else {
          batch = ArrayList(ring)
          clearRing()
        }
      }
      if (batch.isEmpty()) break
      for (f in batch) {
        if (f.type == MediaFrame.Type.VIDEO) {
          if (firstVideo < 0) firstVideo = f.info.timestamp
          lastVideo = f.info.timestamp
          videoFrames++
        }
      }
      // ANCHOR THE FILE ON THE RING'S OLDEST FRAME, before a single one is
      // rebased.
      //
      // [updateFormat] takes `startTs` from the first frame it is ever handed
      // and then clamps with `max(0, ts - startTs)`. Handed a ring, "first" is
      // whichever type the deque happened to hold at the head — and audio and
      // video do not arrive on one interleaved order, so every frame OLDER than
      // that one lands at timestamp 0. A run of frames all at 0 is a frozen
      // picture on playback, a file span that reads as a fraction of the take,
      // and sensors that appear to lead the camera by the difference.
      //
      // Measured on a Mi 10T Pro (2026-09-22), 3 s ring, ~14 s take: 56 of 149
      // readable video packets at pts 0.000000 (38 %), video duration 3.13 s
      // against a true 14 s, avg_frame_rate 124.8. The same take with the ring
      // off: 1 of 346 at zero, 11.56 s, 29.92 fps. A P20 Pro flushing a DEEPER
      // ring (126 frames, 4.2 s) was clean, so depth is not the trigger — the
      // Mi 10T reports `beforeFormat=20`, its whole ring, where the P20 reports
      // 0.
      //
      // The minimum over the WHOLE batch, not the oldest video: an audio frame
      // older than the first video frame is just as much a frame the file has
      // to hold, and anchoring past it would flatten that instead.
      val anchor = batch.minOf { it.info.timestamp }
      if (startTs <= 0 || anchor < startTs) startTs = anchor
      // Visible to the muxer before any ring frame reaches it.
      preRollFlushedFrames = videoFrames
      for (f in batch) {
        val rebased = MediaFrame(f.data, updateFormat(f.info), f.type)
        if (!channel!!.trySend(rebased).isSuccess) runBlocking { channel.send(rebased) }
      }
    }
    preRollFlushedFrames = videoFrames
    // A duration, so never negative: with the detection above it cannot happen,
    // and if it ever does the caller must not be handed a number that reads as a
    // ring reaching into the future.
    preRollFlushedUs =
      if (firstVideo >= 0 && lastVideo >= 0) (lastVideo - firstVideo).coerceAtLeast(0L) else 0L
  }

  /** The encoder's own timestamp of a frame the muxer rebased to the file, µs. */
  protected fun rawTimestampUs(rebasedUs: Long): Long = rebasedUs + startTs + pauseTime

  override fun setRequestKeyFrame(requestKeyFrame: RequestKeyFrame?) {
    this.myRequestKeyFrame = requestKeyFrame
  }

  override fun updateInfo(videoCodec: VideoCodec, audioCodec: AudioCodec) {
    this.videoCodec = videoCodec
    this.audioCodec = audioCodec
  }

  override fun setVideoCodec(videoCodec: VideoCodec) {
    this.videoCodec = videoCodec
  }

  override fun setAudioCodec(audioCodec: AudioCodec) {
    this.audioCodec = audioCodec
  }

  override fun getVideoCodec(): VideoCodec = videoCodec
  override fun getAudioCodec(): AudioCodec = audioCodec
  override fun isRunning(): Boolean = recordStatus == RecordController.Status.STARTED || recordStatus == RecordController.Status.RECORDING || recordStatus == RecordController.Status.RESUMED || recordStatus == RecordController.Status.PAUSED
  override fun isRecording(): Boolean = recordStatus == RecordController.Status.RECORDING
  override fun getStatus(): RecordController.Status = recordStatus

  override fun pauseRecord() {
    if (recordStatus == RecordController.Status.RECORDING) {
      pauseMoment = getCurrentTimeMicro()
      recordStatus = RecordController.Status.PAUSED
      listener?.onStatusChange(recordStatus)
    }
  }

  override fun resumeRecord() {
    if (recordStatus == RecordController.Status.PAUSED) {
      pauseTime += getCurrentTimeMicro() - pauseMoment
      recordStatus = RecordController.Status.RESUMED
      listener?.onStatusChange(recordStatus)
    }
  }

  protected fun isKeyFrame(videoBuffer: ByteBuffer): Boolean {
    val header = ByteArray(5)
    if (videoBuffer.remaining() < header.size) return false
    videoBuffer.duplicate().get(header, 0, header.size)
    return when (videoCodec) {
      VideoCodec.AV1 -> {
        //TODO find the way to check it
        false
      }
      VideoCodec.H264 if (header[4].toInt() and 0x1F) == RtpConstants.IDR -> {  //h264
        true
      }
      else -> { //h265
        (videoCodec == VideoCodec.H265
            && ((header[4].toInt() shr 1) and 0x3f) == RtpConstants.IDR_W_DLP
            || ((header[4].toInt() shr 1) and 0x3f) == RtpConstants.IDR_N_LP)
      }
    }
  }

  private fun updateFormat(oldInfo: MediaFrame.Info): MediaFrame.Info {
    if (startTs <= 0) startTs = oldInfo.timestamp
    val ts = max(0, oldInfo.timestamp - startTs - pauseTime)
    return oldInfo.copy(timestamp = ts)
  }

  override fun recordVideo(videoBuffer: ByteBuffer, videoInfo: MediaCodec.BufferInfo) {
    sendFrame(videoBuffer, videoInfo, MediaFrame.Type.VIDEO)
  }

  override fun recordAudio(audioBuffer: ByteBuffer, audioInfo: MediaCodec.BufferInfo) {
    sendFrame(audioBuffer, audioInfo, MediaFrame.Type.AUDIO)
  }

  private fun sendFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo, type: MediaFrame.Type) {
    if (recordStatus == RecordController.Status.STOPPED) {
      if (preRollUs > 0) synchronized(ringLock) { ringAdd(buffer, info, type) }
      return
    }
    // Between start() and the flush a live frame must not overtake the ring.
    synchronized(ringLock) {
      if (ringPending) { ringAdd(buffer, info, type); return }
    }
    val frameInfo = info.toMediaFrameInfo()
    val i = updateFormat(frameInfo)
    muxerChannel?.trySend(MediaFrame(buffer.clone(), i, type))
  }

  override fun startRecord(
    fd: FileDescriptor,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    start(listener, tracks)
    try {
      startRecordImp(fd, listener, tracks)
    } catch (e: Exception) {
      stopRecord()
      throw e
    }
    flushPreRoll()
  }

  override fun startRecord(
    path: String,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    start(listener, tracks)
    try {
      startRecordImp(path, listener, tracks)
    } catch (e: Exception) {
      stopRecord()
      throw e
    }
    flushPreRoll()
  }

  private fun start(
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    clearTimestamp()
    preRollFlushedFrames = 0
    preRollFlushedUs = 0L
    synchronized(ringLock) {
      ringPending = preRollUs > 0
      if (!ringPending) clearRing()
    }
    muxerChannel = Channel(CAPACITY)
    muxerJob = scope.launch {
      val channel = muxerChannel ?: return@launch
      for (frame in channel) onWriteFrame(frame)
    }
    this.tracks = tracks
    this.listener = listener
    recordStatus = RecordController.Status.STARTED
    if (listener != null) {
      bitrateManager = BitrateManager(listener)
      listener.onStatusChange(recordStatus)
    } else {
      bitrateManager = null
    }
  }

  override fun stopRecord() {
    synchronized(ringLock) { ringPending = false }
    muxerChannel?.close()
    muxerChannel = null
    muxerJob?.cancel()
    runBlocking { muxerJob?.join() }
    recordStatus = RecordController.Status.STOPPED
    clearTimestamp()
    myRequestKeyFrame = null
    listener?.onStatusChange(recordStatus)
    stopRecordImp()
  }

  private fun clearTimestamp() {
    pauseMoment = 0
    pauseTime = 0
    startTs = 0
  }

  abstract fun startRecordImp(fd: FileDescriptor, listener: RecordController.Listener?, tracks: RecordTracks)
  abstract fun startRecordImp(path: String, listener: RecordController.Listener?, tracks: RecordTracks)
  abstract fun stopRecordImp()
  abstract suspend fun onWriteFrame(frame: MediaFrame)
}
