package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.entitlement.PlanFeatureGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Whether a person's plan includes email alerts, through the ordinary plan gate
 * ({@code feature:notification_email}, seeded at STARTER by V528), so an admin
 * moves the bar without a deploy and every non-cloud edition is never gated.
 *
 * <p><b>Credit alerts are exempt, on purpose.</b> They are the one email a Free
 * account must get: without it, its runs start failing with no explanation, and
 * the account leaves instead of topping up.
 *
 * <p><b>Whose plan decides: the RECIPIENT's.</b> Workspace capabilities normally follow the
 * workspace owner (the project docs), but an email alert is not a capability of a
 * workspace: it is sent to one person's inbox, and the product rule is "a Free account gets no
 * alert emails, except about its credits". Every alert already goes to a single person (a failed
 * run to the person who owns it, a task to its assignee), so the recipient's own plan is the
 * one that answers. A Free member of a paid team keeps the bell and the workspace channel.
 *
 * <p>Fails OPEN like every plan gate: no gate bean, a disabled gate or a failed
 * lookup all allow. The bell always has the notification anyway.
 */
@Component
public class NotificationEmailEntitlement {

    public static final String FEATURE_KEY = "feature:notification_email";

    private PlanFeatureGate planFeatureGate;

    @Autowired(required = false)
    public void setPlanFeatureGate(PlanFeatureGate planFeatureGate) {
        this.planFeatureGate = planFeatureGate;
    }

    public boolean allows(String tenantId, NotificationTopic topic) {
        return topic == NotificationTopic.CREDITS || requiredPlan(tenantId) == null;
    }

    /** The plan this person needs for email alerts, or null when their plan already includes them. */
    public String requiredPlan(String tenantId) {
        if (planFeatureGate == null || !planFeatureGate.isEnabled()) return null;
        return planFeatureGate.upgradeRequiredFor(tenantId, List.of(FEATURE_KEY));
    }
}
