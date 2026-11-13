import Foundation

public enum Resampler {
    /// Downmixes interleaved stereo (or multi-channel) audio to mono.
    public static func downmixToMono(interleaved: [Float], channelCount: Int) -> [Float] {
        guard channelCount > 1 else { return interleaved }
        let frameCount = interleaved.count / channelCount
        var mono = [Float](repeating: 0, count: frameCount)

        for i in 0..<frameCount {
            var sum: Float = 0
            for ch in 0..<channelCount {
                sum += interleaved[i * channelCount + ch]
            }
            mono[i] = sum / Float(channelCount)
        }
        return mono
    }

    /// Resamples mono audio from `fromSampleRate` to `toSampleRate` (default 16000 Hz) using linear interpolation.
    public static func resampleMono(
        samples: [Float],
        fromSampleRate: Double,
        toSampleRate: Double = 16000.0
    ) -> [Float] {
        guard fromSampleRate != toSampleRate, !samples.isEmpty else { return samples }

        let ratio = fromSampleRate / toSampleRate
        let outputCount = Int(Double(samples.count) / ratio)
        var output = [Float](repeating: 0, count: outputCount)

        for i in 0..<outputCount {
            let srcIndex = Double(i) * ratio
            let indexFloor = Int(srcIndex)
            let frac = Float(srcIndex - Double(indexFloor))

            if indexFloor + 1 < samples.count {
                let sample1 = samples[indexFloor]
                let sample2 = samples[indexFloor + 1]
                output[i] = sample1 + frac * (sample2 - sample1)
            } else if indexFloor < samples.count {
                output[i] = samples[indexFloor]
            }
        }
        return output
    }
}
