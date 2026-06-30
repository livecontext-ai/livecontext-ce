package com.apimarketplace.conversation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A 1:1 direct-message thread between two user identities.
 *
 * <p><b>Identity-level, NOT workspace-scoped</b> (deliberately no organization_id):
 * a DM is a relationship between two people, independent of any workspace - two users
 * in different organizations still share ONE canonical thread. This is the explicit
 * exception to the org-scoped model used by {@link Conversation}; do NOT add an
 * {@code organization_id} or the {@code OrgScopedEntityListener} here.
 *
 * <p>Participants are stored normalised ({@code participantLo <= participantHi} by
 * string order via {@link #lo}/{@link #hi}) so the unique constraint dedups the pair.
 */
@Entity
@Table(name = "dm_threads", schema = "conversation")
public class DmThread {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "participant_lo", nullable = false, updatable = false)
    private String participantLo;

    @Column(name = "participant_hi", nullable = false, updatable = false)
    private String participantHi;

    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    /**
     * Per-participant "deleted from my inbox" timestamps (NULL = visible). Hiding is
     * one-sided and soft: messages are never destroyed, the OTHER participant keeps the
     * thread, and any new activity (open / message) un-hides it again.
     */
    @Column(name = "lo_hidden_at")
    private Instant loHiddenAt;

    @Column(name = "hi_hidden_at")
    private Instant hiHiddenAt;

    @Column(name = "last_message_preview", length = 280)
    private String lastMessagePreview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DmThread() {
    }

    /** Builds a thread for a pair, normalising the participants so (a,b) == (b,a). */
    public DmThread(String userA, String userB) {
        this.participantLo = lo(userA, userB);
        this.participantHi = hi(userA, userB);
    }

    /** The lexicographically smaller of the two ids - the dedup key's low side. */
    public static String lo(String a, String b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** The lexicographically larger of the two ids - the dedup key's high side. */
    public static String hi(String a, String b) {
        return a.compareTo(b) <= 0 ? b : a;
    }

    public boolean hasParticipant(String userId) {
        return participantLo.equals(userId) || participantHi.equals(userId);
    }

    /** The participant that is NOT {@code userId}, or null if {@code userId} isn't in the thread. */
    public String otherParticipant(String userId) {
        if (participantLo.equals(userId)) {
            return participantHi;
        }
        if (participantHi.equals(userId)) {
            return participantLo;
        }
        return null;
    }

    /** Is this thread hidden ("deleted") from {@code userId}'s inbox? */
    public boolean isHiddenFor(String userId) {
        if (participantLo.equals(userId)) {
            return loHiddenAt != null;
        }
        if (participantHi.equals(userId)) {
            return hiHiddenAt != null;
        }
        return false;
    }

    /** Hide the thread from {@code userId}'s inbox (no-op for a non-participant). */
    public void hideFor(String userId) {
        if (participantLo.equals(userId)) {
            loHiddenAt = Instant.now();
        } else if (participantHi.equals(userId)) {
            hiHiddenAt = Instant.now();
        }
    }

    /** Un-hide the thread for {@code userId} (no-op for a non-participant). */
    public void unhideFor(String userId) {
        if (participantLo.equals(userId)) {
            loHiddenAt = null;
        } else if (participantHi.equals(userId)) {
            hiHiddenAt = null;
        }
    }

    /** Un-hide for BOTH participants - new activity resurfaces the conversation. */
    public void unhideAll() {
        loHiddenAt = null;
        hiHiddenAt = null;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getParticipantLo() {
        return participantLo;
    }

    public String getParticipantHi() {
        return participantHi;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public String getLastMessagePreview() {
        return lastMessagePreview;
    }

    public void setLastMessagePreview(String lastMessagePreview) {
        this.lastMessagePreview = lastMessagePreview;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
