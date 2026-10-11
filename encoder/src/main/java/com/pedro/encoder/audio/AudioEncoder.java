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

package com.pedro.encoder.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.pedro.common.CaptureLatency;
import com.pedro.encoder.BaseEncoder;
import com.pedro.encoder.Frame;
import com.pedro.encoder.GetFrame;
import com.pedro.encoder.TimestampMode;
import com.pedro.encoder.input.audio.GetMicrophoneData;
import com.pedro.encoder.utils.CodecUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Created by pedro on 19/01/17.
 *
 * Encode PCM audio data to ACC and return in a callback
 */

public class AudioEncoder extends BaseEncoder implements GetMicrophoneData {

  private final GetAudioData getAudioData;
  private int bitRate = 64 * 1024;  //in kbps
  private int sampleRate = 32000; //in hz
  public static final int inputSize = 8192;
  private boolean isStereo = true;
  private GetFrame getFrame;
  // Input buffers the codec has offered while no PCM was waiting, filled as PCM arrives.
  // Touched only on the callback thread.
  private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
  private long tsBuffer = 0;

  public AudioEncoder(GetAudioData getAudioData) {
    this.getAudioData = getAudioData;
    typeError = CodecUtil.CodecTypeError.AUDIO_CODEC;
    type = CodecUtil.AAC_MIME;
    TAG = "AudioEncoder";
  }

  /**
   * Prepare encoder with custom parameters
   */
  public boolean prepareAudioEncoder(int bitRate, int sampleRate, boolean isStereo) {
    if (prepared) stop();

    this.bitRate = bitRate;
    this.sampleRate = sampleRate;
    this.isStereo = isStereo;
    isBufferMode = true;

    try {
      if (type.equals(CodecUtil.G711_MIME)) {
        g711Codec.configure(sampleRate, isStereo ? 2 : 1);
        setCallback();
        running = false;
        Log.i(TAG, "prepared");
        prepared = true;
        return true;
      }
      MediaCodecInfo encoder = chooseEncoder(type);
      if (encoder != null) {
        Log.i(TAG, "Encoder selected " + encoder.getName());
        codec = MediaCodec.createByCodecName(encoder.getName());
      } else {
        Log.e(TAG, "Valid encoder not found");
        return false;
      }

      int channelCount = (isStereo) ? 2 : 1;
      MediaFormat audioFormat = MediaFormat.createAudioFormat(type, sampleRate, channelCount);
      audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
      audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputSize);
      audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
          MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      setCallback();
      codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      running = false;
      Log.i(TAG, "prepared");
      prepared = true;
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Create AudioEncoder failed.", e);
      this.stop();
      return false;
    }
  }

  public void setGetFrame(GetFrame getFrame) {
    this.getFrame = getFrame;
  }

  /**
   * Prepare encoder with default parameters
   */
  public boolean prepareAudioEncoder() {
    return prepareAudioEncoder(bitRate, sampleRate, isStereo);
  }

  @Override
  public void start(boolean resetTs) {
    if (resetTs) tsBuffer = 0;
    shouldReset = resetTs;
    // Buffer indices belong to the codec run that offered them.
    if (handler != null) handler.post(freeInputs::clear);
    Log.i(TAG, "started");
  }

  @Override
  protected void stopImp() {
    Log.i(TAG, "stopped");
  }

  @Override
  public boolean reset() {
    stop(false);
    boolean result = prepareAudioEncoder(bitRate, sampleRate, isStereo);
    if (!result) return false;
    restart();
    return true;
  }

  @Override
  protected Frame getInputFrame() throws InterruptedException {
    return getFrame != null ? getFrame.getInputFrame() : queue.take();
  }

  @Override
  protected long calculatePts(Frame frame, long presentTimeUs) {
    long pts;
    long clockPts = Math.max(0, frame.getTimeStamp() - presentTimeUs);
    if (timestampMode == TimestampMode.CLOCK) {
      pts = clockPts;
    } else {
      if (tsBuffer == 0) tsBuffer = clockPts;
      int channels = isStereo ? 2 : 1;
      tsBuffer += (long)((double)frame.getSize() / (sampleRate * channels * 2L) * 1_000_000L);
      if (clockPts - tsBuffer > 500_000) tsBuffer = clockPts;
      pts = tsBuffer;
    }
    return pts;
  }

  @Override
  protected boolean checkBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo) {
    boolean valid = checkValidTimeStamp(bufferInfo);
    // The frame's pts counts from the encoder's start, and the source stamped it with
    // its first sample's capture instant on this same clock (BOOTTIME), so start + pts
    // is when the sound was heard. A Sender Report needs that, not the time it leaves.
    if (valid && presentTimeUs > 0) {
      CaptureLatency.reportAudio(SystemClock.elapsedRealtimeNanos()
          - (presentTimeUs + bufferInfo.presentationTimeUs) * 1000L);
    }
    return valid;
  }

  @Override
  protected void sendBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo) {
    getAudioData.getAudioData(byteBuffer, bufferInfo);
  }

  /**
   * Set custom PCM data.
   * Use it after prepareAudioEncoder(int sampleRate, int channel).
   * Used too with microphone.
   */
  @Override
  public void inputPCMData(@NonNull Frame frame) {
    if (!running) return;
    if (handler != null && getFrame == null) {
      handler.post(() -> {
        Integer index = freeInputs.poll();
        if (index != null) fill(index, frame);
        else if (!queue.offer(frame)) Log.i(TAG, "frame discarded");
      });
      return;
    }
    if (!queue.offer(frame)) {
      Log.i(TAG, "frame discarded");
    }
  }

  /**
   * An input buffer is never held waiting for PCM. The callbacks share one thread, and
   * waiting here for the next block (85 ms) once per buffer the codec had free held every
   * output callback queued behind them: the AAC left in bursts of about five blocks, which
   * a live stream carries as 427 ms of sound at once, then nothing. The index is kept and
   * {@link #inputPCMData} fills it when the PCM arrives.
   */
  @Override
  public void inputAvailable(@NonNull MediaCodec mediaCodec, int inBufferIndex) {
    if (getFrame != null || handler == null) {
      super.inputAvailable(mediaCodec, inBufferIndex);
      return;
    }
    Frame frame = queue.poll();
    if (frame == null) freeInputs.add(inBufferIndex);
    else fill(inBufferIndex, frame);
  }

  private void fill(int inBufferIndex, @NonNull Frame frame) {
    MediaCodec mediaCodec = codec;
    if (mediaCodec == null || !running) return;
    try {
      ByteBuffer byteBuffer = mediaCodec.getInputBuffer(inBufferIndex);
      if (byteBuffer == null) return;
      byteBuffer.clear();
      int size = Math.max(0, Math.min(frame.getSize(), byteBuffer.remaining()));
      byteBuffer.put(frame.getBuffer(), frame.getOffset(), size);
      mediaCodec.queueInputBuffer(inBufferIndex, 0, size, calculatePts(frame, presentTimeUs), 0);
    } catch (IllegalStateException | IndexOutOfBoundsException e) {
      // A codec stopped or reset under a posted frame: that buffer is no longer ours.
      Log.i(TAG, "Encoding error", e);
    }
  }

  @Override
  protected MediaCodecInfo chooseEncoder(String mime) {
    List<MediaCodecInfo> mediaCodecInfoList;
    if (codecType == CodecUtil.CodecType.HARDWARE) {
      mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(CodecUtil.AAC_MIME);
    } else if (codecType == CodecUtil.CodecType.SOFTWARE) {
      mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(CodecUtil.AAC_MIME);
    } else if (codecType == CodecUtil.CodecType.CBR_PRIORITY) {
      mediaCodecInfoList = CodecUtil.getAllEncodersCbrPriority(mime);
    } else {
      //Priority: hardware > software
      mediaCodecInfoList = CodecUtil.getAllEncoders(mime, true);
    }

    Log.i(TAG, mediaCodecInfoList.size() + " encoders found");
    if (mediaCodecInfoList.isEmpty()) return null;
    else return mediaCodecInfoList.get(0);
  }

  public void setSampleRate(int sampleRate) {
    this.sampleRate = sampleRate;
  }

  @Override
  public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
    getAudioData.onAudioFormat(mediaFormat);
  }
}
