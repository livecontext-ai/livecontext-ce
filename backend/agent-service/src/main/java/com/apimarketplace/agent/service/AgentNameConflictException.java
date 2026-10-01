package com.apimarketplace.agent.service;

import java.util.UUID;

/**
 * An active agent with this name already exists in the workspace, and the caller
 * TYPED the name (explicit create, rename), so it is refused rather than renamed
 * behind their back. Carries the first free name ({@link AgentService#allocateAgentName})
 * so the refusal is actionable in one step: the REST layer answers 409
 * {@code AGENT_NAME_CONFLICT} with {@code suggestedName}, the agent tool answers
 * {@code RESOURCE_CONFLICT} with {@code suggested_name}.
 *
 * <p>Extends {@link IllegalArgumentException} on purpose: every caller that already
 * catches that type for "the request was refused" (the agent tool's create/update
 * catch blocks, older internal callers) keeps working unchanged, while the specific
 * handlers that know this type answer with the structured conflict.
 *
 * <p>{@code existingAgentId} is null when the conflict was detected by the database
 * index itself (a concurrent writer won the race), because the losing transaction is
 * aborted and cannot read the winner back.
 */
public class AgentNameConflictException extends IllegalArgumentException {

    private final String name;
    private final UUID existingAgentId;
    private final String suggestedName;
    private final boolean rename;

    public AgentNameConflictException(String name, UUID existingAgentId, String suggestedName) {
        this(name, existingAgentId, suggestedName, false);
    }

    private AgentNameConflictException(String name, UUID existingAgentId, String suggestedName, boolean rename) {
        super(rename ? buildRenameMessage(name, existingAgentId, suggestedName)
                : buildMessage(name, existingAgentId, suggestedName));
        this.name = name;
        this.existingAgentId = existingAgentId;
        this.suggestedName = suggestedName;
        this.rename = rename;
    }

    /**
     * The refusal of an UPDATE that renames (or re-activates) an agent onto a name another active
     * agent holds. The caller is editing its own agent, so "update that agent instead" would be
     * wrong advice: the only way out is another name.
     */
    public static AgentNameConflictException forRename(String name, UUID otherAgentId, String suggestedName) {
        return new AgentNameConflictException(name, otherAgentId, suggestedName, true);
    }

    private static String buildRenameMessage(String name, UUID otherAgentId, String suggestedName) {
        return (otherAgentId != null ? "Agent " + otherAgentId : "Another active agent")
                + " already uses the name" + (name != null ? " '" + name + "'" : "")
                + " in this workspace. Nothing was saved. "
                + (suggestedName != null
                        ? "Retry with name='" + suggestedName + "'."
                        : "Retry with a different name.");
    }

    /** True when this refuses a rename or re-activation, false for a create. */
    public boolean isRename() {
        return rename;
    }

    /**
     * Recognises the V269 index refusing an insert or update and turns it into the same
     * conflict the pre-insert check raises, so a lost race reads exactly like an ordinary
     * duplicate instead of a generic error.
     *
     * <p>Read from the STRUCTURED error, not from its English text: the constraint name comes
     * from Hibernate's {@link org.hibernate.exception.ConstraintViolationException#getConstraintName()}
     * or the driver's {@link org.postgresql.util.ServerErrorMessage#getConstraint()}, and the
     * violated key from the server's DETAIL field, whose only part that matters,
     * {@code (organization_id, name)=(<org>, <name>)}, is column names and values that no
     * server locale translates. The message text is only a last resort for the constraint name
     * (a wrapper that dropped the cause chain).
     *
     * <p>MUST be called OUTSIDE the transaction that failed: that transaction is aborted and
     * can run no further statement. {@code suggester} (normally
     * {@code (org, name) -> agentService.allocateAgentName(org, name)} through the Spring
     * proxy, so it gets a fresh transaction) receives the organization and name read back from
     * the violated key. It is best-effort: when the key cannot be read or the suggester fails,
     * the conflict is still returned, without a suggestion.
     *
     * @return empty when {@code error} is not this index's violation (the caller keeps its
     *         own handling for every other integrity error)
     */
    public static java.util.Optional<AgentNameConflictException> fromIndexViolation(
            Throwable error, java.util.function.BinaryOperator<String> suggester) {
        return fromIndexViolation(error, suggester, false);
    }

    /**
     * Same, saying whether the refused write was a CREATE ({@code rename=false}) or an update
     * that renamed or re-activated an agent ({@code rename=true}): the two need different
     * advice, and only the caller knows which write it was.
     */
    public static java.util.Optional<AgentNameConflictException> fromIndexViolation(
            Throwable error, java.util.function.BinaryOperator<String> suggester, boolean rename) {
        String constraint = null;
        String detail = null;
        StringBuilder messages = new StringBuilder();
        Throwable t = error;
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null && constraint == null) {
                constraint = cve.getConstraintName();
            }
            if (t instanceof org.postgresql.util.PSQLException psql && psql.getServerErrorMessage() != null) {
                org.postgresql.util.ServerErrorMessage sem = psql.getServerErrorMessage();
                if (sem.getConstraint() != null) {
                    constraint = sem.getConstraint();
                }
                if (sem.getDetail() != null) {
                    detail = sem.getDetail();
                }
            }
            if (t.getMessage() != null) {
                messages.append(t.getMessage()).append('\n');
            }
        }
        boolean ours = constraint != null
                ? constraint.equals(AgentService.AGENT_NAME_UNIQUE_INDEX)
                : messages.toString().contains(AgentService.AGENT_NAME_UNIQUE_INDEX);
        if (!ours) {
            return java.util.Optional.empty();
        }
        String[] key = detail != null ? violatedKey(detail, true) : violatedKey(messages.toString(), false);
        String org = key != null ? key[0] : null;
        String name = key != null ? key[1] : null;
        String suggestion = null;
        if (org != null && suggester != null) {
            try {
                suggestion = suggester.apply(org, name);
            } catch (RuntimeException ignored) {
                // Best-effort: the conflict itself is the answer, the suggestion a convenience.
            }
        }
        return java.util.Optional.of(new AgentNameConflictException(name, null, suggestion, rename));
    }

    /**
     * {org, name} from {@code ...(organization_id, name)=(<org>, <name>)...}, or null. In the
     * bare DETAIL the values end at its LAST ")" (so a name holding ")" survives); in free
     * message text, where more follows on the line, at the first ") " instead. An organization
     * id holds no ", " (UUID or numeric).
     */
    static String[] violatedKey(String text, boolean bareDetail) {
        String marker = "(organization_id, name)=(";
        int open = text.indexOf(marker);
        if (open < 0) {
            return null;
        }
        int start = open + marker.length();
        int lineEnd = text.indexOf('\n', start);
        String rest = lineEnd >= 0 ? text.substring(start, lineEnd) : text.substring(start);
        int close = bareDetail ? rest.lastIndexOf(')') : rest.indexOf(") ");
        if (close < 0) {
            return null;
        }
        String inner = rest.substring(0, close);
        int comma = inner.indexOf(", ");
        if (comma <= 0) {
            return null;
        }
        return new String[] {inner.substring(0, comma), inner.substring(comma + 2)};
    }

    private static String buildMessage(String name, UUID existingAgentId, String suggestedName) {
        StringBuilder msg = new StringBuilder(name != null
                ? "An active agent named '" + name + "'"
                : "An active agent with this name")
                .append(" already exists in this workspace");
        if (existingAgentId != null) {
            msg.append(" (ID: ").append(existingAgentId).append(")");
        }
        msg.append(". Nothing was saved.");
        if (existingAgentId != null) {
            msg.append(" If you meant that agent, use agent(action='update', agent_id='")
                    .append(existingAgentId).append("', ...) instead.");
        } else {
            msg.append(" If you meant that agent, find its ID with agent(action='list') and use"
                    + " agent(action='update') on it instead.");
        }
        if (suggestedName != null) {
            msg.append(" Otherwise retry with the free name '").append(suggestedName).append("'.");
        } else {
            msg.append(" Otherwise retry with a different name.");
        }
        return msg.toString();
    }

    public String getName() {
        return name;
    }

    public UUID getExistingAgentId() {
        return existingAgentId;
    }

    public String getSuggestedName() {
        return suggestedName;
    }
}
