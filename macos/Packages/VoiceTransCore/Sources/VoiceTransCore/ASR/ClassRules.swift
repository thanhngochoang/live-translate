import Foundation

private let speechLabels: Set<String> = [
    "speech", "male speech", "female speech", "child speech", "conversation", "narration", "monologue",
    "speech synthesizer"
]

private let singingLabels: Set<String> = [
    "singing", "choir", "rapping", "chant", "song", "male singing", "female singing", "child singing",
    "synthetic singing", "opera", "a capella", "vocal music", "yodeling"
]

private let musicLabels: Set<String> = ["music"]

private func familyScore(scores: [String: Float], members: Set<String>) -> Float {
    var best: Float = 0
    for (label, p) in scores {
        let l = label.lowercased()
        let prefix = l.split(separator: ",").first.map { String($0).trimmingCharacters(in: .whitespaces) } ?? l
        if (members.contains(l) || members.contains(prefix)) && p > best {
            best = p
        }
    }
    return best
}

public func classifyAudio(scores: [String: Float]) -> AudioClass {
    let speech = familyScore(scores: scores, members: speechLabels)
    let singing = familyScore(scores: scores, members: singingLabels)
    let music = familyScore(scores: scores, members: musicLabels)

    if speech >= ClassThresholds.speechStrong {
        return .speech
    } else if singing >= ClassThresholds.singingMin && singing > speech {
        return .singing
    } else if speech >= ClassThresholds.speechMin {
        return .speech
    } else if music >= ClassThresholds.musicMin && speech < ClassThresholds.musicMaxSpeech {
        return .music
    } else {
        return .other
    }
}
