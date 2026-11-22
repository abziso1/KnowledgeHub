package com.knowledgehub.media;

import static com.knowledgehub.media.ImageRepository.utc;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageCleanupQueue {

    record Job(String key, int attempts, Instant leasedUntil) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;

    ImageCleanupQueue(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueUnreferenced(UUID libraryId, Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return;
        }
        var now = utc(clock.instant());
        named.update("""
                with queued as (
                    update images i set state = 'QUEUED'
                    where i.library_id = :library and i.id in (:ids)
                        and (i.state = 'REFERENCED' or (i.state = 'TEMPORARY' and i.expires_at <= :now))
                        and not exists (
                            select 1 from note_image_references r
                            where r.library_id = i.library_id and r.image_id = i.id
                        )
                    returning i.blob_key
                )
                insert into image_cleanup_jobs (blob_key, next_attempt_at)
                select blob_key, :now from queued on conflict (blob_key) do nothing
                """, Map.of("library", libraryId, "ids", candidateIds, "now", now));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueLibrary(UUID libraryId) {
        jdbc.update("""
                with queued as (
                    update images set state = 'QUEUED' where library_id = ? and state <> 'QUEUED'
                    returning blob_key
                )
                insert into image_cleanup_jobs (blob_key, next_attempt_at)
                select blob_key, ? from queued on conflict (blob_key) do nothing
                """, libraryId, utc(clock.instant()));
    }

    void enqueueUnpublished(String key) {
        jdbc.update("""
                insert into image_cleanup_jobs (blob_key, next_attempt_at) values (?, ?)
                on conflict (blob_key) do nothing
                """, key, utc(clock.instant()));
    }

    Optional<Job> claim() {
        var now = clock.instant();
        return jdbc.query("""
                with due as (
                    select blob_key from image_cleanup_jobs
                    where next_attempt_at <= ? and (leased_until is null or leased_until <= ?)
                    order by next_attempt_at, blob_key limit 1 for update skip locked
                )
                update image_cleanup_jobs j set leased_until = ? from due where j.blob_key = due.blob_key
                returning j.blob_key, j.attempts, j.leased_until
                """, (row, index) -> new Job(row.getString(1), row.getInt(2),
                row.getObject(3, OffsetDateTime.class).toInstant()), utc(now), utc(now), utc(now.plusSeconds(60)))
                .stream().findFirst();
    }

    @Transactional
    public boolean complete(Job job) {
        int removed = jdbc.update("delete from image_cleanup_jobs where blob_key = ? and leased_until = ?",
                job.key(), utc(job.leasedUntil()));
        if (removed == 0) {
            return false;
        }
        jdbc.update("delete from images where blob_key = ? and state = 'QUEUED'", job.key());
        return true;
    }

    void retry(Job job) {
        int delay = Math.min(3600, 5 << Math.min(job.attempts(), 10));
        jdbc.update("""
                update image_cleanup_jobs set attempts = attempts + 1, next_attempt_at = ?, leased_until = null
                where blob_key = ? and leased_until = ?
                """, utc(clock.instant().plusSeconds(delay)), job.key(), utc(job.leasedUntil()));
    }
}
