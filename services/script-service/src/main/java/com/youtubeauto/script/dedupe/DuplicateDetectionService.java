package com.youtubeauto.script.dedupe;

import com.youtubeauto.script.config.DedupeProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-stage check:
 *   1. Exact-hash match (cheap, unique-index hit).
 *   2. SimHash scan over the lookback window using Postgres' bit_count(simhash # ?)
 *      to filter, then compute exact similarity in Java for the survivors.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateDetectionService {

    @PersistenceContext
    private EntityManager em;

    private final DedupeProperties props;

    /** Throws DuplicateDetectionException if a match is found. Otherwise returns silently. */
    @Transactional(readOnly = true)
    public void assertNotDuplicate(ScriptFingerprint.Fingerprint fp) {
        // Stage 1: exact hash
        Optional<UUID> exact = findExact(fp.contentHash());
        if (exact.isPresent()) {
            throw new DuplicateDetectionException(exact.get(), 0, 1.0, true);
        }

        // Stage 2: SimHash Hamming distance over recent window
        int maxHamming = (int) Math.floor((1.0 - props.similarityThreshold()) * 64);
        // similarityThreshold=0.80 -> maxHamming=12; reject if Hamming <= 12
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT id, simhash
                FROM (
                    SELECT id, simhash FROM scripts
                    WHERE simhash IS NOT NULL
                    ORDER BY created_at DESC
                    LIMIT :window
                ) recent
                WHERE bit_count((simhash # :probe)::bit(64)) <= :maxHam
                ORDER BY bit_count((simhash # :probe)::bit(64)) ASC
                LIMIT 1
                """, Tuple.class)
                .setParameter("window", props.lookbackWindow())
                .setParameter("probe", fp.simhash())
                .setParameter("maxHam", maxHamming)
                .getResultList();

        if (!rows.isEmpty()) {
            Tuple t = rows.get(0);
            UUID conflictId = (UUID) t.get(0);
            long otherSig = ((Number) t.get(1)).longValue();
            int hamming = SimHash.hamming(fp.simhash(), otherSig);
            double sim = 1.0 - (hamming / 64.0);
            log.warn("Near-duplicate detected: hamming={} similarity={} vs script={}",
                    hamming, sim, conflictId);
            throw new DuplicateDetectionException(conflictId, hamming, sim, false);
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<UUID> findExact(String contentHash) {
        List<UUID> ids = em.createNativeQuery(
                "SELECT id FROM scripts WHERE content_hash = :h LIMIT 1")
                .setParameter("h", contentHash)
                .getResultList();
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
