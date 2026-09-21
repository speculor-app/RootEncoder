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

package com.pedro.encoder.video;

import android.graphics.ImageFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.pedro.common.CaptureLatency;
import com.pedro.common.TimeUtils;
import com.pedro.encoder.BaseEncoder;
import com.pedro.encoder.Frame;
import com.pedro.encoder.TimestampMode;
import com.pedro.encoder.input.video.FpsLimiter;
import com.pedro.encoder.input.video.GetCameraData;
import com.pedro.encoder.utils.CodecUtil;
import com.pedro.encoder.utils.SpsColorPatcher;
import com.pedro.encoder.utils.yuv.YUVUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Created by pedro on 19/01/17.
 * This class need use same resolution, fps and imageFormat that Camera1ApiManagerGl
 */

public class VideoEncoder extends BaseEncoder implements GetCameraData {

  private final GetVideoData getVideoData;
  private volatile boolean spsPpsSetted = false;
  private boolean forceKey = false;
  //video data necessary to send after requestKeyframe.
  private ByteBuffer oldSps, oldPps, oldVps;

  //surface to buffer encoder
  private Surface inputSurface;

  private int width = 640;
  private int height = 480;
  private int fps = 30;
  private int bitRate = 1200 * 1024; //in kbps
  private int rotation = 90;
  private int iFrameInterval = 2;
  private long firstTimestamp = 0;

  /**
   * The source timestamp (surface mode: the camera's sensor time, µs) of the
   * first encoded frame — the value every output PTS is relative to. 0 until a
   * frame has been encoded. A caller anchoring a recording on a PTS adds it back.
   */
  public long getFirstTimestampUs() {
    return firstTimestamp;
  }
  //for disable video
  private final FpsLimiter fpsLimiter = new FpsLimiter();
  private FormatVideoEncoder formatVideoEncoder = FormatVideoEncoder.YUV420Dynamical;
  private int profile = -1;
  private int level = -1;
  private final SpsColorPatcher spsColorPatcher = new SpsColorPatcher();
  private boolean forceBt709Color = false;
  // A VUI colour field to write over what the encoder put in the SPS, each on
  // its own, null = leave it. Reaches the streamed SPS and the muxer's csd-0.
  private Integer spsPrimaries = null, spsTransfer = null, spsMatrix = null;
  /**
   * Explicit colour metadata, or null to write none — the 10-bit route.
   *
   * [forceBt709Color] answers only one question, "is this BT.709 SDR", and a
   * 10-bit stream needs a different answer: HLG is BT.2020 primaries with the
   * ARIB STD-B67 transfer, PQ is BT.2020 with SMPTE ST 2084, and a player that
   * finds neither treats the file as SDR and shows it washed out or flat. So the
   * boolean became a CHOICE, and the boolean stays for the SDR case it already
   * serves — including `spsColorPatcher`, which rewrites the SPS to claim BT.709
   * and must never run over an HDR transfer.
   *
   * Each is applied only when set, because a device that tags correctly by itself
   * is better left alone: on a Magic5 Pro the pixels really are BT.601 FULL range
   * and the stock camera writes the same, so asserting anything there would
   * relabel correct pixels as something they are not.
   */
  private Integer colorStandard = null;
  private Integer colorTransfer = null;
  private Integer colorRange = null;

  public VideoEncoder(GetVideoData getVideoData) {
    this.getVideoData = getVideoData;
    typeError = CodecUtil.CodecTypeError.VIDEO_CODEC;
    type = CodecUtil.H264_MIME;
    TAG = "VideoEncoder";
  }

  public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int rotation,
      int iFrameInterval, FormatVideoEncoder formatVideoEncoder) {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval,
        formatVideoEncoder, -1, -1);
  }

  /**
   * Prepare encoder with custom parameters
   */
  public boolean prepareVideoEncoder(int width, int height, int fps, int bitRate, int rotation,
      int iFrameInterval, FormatVideoEncoder formatVideoEncoder, int profile,
      int level) {
    if (prepared) stop();

    if (width % 2 != 0) {
      throw new IllegalArgumentException("Invalid width: " + width + ", must be an even value");
    }
    if (height % 2 != 0) {
      throw new IllegalArgumentException("Invalid height: " + height + ", must be an even value");
    }
    if (fps <= 0) {
      throw new IllegalArgumentException("Invalid fps: " + fps + ", must be higher than 0");
    }
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.bitRate = bitRate;
    this.rotation = rotation;
    this.iFrameInterval = iFrameInterval;
    this.formatVideoEncoder = formatVideoEncoder;
    this.profile = profile;
    this.level = level;
    isBufferMode = true;
    MediaCodecInfo encoder = chooseEncoder(type);
    try {
      if (encoder != null) {
        Log.i(TAG, "Encoder selected " + encoder.getName());
        codec = MediaCodec.createByCodecName(encoder.getName());
        if (this.formatVideoEncoder == FormatVideoEncoder.YUV420Dynamical) {
          this.formatVideoEncoder = chooseColorDynamically(encoder);
          if (this.formatVideoEncoder == null) {
            Log.e(TAG, "YUV420 dynamical choose failed");
            return false;
          }
        }
      } else {
        Log.e(TAG, "Valid encoder not found");
        return false;
      }
      MediaFormat videoFormat;
      //if you don't use mediacodec rotation you need swap width and height in rotation 90 or 270
      // for correct encoding resolution
      String resolution;
      if ((rotation == 90 || rotation == 270)) {
        resolution = height + "x" + width;
        videoFormat = MediaFormat.createVideoFormat(type, height, width);
      } else {
        resolution = width + "x" + height;
        videoFormat = MediaFormat.createVideoFormat(type, width, height);
      }
      Log.i(TAG, "Prepare video info: " + this.formatVideoEncoder.name() + ", " + resolution);
      videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
          this.formatVideoEncoder.getFormatCodec());
      videoFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
      videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
      videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval);
      //Set CBR mode if supported by encoder.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && CodecUtil.isCBRModeSupported(encoder, type)) {
        Log.i(TAG, "set bitrate mode CBR");
        videoFormat.setInteger(MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
      } else {
        Log.i(TAG, "bitrate mode CBR not supported using default mode");
      }
      // Rotation by encoder.
      // Removed because this is ignored by most encoders, producing different results on different devices
      //  videoFormat.setInteger(MediaFormat.KEY_ROTATION, rotation);

      if (this.profile > 0) {
        // MediaFormat.KEY_PROFILE, API > 21
        videoFormat.setInteger("profile", this.profile);
      }
      if (this.level > 0) {
        // MediaFormat.KEY_LEVEL, API > 23
        videoFormat.setInteger("level", this.level);
      }
      // Force BT.709 color metadata into the VUI. OFF BY DEFAULT, and it should
      // stay off unless a device has been MEASURED to need it.
      //
      // The comment here used to say devices "default to smpte170m/bt470bg which
      // ffprobe/players read incorrectly". That is not true on every device and is
      // measurably false on a Honor Magic5 Pro (Kalama), checked 2026-09-18:
      //
      //   * the pixels really are BT.601. A chart of saturated primaries was filmed
      //     and decoded both ways: under BT.709 the green bar resolves to a NEGATIVE
      //     red, which cannot happen, and the out-of-gamut penalty over six bars was
      //     31 for BT.601 against 58 for BT.709.
      //   * the pixels really are FULL range: luma spans 0..255 with 7.3% of samples
      //     below 16 -- so COLOR_RANGE_LIMITED below is wrong for this device too.
      //   * the stock camera app writes the SAME tagging, so smpte170m/bt470bg is
      //     what this pipeline genuinely produces rather than a defect.
      //
      // Turning this on there would relabel correct pixels as something they are
      // not, and `spsColorPatcher` would carry the same claim into the bitstream
      // where no container rewrite can undo it. Measure the device first.
      //
      // KEY_COLOR_STANDARD / KEY_COLOR_TRANSFER / KEY_COLOR_RANGE added in API 24.
      // An explicit choice wins; the BT.709 flag above serves the SDR case alone.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        if (colorStandard != null || colorTransfer != null || colorRange != null) {
          // AN EXPLICIT CHOICE, which is how 10-bit is asked for: BT.2020 with
          // HLG or with PQ. Each key independently, so a caller can state the
          // transfer without asserting a range it has not measured.
          if (colorStandard != null) videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard);
          if (colorTransfer != null) videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer);
          if (colorRange != null) videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange);
          if (hdrStaticInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            videoFormat.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo.duplicate());
          }
          Log.i(TAG, "colour: standard=" + colorStandard + " transfer=" + colorTransfer
              + " range=" + colorRange + " hdrStaticInfo=" + (hdrStaticInfo != null));
        } else if (forceBt709Color) {
          videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);  // primaries + matrix = BT.709
          videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO); // transfer = BT.709 (gamma)
          videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);        // TV range (16-235)
        }
      }
      setCallback();
      codec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      running = false;
      if (formatVideoEncoder == FormatVideoEncoder.SURFACE
          && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
        isBufferMode = false;
        inputSurface = codec.createInputSurface();
      }
      Log.i(TAG, "prepared");
      prepared = true;
      return true;
    } catch (Exception e) {
      Log.e(TAG, "Create VideoEncoder failed.", e);
      this.stop();
      return false;
    }
  }

  @Override
  public void start(boolean resetTs) {
    if (resetTs) {
      firstTimestamp = 0;
      // The measurement belongs to the timeline that produced it.
      CaptureLatency.reset();
    }
    forceKey = false;
    shouldReset = resetTs;
    spsPpsSetted = false;
    if (formatVideoEncoder != FormatVideoEncoder.SURFACE) {
      YUVUtil.preAllocateBuffers(width * height * 3 / 2);
    }
    Log.i(TAG, "started");
  }

  @Override
  protected void stopImp() {
    spsPpsSetted = false;
    if (inputSurface != null) inputSurface.release();
    inputSurface = null;
    oldSps = null;
    oldPps = null;
    oldVps = null;
    Log.i(TAG, "stopped");
  }

  @Override
  public boolean reset() {
    stop(false);
    boolean result = prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval, formatVideoEncoder,
        profile, level);
    if (!result) return false;
    restart();
    return true;
  }

  private FormatVideoEncoder chooseColorDynamically(MediaCodecInfo mediaCodecInfo) {
    for (int color : mediaCodecInfo.getCapabilitiesForType(type).colorFormats) {
      if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420PLANAR;
      } else if (color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
        return FormatVideoEncoder.YUV420SEMIPLANAR;
      }
    }
    return null;
  }

  /**
   * Prepare encoder with default parameters
   */
  public boolean prepareVideoEncoder() {
    return prepareVideoEncoder(width, height, fps, bitRate, rotation, iFrameInterval,
        formatVideoEncoder, profile, level);
  }

  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  public void setVideoBitrateOnFly(int bitrate) {
    if (isRunning()) {
      this.bitRate = bitrate;
      Bundle bundle = new Bundle();
      bundle.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
      try {
        codec.setParameters(bundle);
      } catch (IllegalStateException e) {
        Log.e(TAG, "encoder need be running", e);
      }
    }
  }

  @RequiresApi(api = Build.VERSION_CODES.KITKAT)
  public void requestKeyframe() {
    if (isRunning()) {
      if (spsPpsSetted && oldSps != null) {
        Bundle bundle = new Bundle();
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        try {
          codec.setParameters(bundle);
          getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        } catch (IllegalStateException e) {
          Log.e(TAG, "encoder need be running", e);
        }
      } else {
        //You need wait until encoder generate first frame.
        spsPpsSetted = false;
        forceKey = true;
      }
    }
  }

  public Surface getInputSurface() {
    return inputSurface;
  }

  public void setInputSurface(Surface inputSurface) {
    this.inputSurface = inputSurface;
  }

  public int getWidth() {
    return width;
  }

  public int getHeight() {
    return height;
  }

  public int getRotation() {
    return rotation;
  }

  public void setFps(int fps) {
    this.fps = fps;
  }

  public void setRotation(int rotation) {
    this.rotation = rotation;
  }

  public int getFps() {
    return fps;
  }

  public int getBitRate() {
    return bitRate;
  }

  public void setForceFps(int fps) {
    fpsLimiter.setFPS(fps);
  }

  // The three colour setters refuse only while the encoder RUNS. They used to
  // refuse while it was PREPARED, and a caller that re-prepares -- a lens switch
  // or a rate change on speculor-mobile-sensor -- sets them before its next
  // prepareVideo while the previous configuration is still prepared, so every
  // one of them was refused and the new configuration silently kept the old
  // keys: under HLG10 a switch to an SDR module wrote BT.2020 keys under an SDR
  // profile. prepare() reads the fields when it builds the MediaFormat, which
  // is after a reconfigure too, so a value set on a prepared, stopped encoder
  // lands in the next format exactly as one set on a fresh encoder does.
  /**
   * Relabel the encoded stream as BT.709 -- in the MediaFormat and, through
   * {@code spsColorPatcher}, in the SPS VUI itself.
   *
   * ONLY for a device measured to produce BT.709 pixels while tagging them 601.
   * It changes what the file CLAIMS, never what the pixels are, so on a device
   * that is honestly 601 it turns a correct file into a wrong one. See the note
   * in {@code prepare()} for the measurement on a Magic5 Pro, where it is wrong
   * on both the matrix and the range.
   */
  public void forceBt709Color(boolean enabled) {
    if (running) throw new IllegalStateException("Encoder running, this must be called before it starts");
    this.forceBt709Color = enabled;
  }

  /**
   * State the colour metadata the encoder should write, each key or null to
   * leave it unstated. Takes precedence over {@link #forceBt709Color(boolean)}.
   *
   * For HLG10: BT.2020 with COLOR_TRANSFER_HLG. For HDR10: BT.2020 with
   * COLOR_TRANSFER_ST2084, and the stream also wants its ST 2086 mastering
   * metadata, which is {@link #setHdrStaticInfo(ByteBuffer)}.
   *
   * Measured on a Magic5 Pro, 2026-09-19: the encoder honoured
   * COLOR_RANGE_LIMITED for HLG and IGNORED it for PQ and for SDR, both of which
   * came back full range. So the range a file GOT has to be read from the file,
   * never assumed from what was asked.
   */
  public void setColorMetadata(Integer standard, Integer transfer, Integer range) {
    if (running) throw new IllegalStateException("Encoder running, this must be called before it starts");
    this.colorStandard = standard;
    this.colorTransfer = transfer;
    this.colorRange = range;
  }

  /**
   * ST 2086 mastering-display metadata, the 25-byte CTA-861.3 blob HDR10 needs.
   *
   * PQ without it is a stream a player has to guess at. HLG carries its own
   * transfer and needs none, so null is right there.
   */
  public void setHdrStaticInfo(java.nio.ByteBuffer info) {
    if (running) throw new IllegalStateException("Encoder running, this must be called before it starts");
    this.hdrStaticInfo = info;
  }

  /**
   * Write these VUI colour fields over the encoder's own, in the SPS a stream
   * receives and in the csd-0 the muxer records; a null leaves that field.
   * For a pipeline that never remaps the gamut but tags BT.2020 (a Magic5 Pro
   * in HLG10 and HDR10, measured 2026-09-20): primaries 1 (BT.709), transfer
   * and matrix left. Takes precedence over {@link #forceBt709Color(boolean)}.
   * The container's own colr box is the muxer's, written from MediaFormat
   * keys that cannot say "709 primaries, 2020 matrix", so a caller that wants
   * the container to agree rewrites that box in the closed file.
   */
  public void setSpsColorOverride(Integer primaries, Integer transfer, Integer matrix) {
    if (running) throw new IllegalStateException("Encoder running, this must be called before it starts");
    this.spsPrimaries = primaries;
    this.spsTransfer = transfer;
    this.spsMatrix = matrix;
  }

  private boolean hasSpsColorOverride() {
    return spsPrimaries != null || spsTransfer != null || spsMatrix != null;
  }

  /** The SPS as the world should see it: the override first, the BT.709 relabel second, else as encoded. */
  private ByteBuffer patchedSps(ByteBuffer sps, boolean isHevc) {
    if (hasSpsColorOverride()) return spsColorPatcher.patchSpsNalColor(sps, isHevc, spsPrimaries, spsTransfer, spsMatrix);
    if (forceBt709Color) return spsColorPatcher.patchSpsNalColorToBt709(sps, isHevc);
    return sps;
  }

  private java.nio.ByteBuffer hdrStaticInfo = null;

  @Override
  public void inputYUVData(@NonNull Frame frame) {
    if (running && !queue.offer(frame)) {
      Log.i(TAG, "frame discarded");
    }
  }

  private boolean sendSPSandPPS(MediaFormat mediaFormat) {
    //AV1
    if (type.equals(CodecUtil.AV1_MIME)) {
      ByteBuffer bufferInfo = mediaFormat.getByteBuffer("csd-0");
      //we need an av1ConfigurationRecord with sequenceObu to work
      if (bufferInfo != null && bufferInfo.remaining() > 4) {
        oldSps = bufferInfo.duplicate();
        getVideoData.onVideoInfo(oldSps, null, null);
        return true;
      }
      //H265
    } else if (type.equals(CodecUtil.H265_MIME)) {
      ByteBuffer bufferInfo = mediaFormat.getByteBuffer("csd-0");
      if (bufferInfo != null) {
        List<ByteBuffer> byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(bufferInfo.duplicate());
        oldSps = patchedSps(byteBufferList.get(1), true);
        oldPps = byteBufferList.get(2);
        oldVps = byteBufferList.get(0);
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        return true;
      }
      //H264
    } else {
      ByteBuffer sps = mediaFormat.getByteBuffer("csd-0");
      ByteBuffer pps = mediaFormat.getByteBuffer("csd-1");
      if (sps != null && pps != null) {
        oldSps = patchedSps(sps.duplicate(), false);
        oldPps = pps.duplicate();
        oldVps = null;
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        return true;
      }
    }
    return false;
  }

  /**
   * choose the video encoder by mime.
   */
  @Override
  protected MediaCodecInfo chooseEncoder(String mime) {
    List<MediaCodecInfo> mediaCodecInfoList;
    if (codecType == CodecUtil.CodecType.HARDWARE) {
      mediaCodecInfoList = CodecUtil.getAllHardwareEncoders(mime, true);
    } else if (codecType == CodecUtil.CodecType.SOFTWARE) {
      mediaCodecInfoList = CodecUtil.getAllSoftwareEncoders(mime, true);
    } else if (codecType == CodecUtil.CodecType.CBR_PRIORITY) {
      //Priority: hardware CBR > software CBR > hardware > software
      mediaCodecInfoList = CodecUtil.getAllEncodersCbrPriority(mime);
    } else {
      //Priority: hardware CBR > hardware > software CBR > software
      mediaCodecInfoList = CodecUtil.getAllEncoders(mime, true, true);
    }

    Log.i(TAG, mediaCodecInfoList.size() + " encoders found");
    for (MediaCodecInfo mci : mediaCodecInfoList) {
      Log.i(TAG, "Encoder " + mci.getName());
      MediaCodecInfo.CodecCapabilities codecCapabilities = mci.getCapabilitiesForType(mime);
      for (int color : codecCapabilities.colorFormats) {
        Log.i(TAG, "Color supported: " + color);
        if (formatVideoEncoder == FormatVideoEncoder.SURFACE) {
          if (color == FormatVideoEncoder.SURFACE.getFormatCodec()) return mci;
        } else {
          //check if encoder support any yuv420 color
          if (color == FormatVideoEncoder.YUV420PLANAR.getFormatCodec()
              || color == FormatVideoEncoder.YUV420SEMIPLANAR.getFormatCodec()) {
            return mci;
          }
        }
      }
    }
    return null;
  }

  @Override
  protected Frame getInputFrame() throws InterruptedException {
    Frame frame = queue.take();
    if (frame == null) return null;
    if (fpsLimiter.limitFPS()) return getInputFrame();
    byte[] buffer = frame.getBuffer();
    boolean isYV12 = frame.getFormat() == ImageFormat.YV12;

    int orientation = frame.isFlip() ? frame.getOrientation() + 180 : frame.getOrientation();
    if (orientation >= 360) orientation -= 360;
    buffer = isYV12 ? YUVUtil.rotateYV12(buffer, width, height, orientation)
        : YUVUtil.rotateNV21(buffer, width, height, orientation);

    buffer = isYV12 ? YUVUtil.YV12toYUV420byColor(buffer, width, height, formatVideoEncoder)
        : YUVUtil.NV21toYUV420byColor(buffer, width, height, formatVideoEncoder);
    frame.setBuffer(buffer);
    return frame;
  }

  @Override
  protected long calculatePts(Frame frame, long presentTimeUs) {
    return Math.max(0, frame.getTimeStamp() - presentTimeUs);
  }

  @Override
  public void formatChanged(@NonNull MediaCodec mediaCodec, @NonNull MediaFormat mediaFormat) {
    // The recorder hands this format to MediaMuxer.addTrack, whose hvcC/avcC
    // carries csd-0 as is: the override has to be in it, or the file's SPS
    // disagrees with the stream's.
    if (hasSpsColorOverride()) spsColorPatcher.patchMediaFormatColor(mediaFormat, spsPrimaries, spsTransfer, spsMatrix);
    getVideoData.onVideoFormat(mediaFormat);
    spsPpsSetted = sendSPSandPPS(mediaFormat);
  }

  @Override
  protected boolean checkBuffer(@NonNull ByteBuffer byteBuffer, @NonNull MediaCodec.BufferInfo bufferInfo) {
    if (forceKey && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
      forceKey = false;
      requestKeyframe();
    }
    if (!spsPpsSetted && type.equals(CodecUtil.H264_MIME)) {
      Log.i(TAG, "formatChanged not called, doing manual sps/pps extraction...");
      Pair<ByteBuffer, ByteBuffer> buffers = VideoEncoderHelper.decodeSpsPpsFromBuffer(byteBuffer.duplicate(), bufferInfo.size);
      if (buffers != null) {
        Log.i(TAG, "manual sps/pps extraction success");
        oldSps = buffers.first;
        oldPps = buffers.second;
        oldVps = null;
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual sps/pps extraction failed");
      }
    } else if (!spsPpsSetted && type.equals(CodecUtil.H265_MIME)) {
      Log.i(TAG, "formatChanged not called, doing manual vps/sps/pps extraction...");
      List<ByteBuffer> byteBufferList = VideoEncoderHelper.extractVpsSpsPpsFromH265(byteBuffer.duplicate());
      if (byteBufferList.size() == 3) {
        Log.i(TAG, "manual vps/sps/pps extraction success");
        oldSps = byteBufferList.get(1);
        oldPps = byteBufferList.get(2);
        oldVps = byteBufferList.get(0);
        getVideoData.onVideoInfo(oldSps, oldPps, oldVps);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual vps/sps/pps extraction failed");
      }
    } else if (!spsPpsSetted && type.equals(CodecUtil.AV1_MIME)) {
      Log.i(TAG, "formatChanged not called, doing manual av1 extraction...");
      ByteBuffer obuSequence = VideoEncoderHelper.extractObuSequence(byteBuffer.duplicate(), bufferInfo);
      if (obuSequence != null) {
        oldSps = obuSequence;
        getVideoData.onVideoInfo(obuSequence, null, null);
        spsPpsSetted = true;
      } else {
        Log.e(TAG, "manual av1 extraction failed");
      }
    }
    if (timestampMode == TimestampMode.CLOCK) {
      if (formatVideoEncoder != FormatVideoEncoder.SURFACE) {
        // Buffer mode: synthesize PTS from wall clock.
        bufferInfo.presentationTimeUs = TimeUtils.getCurrentTimeMicro() - presentTimeUs;
      } else {
        // Surface mode: EGL timestamp is camera sensor time (nanoseconds from boot ÷ 1000).
        // It has clean, jitter-free intervals — but it's a huge absolute value that breaks RTMP.
        // Rebase to relative by subtracting the first frame's PTS → clean intervals, starts at 0.
        //
        // Measure the output delay before rebasing discards the absolute value: it is the
        // only moment the source's own timestamp and a system clock are both in hand.
        // CaptureLatency rejects anything implausible, so a source timeline that does not
        // share an origin with the system clock reports unknown rather than nonsense.
        CaptureLatency.report(
            SystemClock.elapsedRealtimeNanos() - bufferInfo.presentationTimeUs * 1000L);
        if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;
        bufferInfo.presentationTimeUs -= firstTimestamp;
      }
    } else {
      if (firstTimestamp == 0) firstTimestamp = bufferInfo.presentationTimeUs;
      bufferInfo.presentationTimeUs -= firstTimestamp;
    }
    return checkValidTimeStamp(bufferInfo);
  }

  @Override
  protected void sendBuffer(@NonNull ByteBuffer byteBuffer,
      @NonNull MediaCodec.BufferInfo bufferInfo) {
    getVideoData.getVideoData(byteBuffer, bufferInfo);
  }
}
