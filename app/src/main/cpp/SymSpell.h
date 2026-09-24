#ifndef SYMSPELL_H
#define SYMSPELL_H

/**
 * Lightweight SymSpell-style spell checker for on-device keyboard.
 * Implements delete-dictionary + lookup with max edit distance 2.
 * Header-only style implementation (all in .h for simplicity of NDK build).
 */

#include <algorithm>
#include <cctype>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace symspell {

enum class Verbosity { Top, Closest, All };

struct SuggestItem {
    std::string term;
    int distance = 0;
    int64_t count = 0;
};

class SymSpell {
public:
    // capacity hint, maxEditDistance, prefixLength (unused in this compact port)
    SymSpell(int /*capacity*/ = 82765, int maxEditDistance = 2, int /*prefixLength*/ = 7)
        : maxEditDistance_(maxEditDistance) {}

    /**
     * Load dictionary file: each line "word frequency"
     * termIndex=0, countIndex=1
     */
    bool LoadDictionary(const std::string& path, int termIndex, int countIndex) {
        std::ifstream in(path);
        if (!in.is_open()) return false;
        std::string line;
        while (std::getline(in, line)) {
            if (line.empty()) continue;
            std::istringstream iss(line);
            std::vector<std::string> parts;
            std::string part;
            while (iss >> part) parts.push_back(part);
            if (static_cast<int>(parts.size()) <= std::max(termIndex, countIndex)) continue;
            std::string word = toLower(parts[termIndex]);
            if (word.empty()) continue;
            int64_t count = 1;
            try {
                count = std::stoll(parts[countIndex]);
            } catch (...) {
                count = 1;
            }
            if (count < 1) count = 1;
            wordCounts_[word] = std::max(wordCounts_[word], count);
            // Build deletes up to maxEditDistance
            std::unordered_set<std::string> deletes;
            edits(word, 0, deletes);
            for (const auto& d : deletes) {
                deletes_[d].insert(word);
            }
        }
        return !wordCounts_.empty();
    }

    std::vector<SuggestItem> Lookup(const std::string& input,
                                    Verbosity verbosity,
                                    int maxEditDistance) const {
        std::string word = toLower(input);
        std::vector<SuggestItem> suggestions;
        if (word.empty()) return suggestions;

        // Exact match
        auto itExact = wordCounts_.find(word);
        if (itExact != wordCounts_.end()) {
            SuggestItem item;
            item.term = word;
            item.distance = 0;
            item.count = itExact->second;
            suggestions.push_back(item);
            if (verbosity == Verbosity::Top) return suggestions;
        }

        int maxDist = std::min(maxEditDistance, maxEditDistance_);
        std::unordered_set<std::string> considered;
        considered.insert(word);

        // Candidates from delete variants of input
        std::unordered_set<std::string> inputDeletes;
        edits(word, 0, inputDeletes);

        // Also consider the word itself as a "delete" key
        inputDeletes.insert(word);

        struct Candidate {
            std::string term;
            int distance;
            int64_t count;
        };
        std::vector<Candidate> candidates;

        for (const auto& del : inputDeletes) {
            auto it = deletes_.find(del);
            if (it == deletes_.end()) continue;
            for (const auto& suggestion : it->second) {
                if (considered.count(suggestion)) continue;
                considered.insert(suggestion);
                int dist = damerauLevenshtein(word, suggestion);
                if (dist > maxDist) continue;
                auto wit = wordCounts_.find(suggestion);
                int64_t cnt = (wit != wordCounts_.end()) ? wit->second : 1;
                candidates.push_back({suggestion, dist, cnt});
            }
        }

        // Sort: lower distance first, then higher frequency
        std::sort(candidates.begin(), candidates.end(),
                  [](const Candidate& a, const Candidate& b) {
                      if (a.distance != b.distance) return a.distance < b.distance;
                      return a.count > b.count;
                  });

        for (const auto& c : candidates) {
            SuggestItem item;
            item.term = c.term;
            item.distance = c.distance;
            item.count = c.count;
            suggestions.push_back(item);
            if (verbosity == Verbosity::Top && !suggestions.empty()) {
                // Keep only best (already sorted)
                if (suggestions.size() > 1) suggestions.resize(1);
                break;
            }
            if (verbosity == Verbosity::Closest &&
                !suggestions.empty() &&
                suggestions.front().distance < c.distance) {
                break;
            }
        }

        if (verbosity == Verbosity::Top && suggestions.size() > 1) {
            suggestions.resize(1);
        }
        return suggestions;
    }

    size_t wordCount() const { return wordCounts_.size(); }

private:
    int maxEditDistance_;
    std::unordered_map<std::string, int64_t> wordCounts_;
    std::unordered_map<std::string, std::unordered_set<std::string>> deletes_;

    static std::string toLower(const std::string& s) {
        std::string out = s;
        for (char& c : out) {
            c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
        }
        return out;
    }

    // Recursive generation of deletes (edit distance via deletes only — SymSpell core)
    void edits(const std::string& word, int editDistance,
               std::unordered_set<std::string>& result) const {
        if (editDistance >= maxEditDistance_) return;
        for (size_t i = 0; i < word.size(); ++i) {
            std::string del = word.substr(0, i) + word.substr(i + 1);
            if (result.insert(del).second) {
                edits(del, editDistance + 1, result);
            }
        }
    }

    static int damerauLevenshtein(const std::string& a, const std::string& b) {
        const int n = static_cast<int>(a.size());
        const int m = static_cast<int>(b.size());
        if (n == 0) return m;
        if (m == 0) return n;
        // Bound: if length diff already > max useful, still compute for correctness
        std::vector<std::vector<int>> dp(n + 1, std::vector<int>(m + 1));
        for (int i = 0; i <= n; ++i) dp[i][0] = i;
        for (int j = 0; j <= m; ++j) dp[0][j] = j;
        for (int i = 1; i <= n; ++i) {
            for (int j = 1; j <= m; ++j) {
                int cost = (a[i - 1] == b[j - 1]) ? 0 : 1;
                dp[i][j] = std::min({
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                });
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    dp[i][j] = std::min(dp[i][j], dp[i - 2][j - 2] + cost);
                }
            }
        }
        return dp[n][m];
    }
};

}  // namespace symspell

#endif  // SYMSPELL_H
