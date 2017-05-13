package com.google.android.exoplayer.chunk;

import android.util.Log;

import com.google.android.exoplayer.upstream.BandwidthMeter;

import java.util.List;


public class PIDEvaluator implements FormatEvaluator {

    public static final int DEFAULT_MAX_INITIAL_BITRATE = 800000;

    public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000;
    public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000;
    public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000;
    public static final float DEFAULT_BANDWIDTH_FRACTION = 0.75f;

    private final BandwidthMeter bandwidthMeter;

    private final int maxInitialBitrate;
    private final long minDurationForQualityIncreaseUs;
    private final long maxDurationForQualityDecreaseUs;
    private final long minDurationToRetainAfterDiscardUs;
    private final float bandwidthFraction;

    //class level variables for PID
    private final float k_i = 0.01f ;
    private final float k_p = 0.1f;
    private final int q_ref = 20;
    private float timeWeightedBuffer= 0;
    private float integral_error_correction = 0;


    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     */
    public PIDEvaluator(BandwidthMeter bandwidthMeter) {
        this (bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
                DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
    }

    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     * @param maxInitialBitrate The maximum bitrate in bits per second that should be assumed
     *     when bandwidthMeter cannot provide an estimate due to playback having only just started.
     * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for
     *     the evaluator to consider switching to a higher quality format.
     * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for
     *     the evaluator to consider switching to a lower quality format.
     * @param minDurationToRetainAfterDiscardMs When switching to a significantly higher quality
     *     format, the evaluator may discard some of the media that it has already buffered at the
     *     lower quality, so as to switch up to the higher quality faster. This is the minimum
     *     duration of media that must be retained at the lower quality.
     * @param bandwidthFraction The fraction of the available bandwidth that the evaluator should
     *     consider available for use. Setting to a value less than 1 is recommended to account
     *     for inaccuracies in the bandwidth estimator.
     */
    public PIDEvaluator(BandwidthMeter bandwidthMeter,
                        int maxInitialBitrate,
                        int minDurationForQualityIncreaseMs,
                        int maxDurationForQualityDecreaseMs,
                        int minDurationToRetainAfterDiscardMs,
                        float bandwidthFraction) {
        this.bandwidthMeter = bandwidthMeter;
        this.maxInitialBitrate = maxInitialBitrate;
        this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
        this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
        this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
        this.bandwidthFraction = bandwidthFraction;
    }

    @Override
    public void enable() {
        // Do nothing.
    }

    @Override
    public void disable() {
        // Do nothing.
    }

    // Kp

    @Override
    public void evaluate(List<? extends MediaChunk> queue, long playbackPositionUs,
                         Format[] formats, FormatEvaluator.Evaluation evaluation) {
        Log.d("PID", "PID Debugging inside evaluate");

        //long b_t = queue.isEmpty() ? 0
        //        : queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;

        long queueSize = 0;

        for(MediaChunk m:queue){

            queueSize += m.getDurationUs();
            Log.d("PID", "PID Debugging startTimeUs: " + m.startTimeUs);
            Log.d("PID", "PID Debugging endTimeUs: " + m.endTimeUs);
        }

        long b_t = 0;
        if(queue.isEmpty()) {
            b_t = 0;
        } else {
            b_t = queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;
        }

        b_t = b_t / 1000000;
        Log.d("PID", "PID Debugging queueSize: " + queueSize);
        Log.d("PID", "PID Debugging playbackPositionUs: " + playbackPositionUs);

        // possibly maintain the average queue size, weighted a

        timeWeightedBuffer += b_t * 0.25;

        integral_error_correction += (b_t - q_ref);

        float u_t = k_p * (b_t - q_ref) + k_i*(b_t);

        long bitRate = bandwidthMeter.getBitrateEstimate();

        float v_k = (1+u_t)*bitRate;

        Format suitableFormat = null;

        Log.d("PID", "PID Debugging b_t: " + b_t);
        Log.d("PID", "PID Debugging timeWeightedBuffer: " + timeWeightedBuffer);
        Log.d("PID", "PID Debugging u_t: " + u_t);
        Log.d("PID", "PID Debugging bitRate: " + bitRate);
        Log.d("PID", "PID Debugging v_k: " + v_k);

        Log.d("PID", "GRAPH v_k: " + v_k);

        for (int i = 0; i < formats.length; i++) {
            Format format = formats[i];
            if (format.bitrate <= v_k) {
                 suitableFormat = format;
                break;
            }
        }

        Log.d("PID", "PID Debugging suitable format: " + suitableFormat);

        if(suitableFormat != null) {
            evaluation.format = suitableFormat;
        } else {
            evaluation.format = formats[formats.length - 1];
        }

        Log.d("PID", "PID GRAPH format bitrate: " + evaluation.format.bitrate);

//        evaluation.format = formats[formats.length - 1];



        /*

        Log.d("BEEJEE", "evaluate called in Format Evaluator");
        for(Format format: formats) {
            Log.d("BEEJEE", "format bitrate: " + format.bitrate);
        }
        for(MediaChunk chunk: queue) {
            Log.d("BEEJEE", "Chunk duration: " + chunk.getDurationUs());
        }
        long bufferedDurationUs = queue.isEmpty() ? 0
                : queue.get(queue.size() - 1).endTimeUs - playbackPositionUs;
        Format current = evaluation.format;
        Format ideal = determineIdealFormat(formats, bandwidthMeter.getBitrateEstimate());
        boolean isHigher = ideal != null && current != null && ideal.bitrate > current.bitrate;
        boolean isLower = ideal != null && current != null && ideal.bitrate < current.bitrate;
        if (isHigher) {
            if (bufferedDurationUs < minDurationForQualityIncreaseUs) {
                // The ideal format is a higher quality, but we have insufficient buffer to
                // safely switch up. Defer switching up for now.
                ideal = current;
            } else if (bufferedDurationUs >= minDurationToRetainAfterDiscardUs) {
                // We're switching from an SD stream to a stream of higher resolution. Consider
                // discarding already buffered media chunks. Specifically, discard media chunks starting
                // from the first one that is of lower bandwidth, lower resolution and that is not HD.
                for (int i = 1; i < queue.size(); i++) {
                    MediaChunk thisChunk = queue.get(i);
                    long durationBeforeThisSegmentUs = thisChunk.startTimeUs - playbackPositionUs;
                    if (durationBeforeThisSegmentUs >= minDurationToRetainAfterDiscardUs
                            && thisChunk.format.bitrate < ideal.bitrate
                            && thisChunk.format.height < ideal.height
                            && thisChunk.format.height < 720
                            && thisChunk.format.width < 1280) {
                        // Discard chunks from this one onwards.
                        evaluation.queueSize = i;
                        break;
                    }
                }
            }
        } else if (isLower && current != null
                && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
            // The ideal format is a lower quality, but we have sufficient buffer to defer switching
            // down for now.
            ideal = current;
        }
        if (current != null && ideal != current) {
            evaluation.trigger = Chunk.TRIGGER_ADAPTIVE;
        }
        evaluation.format = ideal;
        */
    }


    /**
     * Compute the ideal format ignoring buffer health.
     */
    private Format determineIdealFormat(Format[] formats, long bitrateEstimate) {
//	  Log.d("BEEJEE", "determineIdealFormat called in Format Evaluator");
        long effectiveBitrate = bitrateEstimate == BandwidthMeter.NO_ESTIMATE
                ? maxInitialBitrate : (long) (bitrateEstimate * bandwidthFraction);
        for (int i = 0; i < formats.length; i++) {
            Format format = formats[i];
            if (format.bitrate <= effectiveBitrate) {
                return format;
            }
        }
        // We didn't manage to calculate a suitable format. Return the lowest quality format.
        return formats[formats.length - 1];
    }
}
