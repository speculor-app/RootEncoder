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
package com.pedro.library.util

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import androidx.annotation.RequiresApi
import com.pedro.common.AudioCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.common.toMediaCodecBufferInfo
import com.pedro.library.base.recording.AsyncBaseRecordController
import com.pedro.library.base.recording.RecordController
import com.pedro.library.base.recording.RecordController.RecordTracks
import java.io.FileDescriptor
import java.io.IOException

/**
 * Created by pedro on 08/03/19.
 * Class to control video recording with MediaMuxer.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
class AndroidMuxerRecordController : AsyncBaseRecordController() {
  private var mediaMuxer: MediaMuxer? = null
  private var videoFormat: MediaFormat? = null
  private var audioFormat: MediaFormat? = null
  private val outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
  private var videoTrack: Int = -1
  private var audioTrack: Int = -1

  /**
   * Rotation metadata written into the MP4 (degrees clockwise, 0/90/180/270).
   * For captures the camera feeds to the encoder DIRECTLY the pixels are
   * sensor-oriented — no GL pass rotates them — so display orientation can only
   * travel as metadata, the same way MediaRecorder stores it. Set before
   * startRecord; 0 writes nothing.
   */
  var orientationHint = 0

  /**
   * Whether the FIRST video frame may open the file. Consulted per frame while
   * the file is empty; null admits the first keyframe, as before. A gate that
   * returns false holds the file — nothing is written, nothing requested — and
   * when it opens the next frame re-arms a keyframe request, so the file
   * begins on a fresh keyframe a frame or two later rather than up to a GOP
   * later. Built for a settle gate: the first second of a take arrived at the
   * wrong cadence on every phone measured, and it went straight into the file.
   */
  @Volatile var admitFirstFrame: (() -> Boolean)? = null

  /**
   * The ENCODER'S timestamp of the first video frame WRITTEN, µs — the camera's
   * clock, not the file's — so a caller can anchor the take on it; -1 until one
   * is. With a pre-roll this is EARLIER than the tap.
   */
  @Volatile var firstWrittenPtsUs = -1L
    private set

  private var gateRequester: RecordController.RequestKeyFrame? = null
  private var heldByGate = false

  /** Per-take counters a caller can read back: what the muxer did with the frames it was handed. */
  @Volatile var framesHeldByGate = 0L; private set
  @Volatile var framesBeforeFormat = 0L; private set
  @Volatile var framesWaitingKey = 0L; private set
  @Volatile var framesWritten = 0L; private set
  @Volatile var lastWriteError: String? = null; private set

  override fun setRequestKeyFrame(requestKeyFrame: RecordController.RequestKeyFrame?) {
    gateRequester = requestKeyFrame
    super.setRequestKeyFrame(requestKeyFrame)
  }

  @Throws(IOException::class)
  override fun startRecordImp(
    path: String,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    if (getAudioCodec() != AudioCodec.AAC) {
      throw IOException("Unsupported AudioCodec: " + getAudioCodec().name)
    }
    mediaMuxer = MediaMuxer(path, outputFormat).apply {
      if (orientationHint != 0) setOrientationHint(orientationHint)
    }
    firstWrittenPtsUs = -1L
    heldByGate = false
    framesHeldByGate = 0; framesBeforeFormat = 0; framesWaitingKey = 0; framesWritten = 0; lastWriteError = null
    if (tracks == RecordTracks.AUDIO && audioFormat != null) init()
  }

  @RequiresApi(api = Build.VERSION_CODES.O)
  @Throws(IOException::class)
  override fun startRecordImp(
    fd: FileDescriptor,
    listener: RecordController.Listener?,
    tracks: RecordTracks
  ) {
    if (getAudioCodec() != AudioCodec.AAC) {
      throw IOException("Unsupported AudioCodec: " + getAudioCodec().name)
    }
    mediaMuxer = MediaMuxer(fd, outputFormat).apply {
      if (orientationHint != 0) setOrientationHint(orientationHint)
    }
    if (tracks == RecordTracks.AUDIO && audioFormat != null) init()
  }

  override fun stopRecordImp() {
    videoTrack = -1
    audioTrack = -1
    try {
      mediaMuxer?.stop()
      mediaMuxer?.release()
    } catch (_: Exception) { }
    mediaMuxer = null
  }

  override fun setVideoFormat(videoFormat: MediaFormat) {
    this.videoFormat = videoFormat
  }

  override fun setAudioFormat(audioFormat: MediaFormat) {
    this.audioFormat = audioFormat
    if (tracks == RecordTracks.AUDIO && recordStatus == RecordController.Status.STARTED) {
      init()
    }
  }

  override fun resetFormats() {
    videoFormat = null
    audioFormat = null
  }

  private fun init() {
    if (tracks != RecordTracks.VIDEO) audioTrack = mediaMuxer?.addTrack(audioFormat!!) ?: -1
    mediaMuxer?.start()
    recordStatus = RecordController.Status.RECORDING
    listener?.onStatusChange(recordStatus)
  }

  private suspend fun write(track: Int, frame: MediaFrame) {
    if (track == -1) return
    try {
      mediaMuxer?.writeSampleData(track, frame.data, frame.info.toMediaCodecBufferInfo())
      if (frame.type == MediaFrame.Type.VIDEO && firstWrittenPtsUs < 0) firstWrittenPtsUs = rawTimestampUs(frame.info.timestamp)
      if (frame.type == MediaFrame.Type.VIDEO) framesWritten++
      bitrateManager?.calculateBitrate(frame.info.size * 8L)
    } catch (e: Exception) {
      lastWriteError = e.toString()
      listener?.onError(e)
    }
  }

  override suspend fun onWriteFrame(frame: MediaFrame) {
    when (frame.type) {
      MediaFrame.Type.VIDEO -> {
        if (recordStatus == RecordController.Status.STARTED && (videoFormat == null || (audioFormat == null && tracks != RecordTracks.VIDEO))) framesBeforeFormat++
        if (recordStatus == RecordController.Status.STARTED && videoFormat != null && (audioFormat != null || tracks == RecordTracks.VIDEO)) {
          // A flushed pre-roll comes from a warm encoder already on cadence: nothing to settle.
          if (preRollFlushedFrames == 0 && admitFirstFrame?.invoke() == false) {
            heldByGate = true
            framesHeldByGate++
            return
          }
          if (heldByGate) {
            heldByGate = false
            myRequestKeyFrame = gateRequester
          }
          if (frame.info.isKeyFrame || isKeyFrame(frame.data)) {
            myRequestKeyFrame = null
            videoTrack = mediaMuxer?.addTrack(videoFormat!!) ?: -1
            init()
          } else if (myRequestKeyFrame != null) {
            myRequestKeyFrame?.onRequestKeyFrame()
            myRequestKeyFrame = null
            framesWaitingKey++
          } else framesWaitingKey++
        } else if (recordStatus == RecordController.Status.RESUMED && (frame.info.isKeyFrame
              || isKeyFrame(frame.data))
        ) {
          recordStatus = RecordController.Status.RECORDING
          listener?.onStatusChange(recordStatus)
        }
        if (recordStatus == RecordController.Status.RECORDING && tracks != RecordTracks.AUDIO) {
          write(videoTrack, frame)
        }
      }
      MediaFrame.Type.AUDIO -> {
        if (recordStatus == RecordController.Status.RECORDING && tracks != RecordTracks.VIDEO) {
          write(audioTrack, frame)
        }
      }
    }
  }
}