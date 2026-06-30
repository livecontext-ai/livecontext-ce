package com.apimarketplace.orchestrator.repository;

import org.springframework.data.domain.AbstractPageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * {@link Pageable} that supports <b>arbitrary</b> offsets - not just multiples of
 * {@code limit}. Closes the silent-snap gap in {@code workflow.runs}: with stock
 * {@code PageRequest.of(offset / limit, limit)}, asking for {@code offset=33}
 * returned rows 25-49 labelled as page 1, which lies to the agent.
 *
 * <p>Spring Data's stock {@code PageRequest} computes {@code offset = page * size},
 * so any non-aligned offset rounds down silently. This subclass takes
 * {@code (offset, limit)} directly and overrides {@link #getOffset()} so Hibernate
 * calls {@code setFirstResult(offset)} with the agent-supplied value verbatim.
 *
 * <p>Lives in {@code orchestrator-service} (not {@code agent-common}) because it
 * depends on Spring Data - the helper itself stays Spring-free.
 */
public class OffsetLimitPageable extends AbstractPageRequest {

    private final long offset;

    public OffsetLimitPageable(long offset, int limit) {
        super(limit > 0 ? (int) Math.min(offset / Math.max(limit, 1), Integer.MAX_VALUE) : 0,
              Math.max(1, limit));
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        this.offset = offset;
    }

    public static OffsetLimitPageable of(long offset, int limit) {
        return new OffsetLimitPageable(offset, limit);
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetLimitPageable(offset + getPageSize(), getPageSize());
    }

    @Override
    public Pageable previous() {
        long prevOffset = Math.max(0, offset - getPageSize());
        return new OffsetLimitPageable(prevOffset, getPageSize());
    }

    @Override
    public Pageable first() {
        return new OffsetLimitPageable(0, getPageSize());
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimitPageable((long) pageNumber * getPageSize(), getPageSize());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OffsetLimitPageable other)) return false;
        return offset == other.offset && getPageSize() == other.getPageSize();
    }

    @Override
    public int hashCode() {
        return Long.hashCode(offset) * 31 + getPageSize();
    }

    @Override
    public String toString() {
        return "OffsetLimitPageable{offset=" + offset + ", limit=" + getPageSize() + "}";
    }
}
