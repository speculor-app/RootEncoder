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

package com.pedro.common

/**
 * How far behind real time the encoder's output runs — the delay between a camera
 * exposing a frame and that frame emerging encoded.
 *
 * Anything downstream can only observe a frame once it is encoded, so a timestamp taken
 * there is late by this much. Measured against a filmed reference clock on one 4K HEVC
 * stream it was ~225 ms, roughly seven frames; large enough to matter when video is fused
 * with motion data captured alongside it.
 *
 * Published from the encoder because that is the only place both halves exist: the
 * source's own timestamp for the frame, and a system clock reading as it is produced.
 *
 * **Zero means unknown, not "no latency".** In surface mode the source timestamp is the
 * camera's, and nothing guarantees it shares an origin with any system clock — on one
 * device that assumption put frames seconds out. The encoder only publishes a value that
 * survives a sanity check, so a caller subtracting this can be wrong by no more than the
 * check's bound, and is otherwise merely uncorrected.
 */
object CaptureLatency {

    /**
     * Latest measured encoder output delay in nanoseconds, or 0 when unknown.
     *
     * Written per frame on the encoder thread, read on the sender thread. A stale read
     * costs one frame a slightly dated correction, which is far below the quantity being
     * corrected.
     */
    @Volatile
    @JvmStatic
    var lastNs: Long = 0
        private set

    /** Upper bound for a believable camera-to-encoder delay. */
    private const val MAX_PLAUSIBLE_NS = 2_000_000_000L

    /**
     * Record a measurement, rejecting anything that cannot be a pipeline delay.
     *
     * A negative value means the frame was produced before it was captured, and an
     * implausibly large one means the two clocks do not share an origin — the failure
     * this guard exists for. Both publish 0 rather than a confident wrong number.
     */
    @JvmStatic
    fun report(latencyNs: Long) {
        lastNs = if (latencyNs in 0..MAX_PLAUSIBLE_NS) latencyNs else 0
    }

    @JvmStatic
    fun reset() {
        lastNs = 0
    }
}
