'use client';

import React, { useState, useEffect, useCallback, useRef, memo } from 'react';
import { getClientLocale } from '@/lib/utils/locale';
import { Star, Trash2, Loader2, MessageCircle, Pencil, Info, ChevronLeft, ChevronRight, AlertCircle, Flag, Mail, Copy, Check } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { cn } from '@/lib/utils';
import { useAuth } from '@/lib/providers/smart-providers';
import { publicationService } from '@/lib/api/orchestrator/publication.service';
import type { WorkflowPublication, PublicationReview } from '@/lib/api/orchestrator/types';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { UserActionMenu } from '@/components/profile/UserActionMenu';
import { CredentialWizard } from '@/components/credentials/CredentialWizard';
import { useMissingCredentials } from '@/hooks/useMissingCredentials';
import { parseUtcAware } from '@/lib/utils/dateFormatters';
import { ApplicationActivationButton } from '@/components/applications/ApplicationActivationButton';
import { cloudWebUrl } from '@/lib/edition';

interface PublicationInfoPanelProps {
  publication: WorkflowPublication;
  className?: string;
  defaultOpen?: boolean;
  floating?: boolean;
  /**
   * Cloned workflow id for an acquired application. When provided, the panel
   * runs the missing-credential check and surfaces a "Setup required" block
   * + amber badge so the user can connect services without leaving the app
   * page. Pass {@code undefined} for anonymous marketplace previews and
   * publisher self-views.
   */
  acquiredWorkflowId?: string;
  /**
   * When {@code true}, suppress the inline {@link ApplicationActivationButton}
   * mount on the Info tab. Used by the application chat page where the
   * activation toggle lives next to the Logs button in the chat header instead
   * (single source of truth - avoids duplicate buttons on the same page).
   */
  hideActivationButton?: boolean;
  /**
   * The publication is sourced from the CLOUD marketplace (a cloud-linked CE
   * rendering remote content). Publisher / reviewer ids are then cloud user ids
   * absent from this install's auth DB, so: (1) the avatar loads through the
   * cloud proxy (see {@link PublisherAvatar} `remote`), and (2) the "Send
   * message" affordance is hidden - a DM opened here could never reach the cloud
   * user (see {@link UserActionMenu}). Default false (local). Mirrors the
   * `remote` flag on marketplace cards.
   */
  remote?: boolean;
}

const StarRating = memo(function StarRating({ rating, interactive, onRate, size = 'sm' }: {
  rating: number;
  interactive?: boolean;
  onRate?: (rating: number) => void;
  size?: 'xs' | 'sm';
}) {
  const [hovered, setHovered] = useState(0);
  const iconClass = size === 'xs' ? 'w-3 h-3' : 'w-3.5 h-3.5';

  return (
    <div className="flex items-center gap-0.5">
      {[1, 2, 3, 4, 5].map(i => (
        <button
          key={i}
          type="button"
          disabled={!interactive}
          className={cn(
            'p-0 border-0 bg-transparent',
            interactive ? 'cursor-pointer hover:scale-110 transition-transform' : 'cursor-default'
          )}
          onMouseEnter={() => interactive && setHovered(i)}
          onMouseLeave={() => interactive && setHovered(0)}
          onClick={() => interactive && onRate?.(i)}
        >
          <Star
            className={cn(
              iconClass,
              (hovered || rating) >= i
                ? 'fill-amber-400 text-amber-400'
                : 'fill-gray-200 text-gray-400 dark:fill-none dark:text-gray-600'
            )}
          />
        </button>
      ))}
    </div>
  );
});

function useRelativeTimeFormatter() {
  const locale = getClientLocale();
  return useCallback((dateStr: string): string => {
    const now = Date.now();
    const date = parseUtcAware(dateStr).getTime();
    const diffSec = Math.floor((now - date) / 1000);

    try {
      const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto', style: 'narrow' });
      if (diffSec < 60) return rtf.format(-diffSec, 'second');
      const diffMin = Math.floor(diffSec / 60);
      if (diffMin < 60) return rtf.format(-diffMin, 'minute');
      const diffHr = Math.floor(diffMin / 60);
      if (diffHr < 24) return rtf.format(-diffHr, 'hour');
      const diffDay = Math.floor(diffHr / 24);
      if (diffDay < 30) return rtf.format(-diffDay, 'day');
      const diffMonth = Math.floor(diffDay / 30);
      return rtf.format(-diffMonth, 'month');
    } catch {
      const diffMin = Math.floor(diffSec / 60);
      if (diffMin < 1) return 'now';
      if (diffMin < 60) return `${diffMin}m`;
      const diffHr = Math.floor(diffMin / 60);
      if (diffHr < 24) return `${diffHr}h`;
      const diffDay = Math.floor(diffHr / 24);
      return `${diffDay}d`;
    }
  }, [locale]);
}

// ============================================================================
// Reply components
// ============================================================================

function ReplyItem({ reply, isOwn, publicationId, onUpdated, onDeleted, formatTimeAgo, t, remote = false }: {
  reply: PublicationReview;
  isOwn: boolean;
  publicationId: string;
  onUpdated: (updated: PublicationReview) => void;
  onDeleted: (id: string) => void;
  formatTimeAgo: (dateStr: string) => string;
  t: (key: string) => string;
  /** Cloud-sourced reviewer (cloud-linked CE) - hide the DM action, see UserActionMenu. */
  remote?: boolean;
}) {
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState(reply.comment || '');
  const [saving, setSaving] = useState(false);
  const [deletingReply, setDeletingReply] = useState(false);

  const handleSaveEdit = async () => {
    if (!editText.trim()) return;
    setSaving(true);
    try {
      const updated = await publicationService.updateReply(publicationId, reply.id, editText.trim());
      onUpdated(updated);
      setEditing(false);
    } catch { /* silent */ } finally {
      setSaving(false);
    }
  };

  const handleDeleteReply = async () => {
    setDeletingReply(true);
    try {
      await publicationService.deleteReply(publicationId, reply.id);
      onDeleted(reply.id);
    } catch { /* silent */ } finally {
      setDeletingReply(false);
    }
  };

  return (
    <div className={cn(
      'ml-4 p-2 rounded-lg',
      isOwn
        ? 'bg-blue-50/40 dark:bg-blue-900/10 ring-1 ring-blue-200/50 dark:ring-blue-800/20'
        : 'bg-gray-50/30 dark:bg-gray-700/10'
    )}>
      <div className="flex items-center gap-1 mb-0.5">
        <UserActionMenu userId={reply.reviewerId} remote={remote}>
          <PublisherAvatar userId={reply.reviewerId} name={reply.reviewerName} size={14} variant="neutral" />
          <span className="text-sm text-gray-700 dark:text-gray-200 truncate">
            {reply.reviewerName || 'Anonymous'}
          </span>
        </UserActionMenu>
        <span className="text-xs text-gray-400 dark:text-gray-500">&middot;</span>
        <span className="text-xs text-gray-400 dark:text-gray-500 flex-shrink-0">
          {formatTimeAgo(reply.createdAt)}
        </span>
        {isOwn && !editing && (
          <div className="ml-auto flex items-center gap-1">
            <button type="button" onClick={() => { setEditing(true); setEditText(reply.comment || ''); }}
              className="p-0.5 text-gray-400 hover:text-blue-500 transition-colors">
              <Pencil className="w-2.5 h-2.5" />
            </button>
            <button type="button" onClick={handleDeleteReply} disabled={deletingReply}
              className="p-0.5 text-gray-400 hover:text-red-500 transition-colors">
              {deletingReply ? <Loader2 className="w-2.5 h-2.5 animate-spin" /> : <Trash2 className="w-2.5 h-2.5" />}
            </button>
          </div>
        )}
      </div>
      {editing ? (
        <div className="mt-1">
          <Textarea value={editText} onChange={e => setEditText(e.target.value)} maxLength={2000} rows={2}
            className="min-h-0 text-sm resize-none"
          />
          <div className="flex gap-1.5 mt-1">
            <Button variant="default" size="sm" onClick={handleSaveEdit} disabled={saving || !editText.trim()}
              className="h-7 px-2.5 text-sm">
              {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : t('updateReview')}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setEditing(false)}
              className="h-7 px-2 text-sm">
              {t('cancel')}
            </Button>
          </div>
        </div>
      ) : (
        <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed whitespace-pre-wrap">{reply.comment}</p>
      )}
    </div>
  );
}

function ReviewItem({ review, isOwn, publicationId, userId, formatTimeAgo, t, tMarketplace, remote = false, onCommentDeleted }: {
  review: PublicationReview;
  isOwn: boolean;
  publicationId: string;
  userId?: string;
  formatTimeAgo: (dateStr: string) => string;
  t: (key: string) => string;
  tMarketplace: (key: string) => string;
  /** Cloud-sourced reviewer (cloud-linked CE) - hide the DM action, see UserActionMenu. */
  remote?: boolean;
  /**
   * Called after the current user deletes THEIR OWN comment (rating untouched).
   * The parent refetches so the now-comment-less entry leaves the Comments list
   * and the Info-tab rating/form state stays in sync.
   */
  onCommentDeleted?: () => void | Promise<void>;
}) {
  const [showReplies, setShowReplies] = useState(false);
  const [replies, setReplies] = useState<PublicationReview[]>([]);
  const [repliesLoaded, setRepliesLoaded] = useState(false);
  const [loadingReplies, setLoadingReplies] = useState(false);
  const [replyCount, setReplyCount] = useState(review.replyCount ?? 0);

  // Inline reply form
  const [showReplyForm, setShowReplyForm] = useState(false);
  const [replyText, setReplyText] = useState('');
  const [submittingReply, setSubmittingReply] = useState(false);

  // Deleting the user's OWN comment (top-level). Removes only the comment - the
  // star rating, owned by the Info tab, is left untouched (separate concern).
  const [deletingComment, setDeletingComment] = useState(false);
  const handleDeleteComment = async () => {
    setDeletingComment(true);
    try {
      await publicationService.deleteComment(publicationId);
      await onCommentDeleted?.();
    } catch { /* silent */ } finally {
      setDeletingComment(false);
    }
  };

  const toggleReplies = async () => {
    if (!showReplies && !repliesLoaded) {
      setLoadingReplies(true);
      try {
        const res = await publicationService.getReplies(publicationId, review.id);
        setReplies(res.replies);
        setRepliesLoaded(true);
      } catch { /* silent */ }
      setLoadingReplies(false);
    }
    setShowReplies(!showReplies);
  };

  const handleSubmitReply = async () => {
    if (!replyText.trim()) return;
    setSubmittingReply(true);
    try {
      const saved = await publicationService.submitReply(publicationId, review.id, replyText.trim());
      setReplies(prev => [...prev, saved]);
      setReplyCount(prev => prev + 1);
      setReplyText('');
      setShowReplyForm(false);
      setShowReplies(true);
      setRepliesLoaded(true);
    } catch { /* silent */ } finally {
      setSubmittingReply(false);
    }
  };

  const handleReplyUpdated = (updated: PublicationReview) => {
    setReplies(prev => prev.map(r => r.id === updated.id ? updated : r));
  };

  const handleReplyDeleted = (id: string) => {
    setReplies(prev => prev.filter(r => r.id !== id));
    setReplyCount(prev => Math.max(0, prev - 1));
  };

  return (
    <div className={cn(
      'p-2.5 rounded-xl',
      isOwn
        ? 'bg-blue-50/50 dark:bg-blue-900/10 ring-1 ring-blue-200 dark:ring-blue-800/30'
        : 'bg-gray-50/50 dark:bg-gray-700/20'
    )}>
      {/* Review header */}
      <div className="flex items-center gap-1.5 mb-1">
        <div className="flex items-center gap-1 min-w-0">
          <UserActionMenu userId={review.reviewerId} remote={remote}>
            <PublisherAvatar userId={review.reviewerId} name={review.reviewerName} size={16} variant="neutral" />
            <span className="text-sm font-medium text-gray-700 dark:text-gray-200 truncate">
              {review.reviewerName || tMarketplace('anonymous')}
            </span>
          </UserActionMenu>
        </div>
        {isOwn && (
          <span className="px-1.5 py-0.5 rounded text-[10px] font-medium leading-none bg-blue-100 dark:bg-blue-900/40 text-blue-600 dark:text-blue-300 flex-shrink-0">
            {t('you')}
          </span>
        )}
        {review.rating != null && <StarRating rating={review.rating} size="xs" />}
        <span className="text-xs text-gray-300 dark:text-gray-600" aria-hidden="true">&middot;</span>
        <span className="text-xs text-gray-400 dark:text-gray-500 flex-shrink-0">
          {formatTimeAgo(review.createdAt)}
        </span>
        {/* Delete MY comment - lives on the comment itself (not next to the
            Info-tab stars). Removes only the comment; the rating is untouched. */}
        {isOwn && (
          <button type="button" onClick={handleDeleteComment} disabled={deletingComment}
            title={t('deleteComment')} aria-label={t('deleteComment')}
            className="ml-auto flex-shrink-0 p-1 rounded text-gray-400 hover:text-red-500 hover:bg-gray-100 dark:hover:bg-gray-700/50 transition-colors">
            {deletingComment ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Trash2 className="w-3.5 h-3.5" />}
          </button>
        )}
      </div>

      {/* Review comment */}
      {review.comment && (
        <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed whitespace-pre-wrap">{review.comment}</p>
      )}

      {/* Reply controls */}
      <div className="flex items-center gap-2 mt-1.5">
        <button type="button" onClick={() => setShowReplyForm(!showReplyForm)}
          className="flex items-center gap-1 text-xs text-gray-400 hover:text-blue-500 dark:hover:text-blue-400 transition-colors">
          <MessageCircle className="w-3 h-3" />
          {t('reply')}
        </button>
        {replyCount > 0 && (
          <button type="button" onClick={toggleReplies}
            className="text-xs text-blue-500 hover:text-blue-600 dark:text-blue-400 dark:hover:text-blue-300 transition-colors">
            {loadingReplies ? (
              <Loader2 className="w-3 h-3 animate-spin inline" />
            ) : showReplies ? t('hideReplies') : `${t('showReplies')} (${replyCount})`}
          </button>
        )}
      </div>

      {/* Inline reply form */}
      {showReplyForm && (
        <div className="mt-2 ml-4">
          <Textarea value={replyText} onChange={e => setReplyText(e.target.value)}
            placeholder={t('writeReply')} maxLength={2000} rows={2}
            className="min-h-0 text-xs resize-none"
          />
          <div className="flex gap-1.5 mt-1">
            <Button variant="default" size="sm" onClick={handleSubmitReply} disabled={!replyText.trim() || submittingReply}
              className="h-6 px-2.5 text-xs">
              {submittingReply ? <Loader2 className="w-3 h-3 animate-spin" /> : t('submitReply')}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => { setShowReplyForm(false); setReplyText(''); }}
              className="h-6 px-2 text-xs">
              {t('cancel')}
            </Button>
          </div>
        </div>
      )}

      {/* Replies list */}
      {showReplies && replies.length > 0 && (
        <div className="mt-2 space-y-1.5">
          {replies.map(reply => (
            <ReplyItem
              key={reply.id}
              reply={reply}
              isOwn={!!userId && reply.reviewerId === userId}
              publicationId={publicationId}
              onUpdated={handleReplyUpdated}
              onDeleted={handleReplyDeleted}
              formatTimeAgo={formatTimeAgo}
              t={t}
              remote={remote}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Main component
// ============================================================================

export function PublicationInfoPanel({
  publication,
  className,
  defaultOpen = true,
  floating = false,
  acquiredWorkflowId,
  hideActivationButton = false,
  remote = false,
}: PublicationInfoPanelProps) {
  const t = useTranslations('reviews');
  const tMarketplace = useTranslations('marketplace');
  const tApplications = useTranslations('applications.setup');
  const formatTimeAgo = useRelativeTimeFormatter();
  const { numericUserId } = useAuth();
  const currentUserId = numericUserId != null ? String(numericUserId) : undefined;
  const isPublisher = !!currentUserId && currentUserId === publication.publisherId;
  const canReview = !!currentUserId && !isPublisher;

  // Detect missing credentials. Hook is no-op when acquiredWorkflowId is undefined.
  const missingCreds = useMissingCredentials({
    workflowId: acquiredWorkflowId,
    planSnapshot: publication.planSnapshot ?? undefined,
    enabled: !!acquiredWorkflowId,
  });
  const hasMissingCreds = missingCreds.count > 0;

  // Auto-expand once when count > 0 surfaces - the trigger button alone is
  // too easy to miss for a state that gates app functionality. Subsequent
  // closures by the user are respected (we don't fight them).
  const autoExpandedRef = useRef(false);
  useEffect(() => {
    if (hasMissingCreds && !autoExpandedRef.current) {
      autoExpandedRef.current = true;
      setIsOpen(true);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hasMissingCreds]);

  const [setupWizardOpen, setSetupWizardOpen] = useState(false);

  const [isOpen, setIsOpen] = useState(defaultOpen);
  const floatingRef = useRef<HTMLDivElement | null>(null);

  // Escape key + click-outside to close (floating mode only)
  useEffect(() => {
    if (!floating || !isOpen) return;
    const handleKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setIsOpen(false);
    };
    const handleClick = (e: MouseEvent) => {
      const target = e.target as Node | null;
      if (!target) return;
      if (floatingRef.current && floatingRef.current.contains(target)) return;
      // The publisher "View profile / Send message" menu (UserActionMenu) is a
      // Radix Popover portaled to document.body, so a click inside it lands
      // OUTSIDE floatingRef. Without this guard the mousedown closes the panel
      // before the menu item's own click fires: the panel - and the portaled
      // menu with it - unmount, so the navigation never runs. Treat clicks
      // inside any portaled popper as inside the panel.
      if (target instanceof Element && target.closest('[data-radix-popper-content-wrapper]')) {
        return;
      }
      setIsOpen(false);
    };
    document.addEventListener('keydown', handleKey);
    document.addEventListener('mousedown', handleClick);
    return () => {
      document.removeEventListener('keydown', handleKey);
      document.removeEventListener('mousedown', handleClick);
    };
  }, [floating, isOpen]);
  const [activeTab, setActiveTab] = useState('info');
  const [reviews, setReviews] = useState<PublicationReview[]>([]);
  // Comments tab badge count - populated lazily on mount via getCommentCount and
  // kept in sync after submit/delete. Distinct from `publication.reviewCount`
  // which counts votes (rating-only entries) and drives the Info tab average.
  const [commentCount, setCommentCount] = useState<number>(0);
  const [myReview, setMyReview] = useState<PublicationReview | null>(null);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [commentsLoaded, setCommentsLoaded] = useState(false);

  // Review form state
  const [formRating, setFormRating] = useState(0);
  const [formComment, setFormComment] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  // Report tab state - transient "Link copied" badge after the copy button is
  // clicked. Resets after 2s. Builds the mailto + page URL lazily inside the
  // tab's render block (no SSR/window access at module scope).
  const [reportLinkCopied, setReportLinkCopied] = useState(false);

  // Clear error if user turns out to be publisher
  useEffect(() => {
    if (isPublisher) setSubmitError(null);
  }, [isPublisher]);

  // Authenticated user id (numeric, matches backend-side reviewer_id).
  const userId = currentUserId;

  const loadReviews = useCallback(async (p: number = 0) => {
    setLoading(true);
    try {
      // Comments tab shows entries with a real comment only - rating-only votes
      // appear on the Info tab via the average + vote count, never as a blank
      // "comment". totalElements is the comment count, used to keep the badge in
      // sync after submit/delete without an extra round-trip.
      const res = await publicationService.getReviews(publication.id, p, 10, { onlyWithComment: true });
      setReviews(res.reviews);
      setTotalPages(res.totalPages);
      setPage(p);
      setCommentCount(res.totalElements ?? 0);
    } catch { /* silent */ } finally {
      setLoading(false);
    }
  }, [publication.id]);

  // Comment count for the tab badge - loaded once on mount so the badge is
  // accurate before the user opens the Comments tab. Subsequent submit/delete
  // operations refresh it via loadReviews → totalElements.
  useEffect(() => {
    let cancelled = false;
    publicationService.getCommentCount(publication.id).then(c => {
      if (!cancelled) setCommentCount(c);
    });
    return () => { cancelled = true; };
  }, [publication.id]);

  const loadMyReview = useCallback(async () => {
    try {
      const review = await publicationService.getMyReview(publication.id);
      setMyReview(review);
      // Always sync the form state to the loaded review, including the null case
      // (review fully removed) so a stale rating/comment never lingers in the
      // form after a partial delete (clear comment / clear rating).
      setFormRating(review?.rating ?? 0);
      setFormComment(review?.comment || '');
    } catch { /* silent */ }
  }, [publication.id]);

  // Load myReview on mount (skip for publisher - they can't review)
  const [myReviewLoaded, setMyReviewLoaded] = useState(false);
  useEffect(() => {
    if (!myReviewLoaded && canReview) {
      setMyReviewLoaded(true);
      loadMyReview();
    }
  }, [myReviewLoaded, canReview, loadMyReview]);

  // Lazy load review list when Comments tab first activated
  useEffect(() => {
    if (activeTab === 'comments' && !commentsLoaded) {
      setCommentsLoaded(true);
      loadReviews();
    }
  }, [activeTab, commentsLoaded, loadReviews]);

  // Auto-submit rating on star click (rating only)
  const handleRateAndSubmit = useCallback(async (rating: number) => {
    setFormRating(rating);
    setSubmitError(null);
    setSubmitting(true);
    try {
      const saved = await publicationService.submitReview(publication.id, { rating });
      setMyReview(saved);
      setFormRating(saved.rating ?? 0);
      if (commentsLoaded) await loadReviews(0);
    } catch (err: any) {
      const msg = err?.details?.error || err?.message || 'Failed to submit review';
      setSubmitError(msg);
      console.error('Failed to submit review:', err);
    } finally {
      setSubmitting(false);
    }
  }, [publication.id, commentsLoaded, loadReviews]);

  // Submit comment independently (comment only)
  const handleSubmitComment = async () => {
    if (!formComment.trim()) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const saved = await publicationService.submitReview(publication.id, {
        comment: formComment.trim(),
      });
      setMyReview(saved);
      setFormComment(saved.comment || '');
      await loadReviews(0);
    } catch (err: any) {
      const msg = err?.details?.error || err?.message || 'Failed to submit review';
      setSubmitError(msg);
      console.error('Failed to submit review:', err);
    } finally {
      setSubmitting(false);
    }
  };

  // Remove ONLY the rating (Info tab). Keeps any comment the user posted - the
  // comment is deleted from the Comments tab instead. Refetch to resync state:
  // the review row may survive (comment kept, rating null) or be gone entirely
  // (was rating-only).
  const handleRemoveRating = async () => {
    setDeleting(true);
    try {
      await publicationService.deleteRating(publication.id);
      await loadMyReview();
      if (commentsLoaded) await loadReviews(page);
    } catch (err) {
      console.error('Failed to remove rating:', err);
    } finally {
      setDeleting(false);
    }
  };

  // Refetch hook handed to each own ReviewItem: after the user deletes their own
  // comment, resync the Info-tab form/rating (the row may keep its rating) and
  // reload page 0 of the comment list (the now-comment-less entry drops out).
  const handleOwnCommentDeleted = useCallback(async () => {
    await loadMyReview();
    await loadReviews(0);
  }, [loadMyReview, loadReviews]);

  const avgRating = publication.averageRating ?? 0;
  const reviewCount = publication.reviewCount ?? 0;

  // Wizard mounts as a sibling so it stays on screen when the user
  // collapses the panel mid-flow. Refetch on each successful add so the
  // amber badge decrements progressively.
  const wizardElement = missingCreds.wizardable.length > 0 ? (
    <CredentialWizard
      open={setupWizardOpen}
      onOpenChange={setSetupWizardOpen}
      requirements={missingCreds.wizardable.map((w) => ({
        iconSlug: w.iconSlug,
        serviceName: w.serviceName,
        toolId: w.toolId,
      }))}
      onComplete={() => {
        setSetupWizardOpen(false);
        void missingCreds.refetch();
      }}
      onCredentialAdded={() => {
        void missingCreds.refetch();
      }}
    />
  ) : null;

  const triggerButton = (
    <button
      type="button"
      onClick={() => setIsOpen(true)}
      aria-label={hasMissingCreds ? tApplications('indicatorLabel', { count: missingCreds.count }) : t('title')}
      title={hasMissingCreds ? tApplications('indicatorLabel', { count: missingCreds.count }) : t('title')}
      className={cn(
        'relative h-8 p-0 rounded-full flex items-center justify-center',
        'backdrop-blur-sm transition-all duration-200 animate-[fadeIn_0.5s_ease-in-out]',
        // Amber emphasis when an action is required so the icon reads as a
        // state change, not decoration.
        hasMissingCreds
          ? 'w-auto px-2.5 gap-1.5 bg-amber-100/95 dark:bg-amber-900/40 text-amber-800 dark:text-amber-200 ring-1 ring-amber-300 dark:ring-amber-700 hover:bg-amber-100 dark:hover:bg-amber-900/60'
          : 'w-8 bg-white/40 dark:bg-gray-800/40 text-gray-400 dark:text-gray-500 hover:bg-white/90 dark:hover:bg-gray-800/90 hover:text-gray-700 dark:hover:text-gray-200 opacity-80 hover:opacity-100',
        !floating && className
      )}
    >
      <Info className="h-3.5 w-3.5" />
      {hasMissingCreds && (
        <span className="text-[11px] font-semibold leading-none">{missingCreds.count}</span>
      )}
    </button>
  );

  const expandedPanel = (
    <div
      className={cn(
        'bg-white/85 dark:bg-gray-800/85 backdrop-blur-md transition-all flex flex-col border',
        // Amber border when something needs attention, mirroring the trigger badge.
        hasMissingCreds
          ? 'border-amber-300 dark:border-amber-700/70'
          : 'border-gray-200/60 dark:border-gray-700/60',
        'w-[340px] max-h-[50vh] rounded-2xl shadow-sm',
        !floating && className
      )}
    >
      {/* Header row - close button */}
      <div className="flex items-center justify-end px-2 py-1.5 flex-shrink-0">
        <Button variant="ghost" size="sm" onClick={() => setIsOpen(false)} aria-label={t('cancel')} className="h-6 w-6 p-0">
          <span className="text-sm leading-none" aria-hidden="true">&times;</span>
        </Button>
      </div>

      {/* Expanded content */}
      <div className="overflow-y-auto flex-1 min-h-0" onClick={e => e.stopPropagation()}>
        <Tabs value={activeTab} onValueChange={v => { setActiveTab(v); setSubmitError(null); }}>
          <TabsList className="w-full h-8 rounded-none bg-transparent border-b border-gray-100 dark:border-gray-700/50 p-0">
            <TabsTrigger value="info"
              className="flex-1 h-8 rounded-none text-xs font-medium data-[state=active]:shadow-none data-[state=active]:bg-transparent data-[state=active]:border-b-2 data-[state=active]:border-gray-900 dark:data-[state=active]:border-gray-100 data-[state=active]:text-gray-900 dark:data-[state=active]:text-gray-100 text-gray-500 dark:text-gray-400">
              {t('infoTab')}
            </TabsTrigger>
            <TabsTrigger value="comments"
              className="flex-1 h-8 rounded-none text-xs font-medium data-[state=active]:shadow-none data-[state=active]:bg-transparent data-[state=active]:border-b-2 data-[state=active]:border-gray-900 dark:data-[state=active]:border-gray-100 data-[state=active]:text-gray-900 dark:data-[state=active]:text-gray-100 text-gray-500 dark:text-gray-400">
              {t('commentsTab')}
              {commentCount > 0 && (
                <span className="ml-1 px-1 py-0.5 text-xs bg-gray-100 dark:bg-gray-700 rounded text-gray-500 dark:text-gray-400 leading-none">
                  {commentCount}
                </span>
              )}
            </TabsTrigger>
            <TabsTrigger value="report"
              className="flex-1 h-8 rounded-none text-xs font-medium data-[state=active]:shadow-none data-[state=active]:bg-transparent data-[state=active]:border-b-2 data-[state=active]:border-gray-900 dark:data-[state=active]:border-gray-100 data-[state=active]:text-gray-900 dark:data-[state=active]:text-gray-100 text-gray-500 dark:text-gray-400">
              <Flag className="w-3 h-3 mr-1 inline" aria-hidden="true" />
              {t('reportTab')}
            </TabsTrigger>
          </TabsList>

          {/* Info tab - description + publisher/rating footer */}
          <TabsContent value="info" className="mt-0">
            <div className="px-3 py-3 space-y-3">
              {/* Setup-required block - shown only for acquired apps with
                  unconnected credentials. Sits above the description so the
                  user sees the call-to-action first. */}
              {hasMissingCreds && (
                <div className="rounded-lg border border-amber-200 dark:border-amber-700/60 bg-amber-50 dark:bg-amber-900/20 p-3 space-y-2">
                  <div className="flex items-start gap-2">
                    <AlertCircle className="w-4 h-4 text-amber-600 dark:text-amber-400 mt-0.5 shrink-0" />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-amber-900 dark:text-amber-100 leading-snug">
                        {tApplications('title', { count: missingCreds.count })}
                      </div>
                      {missingCreds.wizardable.length > 0 && (
                        <div className="mt-1 text-xs text-amber-800/90 dark:text-amber-200/80">
                          {missingCreds.wizardable.map((w) => w.serviceName).join(' · ')}
                        </div>
                      )}
                    </div>
                  </div>
                  {missingCreds.wizardable.length > 0 && (
                    <Button
                      size="sm"
                      onClick={() => setSetupWizardOpen(true)}
                      className="w-full h-7 text-xs"
                    >
                      {tApplications('connectButton', { count: missingCreds.wizardable.length })}
                    </Button>
                  )}
                  {missingCreds.manual.length > 0 && (
                    <p className="text-[11px] text-amber-700/80 dark:text-amber-300/80 leading-snug">
                      {tApplications('manualHint')}
                    </p>
                  )}
                </div>
              )}

              {/* Activer/Désactiver - only when the viewer has an acquired clone (own
                  application) AND credentials are not blocking. Renders the
                  Activer/Désactiver toggle that pins/unpins the underlying
                  workflow per design v3.5 §L1 (single-run UX, no version picker). */}
              {acquiredWorkflowId && !hasMissingCreds && !hideActivationButton && (
                <div className="pt-1">
                  <ApplicationActivationButton workflowId={acquiredWorkflowId} />
                </div>
              )}

              {/* Description */}
              {publication.description ? (
                <p className="text-sm text-gray-700 dark:text-gray-300 leading-relaxed whitespace-pre-wrap">
                  {publication.description}
                </p>
              ) : (
                <p className="text-xs text-gray-400 dark:text-gray-500 text-center py-3">{t('noDescription')}</p>
              )}

              {/* Publisher + rating footer */}
              <div className="flex items-center gap-1.5 flex-wrap pt-1 border-t border-gray-100 dark:border-gray-700/50">
                {publication.publisherName && (
                  <>
                    <UserActionMenu userId={publication.publisherId} remote={remote}>
                      <PublisherAvatar userId={publication.publisherId} name={publication.publisherName} size={16} variant="neutral" remote={remote} />
                      <span className="text-xs text-gray-500 dark:text-gray-400">{publication.publisherName}</span>
                    </UserActionMenu>
                    <span className="text-xs text-gray-300 dark:text-gray-600">&middot;</span>
                  </>
                )}
                {canReview ? (
                  <div className="flex items-center gap-2 flex-wrap">
                    {/* Current average (read-only) so the reviewer sees the consensus
                        while casting their own vote - not only after they've voted. */}
                    {reviewCount > 0 && (
                      <span className="inline-flex items-center gap-1.5">
                        <StarRating rating={avgRating} size="xs" />
                        <span className="text-xs text-gray-500 dark:text-gray-400">{avgRating.toFixed(1)}</span>
                        <span className="text-xs text-gray-400 dark:text-gray-500">
                          ({reviewCount} {reviewCount === 1 ? t('voter') : t('voters')})
                        </span>
                        <span className="text-xs text-gray-300 dark:text-gray-600">&middot;</span>
                      </span>
                    )}
                    {/* The viewer's own vote (interactive). */}
                    <span className="inline-flex items-center gap-1.5">
                      <StarRating rating={formRating} interactive onRate={handleRateAndSubmit} size="xs" />
                      {submitting && <Loader2 className="w-3 h-3 animate-spin text-gray-400" />}
                      {/* Remove ONLY the rating - distinct from deleting a comment,
                          which now lives on the comment in the Comments tab. Shown
                          only when there is a PERSISTED rating to retract (keyed to
                          myReview, not the optimistic formRating, so a failed star
                          submit never surfaces a remove control for an unsaved rating). */}
                      {(myReview?.rating ?? 0) > 0 && !submitting && (
                        <button type="button" onClick={handleRemoveRating} disabled={deleting}
                          title={t('removeRating')} aria-label={t('removeRating')}
                          className="p-0.5 text-gray-400 hover:text-red-500 transition-colors">
                          {deleting ? <Loader2 className="w-3 h-3 animate-spin" /> : <Trash2 className="w-3 h-3" />}
                        </button>
                      )}
                    </span>
                  </div>
                ) : reviewCount > 0 ? (
                  <div className="flex items-center gap-1.5">
                    <StarRating rating={avgRating} size="xs" />
                    <span className="text-xs text-gray-500 dark:text-gray-400">{avgRating.toFixed(1)}</span>
                    <span className="text-xs text-gray-400 dark:text-gray-500">
                      ({reviewCount} {reviewCount === 1 ? t('voter') : t('voters')})
                    </span>
                  </div>
                ) : (
                  <span className="text-xs text-gray-400 dark:text-gray-500">{t('noRatings')}</span>
                )}
              </div>
              {submitError && (
                <p className="text-xs text-red-500">{submitError}</p>
              )}
            </div>
          </TabsContent>

          {/* Comments tab - comment form + review list */}
          <TabsContent value="comments" className="mt-0">
            <div className="px-3 py-3">
              {/* Comment form (hidden for publisher). One comment per user: the
                  box is prefilled with the user's existing comment so it doubles
                  as the editor. Deleting a comment happens on the comment itself
                  in the list below (never next to the Info-tab stars). */}
              {canReview && (
                <div className="mb-3">
                  <Textarea
                    value={formComment}
                    onChange={e => setFormComment(e.target.value)}
                    placeholder={t('writeComment')}
                    maxLength={2000}
                    rows={2}
                    className="min-h-0 text-sm resize-none"
                  />
                  <div className="flex items-center justify-between gap-2 mt-1.5">
                    <span className="text-xs text-gray-400 dark:text-gray-500 tabular-nums">
                      {formComment.length}/2000
                    </span>
                    <Button variant="default" size="sm" onClick={handleSubmitComment} disabled={!formComment.trim() || submitting}
                      className="h-7 px-3 text-sm">
                      {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : (myReview?.comment ? t('updateReview') : t('submitReview'))}
                    </Button>
                  </div>
                  {submitError && (
                    <p className="mt-1.5 text-xs text-red-500">{submitError}</p>
                  )}
                </div>
              )}

              {/* Review list */}
              {loading ? (
                <div className="flex justify-center py-4">
                  <Loader2 className="w-4 h-4 animate-spin text-gray-400" />
                </div>
              ) : reviews.length === 0 ? (
                <p className="text-sm text-gray-400 dark:text-gray-500 text-center py-4">{t('noComments')}</p>
              ) : (
                <div className="space-y-2.5">
                  {reviews.map(review => (
                    <ReviewItem
                      key={review.id}
                      review={review}
                      isOwn={!!userId && review.reviewerId === userId}
                      publicationId={publication.id}
                      userId={userId}
                      formatTimeAgo={formatTimeAgo}
                      t={t}
                      tMarketplace={tMarketplace}
                      remote={remote}
                      onCommentDeleted={handleOwnCommentDeleted}
                    />
                  ))}

                  {/* Pagination */}
                  {totalPages > 1 && (
                    <div className="flex items-center justify-center gap-1 py-2">
                      <button type="button" onClick={() => loadReviews(page - 1)} disabled={page === 0}
                        className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]">
                        <ChevronLeft className="h-3.5 w-3.5" />
                      </button>
                      <span className="text-xs text-[var(--text-secondary)] font-medium min-w-[50px] text-center">
                        {page + 1} / {totalPages}
                      </span>
                      <button type="button" onClick={() => loadReviews(page + 1)} disabled={page >= totalPages - 1}
                        className="w-7 h-7 p-0 rounded-full transition-colors inline-flex items-center justify-center text-[var(--text-secondary)] hover:bg-[var(--text-primary)] hover:text-[var(--bg-primary)] disabled:opacity-30 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-[var(--text-secondary)]">
                        <ChevronRight className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          </TabsContent>

          {/* Report tab - notice-and-takedown entry point per Terms §9 (LCEN / EU DSA).
              Routes to /contact?category=abuse&message=… which lands the reporter
              on the captcha-gated contact form (Cloudflare rate-limited, audit
              trail in auth-service ContactService). The previous mailto: link
              silently no-op'd for users without a configured desktop mail client.
              In CE the marketplace renders CLOUD-hosted publications via the remote
              proxy, so every link here (report, page URL, terms) is rewritten onto
              the cloud origin by cloudWebUrl - a takedown for cloud content must
              reach the cloud operator, not the local self-hosted install. */}
          <TabsContent value="report" className="mt-0">
            {(() => {
              const pageUrl = typeof window !== 'undefined' ? cloudWebUrl(window.location.href) : '';
              const reportBody = [
                t('reportBodyPublication', { title: publication.title ?? t('reportUntitled') }),
                t('reportBodyId', { id: publication.id }),
                t('reportBodyUrl', { url: pageUrl }),
                t('reportBodyPublisher', {
                  publisher: publication.publisherName ?? publication.publisherId ?? t('reportUnknownPublisher'),
                }),
                '',
                t('reportBodyReasonHeading'),
                t('reportBodyReasonPlaceholder'),
                '',
                t('reportBodyReporterHeading'),
                t('reportBodyReporterPlaceholder'),
              ].join('\n');
              const reportHref = cloudWebUrl(`/contact?category=abuse&message=${encodeURIComponent(reportBody)}`);
              const handleCopyLink = async () => {
                // Only flip the "copied" feedback when the clipboard API actually
                // succeeded - older browsers / insecure contexts (http://) expose
                // no clipboard, in which case showing a green check would mislead
                // the user into thinking the link was copied when it wasn't.
                if (!navigator?.clipboard?.writeText) return;
                try {
                  await navigator.clipboard.writeText(pageUrl || publication.id);
                  setReportLinkCopied(true);
                  setTimeout(() => setReportLinkCopied(false), 2000);
                } catch { /* clipboard denied (permissions, focus loss) - silent */ }
              };
              return (
                <div className="px-3 py-3 space-y-3">
                  <div className="flex items-start gap-2">
                    <Flag className="w-4 h-4 text-theme-muted mt-0.5 shrink-0" aria-hidden="true" />
                    <div className="flex-1 min-w-0">
                      <div className="text-sm font-medium text-theme-primary leading-snug">
                        {t('reportTitle')}
                      </div>
                      <p className="mt-1 text-xs text-theme-secondary leading-relaxed">
                        {t('reportDescription')}
                      </p>
                    </div>
                  </div>

                  <Button asChild size="sm" className="w-full">
                    <a href={reportHref} target="_blank" rel="noopener noreferrer">
                      <Mail className="w-3.5 h-3.5" aria-hidden="true" />
                      {t('reportEmailButton')}
                    </a>
                  </Button>

                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={handleCopyLink}
                    className="w-full"
                  >
                    {reportLinkCopied
                      ? <Check className="w-3.5 h-3.5 text-emerald-500" aria-hidden="true" />
                      : <Copy className="w-3.5 h-3.5" aria-hidden="true" />}
                    {reportLinkCopied ? t('reportLinkCopied') : t('reportCopyLink')}
                  </Button>

                  <p className="pt-1 border-t border-theme text-[11px] text-theme-muted leading-relaxed">
                    <a
                      href={cloudWebUrl('/legal/terms')}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="underline hover:text-theme-secondary transition-colors"
                    >
                      {t('reportSeeTerms')}
                    </a>
                  </p>
                </div>
              );
            })()}
          </TabsContent>
        </Tabs>
      </div>
    </div>
  );

  if (floating) {
    return (
      <div ref={floatingRef} className={cn('relative inline-flex', className)}>
        {triggerButton}
        {isOpen && (
          <div className="absolute top-0 right-0 z-[50]">
            {expandedPanel}
          </div>
        )}
        {wizardElement}
      </div>
    );
  }

  return (
    <>
      {isOpen ? expandedPanel : triggerButton}
      {wizardElement}
    </>
  );
}
