import Foundation

enum StreamingTranscriptMerger {
    /// Reconciles two ASR passes without ever requiring the full recording to be
    /// transcribed again. Exact overlap is preferred; a fuzzy word alignment is
    /// used for normal boundary revisions, and unrelated text is concatenated.
    nonisolated static func mergeBestEffort(prefix: String, overlappingSuffix: String) -> String {
        if let exact = self.merge(prefix: prefix, overlappingSuffix: overlappingSuffix) {
            return exact
        }

        let prefixWords = prefix.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let suffixWords = overlappingSuffix.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard !prefixWords.isEmpty else { return overlappingSuffix }
        guard !suffixWords.isEmpty else { return prefix }

        let maximumOverlap = min(prefixWords.count, suffixWords.count, 40)
        var best: (count: Int, matchingWords: Int)?
        for count in stride(from: maximumOverlap, through: 2, by: -1) {
            let left = prefixWords.suffix(count).map(normalized)
            let right = suffixWords.prefix(count).map(normalized)
            let matches = zip(left, right).reduce(into: 0) { total, pair in
                if pair.0 == pair.1 { total += 1 }
            }
            guard Double(matches) / Double(count) >= 0.6 else { continue }
            if best == nil || matches > best!.matchingWords || (matches == best!.matchingWords && count > best!.count) {
                best = (count, matches)
            }
        }

        if let best {
            return (prefixWords + suffixWords.dropFirst(best.count)).joined(separator: " ")
        }
        return (prefixWords + suffixWords).joined(separator: " ")
    }

    nonisolated static func merge(prefix: String, overlappingSuffix: String) -> String? {
        let prefixWords = prefix.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let suffixWords = overlappingSuffix.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard prefixWords.count >= 2, suffixWords.count >= 2 else { return nil }

        let maximumOverlap = min(prefixWords.count, suffixWords.count, 40)
        for count in stride(from: maximumOverlap, through: 2, by: -1) {
            let prefixOverlap = prefixWords.suffix(count).map(normalized)
            let suffixOverlap = suffixWords.prefix(count).map(normalized)
            if prefixOverlap == suffixOverlap {
                var mergedPrefix = prefixWords
                let suffixBoundaryWord = suffixWords[count - 1]
                mergedPrefix[mergedPrefix.count - 1] = self.applyingTrailingPunctuation(
                    from: suffixBoundaryWord,
                    to: mergedPrefix[mergedPrefix.count - 1]
                )
                return (mergedPrefix + suffixWords.dropFirst(count)).joined(separator: " ")
            }
        }
        return nil
    }

    nonisolated private static func normalized(_ word: String) -> String {
        word.lowercased().filter { $0.isLetter || $0.isNumber }
    }

    nonisolated private static func applyingTrailingPunctuation(from source: String, to target: String) -> String {
        let punctuation = source.reversed().prefix { !$0.isLetter && !$0.isNumber }.reversed()
        guard !punctuation.isEmpty else { return target }
        let base = target.reversed().drop { !$0.isLetter && !$0.isNumber }.reversed()
        return String(base) + String(punctuation)
    }
}
