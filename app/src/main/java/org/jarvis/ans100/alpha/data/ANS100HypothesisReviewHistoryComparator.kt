package org.jarvis.ans100.alpha.data

/**
 * Compares two immutable manual-review records for the same hypothesis.
 * This is an audit diff, not an evidence-quality score or an automatic verdict.
 */
object ANS100HypothesisReviewHistoryComparator {
    data class Comparison(
        val previousDecisionId: String,
        val outcomeChanged: Boolean,
        val packetChanged: Boolean,
        val addedSelectedPassageRefs: List<String>,
        val removedSelectedPassageRefs: List<String>,
        val addedSourceUrls: List<String>,
        val removedSourceUrls: List<String>,
        val changedSourceUrls: List<String>,
        val addedPassageRefs: List<String>,
        val removedPassageRefs: List<String>,
        val changedPassageRefs: List<String>,
        val addedWarnings: List<String>,
        val removedWarnings: List<String>,
        val snapshotsComparable: Boolean,
    )

    fun compare(
        previous: ANS100HypothesisManualReviewLedger.Entry,
        current: ANS100HypothesisManualReviewLedger.Entry,
    ): Comparison {
        require(previous.decision.hypothesisId == current.decision.hypothesisId) {
            "review history entries must belong to the same hypothesis"
        }
        require(previous.decision.decisionId != current.decision.decisionId) {
            "a review entry cannot be compared with itself"
        }
        require(previous.decision.reviewedAtEpochMs <= current.decision.reviewedAtEpochMs) {
            "reviews must be compared in chronological order"
        }

        val oldSelected = previous.decision.selectedPassageRefs.toSet()
        val newSelected = current.decision.selectedPassageRefs.toSet()
        val oldSnapshot = previous.snapshot
        val newSnapshot = current.snapshot
        val comparable = oldSnapshot != null && newSnapshot != null

        // A missing legacy snapshot means "unknown", not an empty source set.
        val oldSources = if (comparable) oldSnapshot!!.sources.associateBy { it.canonicalUrl } else emptyMap()
        val newSources = if (comparable) newSnapshot!!.sources.associateBy { it.canonicalUrl } else emptyMap()
        val oldPassages = if (comparable) oldSnapshot!!.passages.associateBy { it.passageRef } else emptyMap()
        val newPassages = if (comparable) newSnapshot!!.passages.associateBy { it.passageRef } else emptyMap()

        return Comparison(
            previousDecisionId = previous.decision.decisionId,
            outcomeChanged = previous.decision.outcome != current.decision.outcome,
            packetChanged = previous.decision.packetFingerprint != current.decision.packetFingerprint,
            addedSelectedPassageRefs = (newSelected - oldSelected).sorted(),
            removedSelectedPassageRefs = (oldSelected - newSelected).sorted(),
            addedSourceUrls = (newSources.keys - oldSources.keys).sorted(),
            removedSourceUrls = (oldSources.keys - newSources.keys).sorted(),
            changedSourceUrls = (oldSources.keys intersect newSources.keys)
                .filter { oldSources[it]?.pageContentSha256 != newSources[it]?.pageContentSha256 }
                .sorted(),
            addedPassageRefs = (newPassages.keys - oldPassages.keys).sorted(),
            removedPassageRefs = (oldPassages.keys - newPassages.keys).sorted(),
            changedPassageRefs = (oldPassages.keys intersect newPassages.keys)
                .filter {
                    val old = oldPassages[it]
                    val new = newPassages[it]
                    old?.sentenceSha256 != new?.sentenceSha256 ||
                        old?.polarity != new?.polarity ||
                        old?.polarityReason != new?.polarityReason ||
                        old?.excerpt != new?.excerpt
                }.sorted(),
            addedWarnings = if (comparable) (newSnapshot!!.warnings.toSet() - oldSnapshot!!.warnings.toSet()).sorted() else emptyList(),
            removedWarnings = if (comparable) (oldSnapshot!!.warnings.toSet() - newSnapshot!!.warnings.toSet()).sorted() else emptyList(),
            snapshotsComparable = comparable,
        )
    }
}
