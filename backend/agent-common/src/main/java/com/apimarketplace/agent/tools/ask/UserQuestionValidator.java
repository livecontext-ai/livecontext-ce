package com.apimarketplace.agent.tools.ask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the raw {@code questions} argument of the {@code ask_user} tool, and the raw
 * {@code answers} body the person submits, into validated records.
 *
 * <p>Both sides share this class on purpose: agent-service validates what the agent asks,
 * conversation-service validates what the person answers, and a shape one side accepts
 * must be one the other side can read. Every rejection names the offending index so the
 * agent can fix the call instead of guessing; nothing is ever silently truncated.
 */
public final class UserQuestionValidator {

    public static final int MAX_QUESTIONS = 4;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 4;
    public static final int MAX_HEADER_LENGTH = 40;
    public static final int MAX_QUESTION_LENGTH = 1_000;
    public static final int MAX_LABEL_LENGTH = 120;
    public static final int MAX_DESCRIPTION_LENGTH = 300;
    public static final int MAX_FREE_TEXT_LENGTH = 4_000;

    /**
     * Labels the card adds itself. Declaring one would show the person two "Other" rows,
     * one of which the agent can never read back as free text.
     */
    private static final Set<String> RESERVED_LABELS = Set.of("other", "autre", "otro", "outro", "andere", "其他");

    private UserQuestionValidator() {
    }

    /** Thrown with a message the agent can act on; never carries anything else. */
    public static final class InvalidQuestionsException extends IllegalArgumentException {
        public InvalidQuestionsException(String message) {
            super(message);
        }
    }

    /**
     * Validates the questions and reports each declared "Other"-like option it dropped. Such an
     * option used to fail the whole call although the fix is always the same (the card adds that
     * row itself), so it is removed and the caller names it in its result: the removal stays
     * visible to the agent, never silent. There is deliberately no overload without the report.
     *
     * @param raw            the {@code questions} tool argument as the model sent it
     * @param droppedOptions receives one entry per dropped option, e.g. {@code questions[0].options[3] 'Autre'}
     * @return the validated questions, in order
     * @throws InvalidQuestionsException when the shape is wrong, with the index that is wrong
     */
    public static List<UserQuestion> parseQuestions(Object raw, List<String> droppedOptions) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidQuestionsException("questions must be a non-empty list of 1 to "
                    + MAX_QUESTIONS + " questions, each with header, question and options.");
        }
        if (list.size() > MAX_QUESTIONS) {
            throw new InvalidQuestionsException("questions holds " + list.size() + " entries; the maximum is "
                    + MAX_QUESTIONS + ". Ask the most important ones now and the rest in a later call.");
        }
        List<UserQuestion> questions = new ArrayList<>();
        Set<String> headers = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new InvalidQuestionsException("questions[" + i + "] must be an object with header, question and options.");
            }
            String header = requireText(map.get("header"), "questions[" + i + "].header", MAX_HEADER_LENGTH);
            if (!headers.add(header.toLowerCase(Locale.ROOT))) {
                throw new InvalidQuestionsException("questions[" + i + "].header '" + header
                        + "' is used twice; headers must be unique, they are how you read the answers back.");
            }
            String question = requireText(map.get("question"), "questions[" + i + "].question", MAX_QUESTION_LENGTH);
            List<UserQuestionOption> options = parseOptions(map.get("options"), i, droppedOptions);
            boolean multiSelect = isTruthy(map.get("multiSelect")) || isTruthy(map.get("multi_select"));
            questions.add(new UserQuestion(header, question, options, multiSelect));
        }
        return questions;
    }

    private static List<UserQuestionOption> parseOptions(Object raw, int questionIndex, List<String> droppedOptions) {
        String path = "questions[" + questionIndex + "].options";
        if (!(raw instanceof List<?> list)) {
            throw new InvalidQuestionsException(path + " must be a list of " + MIN_OPTIONS + " to "
                    + MAX_OPTIONS + " options, each with a label.");
        }
        // The size rule applies to the options the card will SHOW, so a declared "Other" does not
        // count: five options of which one is "Other" is four real ones and fine.
        int reserved = (int) list.stream().filter(UserQuestionValidator::isReservedOption).count();
        int real = list.size() - reserved;
        if (real < MIN_OPTIONS || real > MAX_OPTIONS) {
            throw new InvalidQuestionsException(path + " holds " + real + (real == 1 ? " option" : " options")
                    + (reserved > 0 ? " besides 'Other'" : "") + "; give between "
                    + MIN_OPTIONS + " and " + MAX_OPTIONS + ". The person can always type their own answer, "
                    + "so do not add an 'Other' option.");
        }
        List<UserQuestionOption> options = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (int j = 0; j < list.size(); j++) {
            Object entry = list.get(j);
            String label;
            Object rawDescription = null;
            if (entry instanceof Map<?, ?> map) {
                label = requireText(map.get("label"), path + "[" + j + "].label", MAX_LABEL_LENGTH);
                rawDescription = map.get("description");
            } else if (entry instanceof String s && !s.isBlank()) {
                // A bare string is a label with no description; accepted so a terse call still works.
                label = s.trim();
            } else {
                throw new InvalidQuestionsException(path + "[" + j + "] must be an object with a label.");
            }
            if (RESERVED_LABELS.contains(label.toLowerCase(Locale.ROOT))) {
                // Dropped, not refused: the card always adds its own free-text row, and a refusal
                // cost a full retry for a fix that is always the same. Reported so it is not silent.
                // Checked before the description, so a dropped option's description cannot fail the call.
                droppedOptions.add(path + "[" + j + "] '" + label + "'");
                continue;
            }
            String description = null;
            if (rawDescription != null) {
                description = String.valueOf(rawDescription).trim();
                if (description.length() > MAX_DESCRIPTION_LENGTH) {
                    throw new InvalidQuestionsException(path + "[" + j + "].description is longer than "
                            + MAX_DESCRIPTION_LENGTH + " characters.");
                }
            }
            if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                throw new InvalidQuestionsException(path + "[" + j + "] '" + label + "' is a duplicate label.");
            }
            options.add(new UserQuestionOption(label, description));
        }
        return options;
    }

    /**
     * Validate the person's answers against the questions that were asked.
     *
     * @param raw       the {@code answers} list as submitted: {@code [{header, selected[], freeText}]}
     * @param questions the questions the card showed, or {@code null} when they are not at hand
     *                  (the answer side may only have the raw envelope), in which case only the
     *                  shape is checked
     * @throws InvalidQuestionsException with the index that is wrong
     */
    public static List<UserQuestionAnswer> parseAnswers(Object raw, List<UserQuestion> questions) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new InvalidQuestionsException("answers must be a non-empty list of {header, selected, freeText}.");
        }
        if (list.size() > MAX_QUESTIONS) {
            throw new InvalidQuestionsException("answers holds " + list.size() + " entries; at most " + MAX_QUESTIONS + " questions were asked.");
        }
        List<UserQuestionAnswer> answers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> map)) {
                throw new InvalidQuestionsException("answers[" + i + "] must be an object with header, selected and freeText.");
            }
            String header = requireText(map.get("header"), "answers[" + i + "].header", MAX_HEADER_LENGTH);
            List<String> selected = new ArrayList<>();
            if (map.get("selected") instanceof List<?> sel) {
                for (Object o : sel) {
                    if (o != null && !String.valueOf(o).isBlank()) {
                        selected.add(String.valueOf(o).trim());
                    }
                }
            }
            String freeText = map.get("freeText") instanceof String s && !s.isBlank() ? s.trim() : null;
            if (freeText != null && freeText.length() > MAX_FREE_TEXT_LENGTH) {
                throw new InvalidQuestionsException("answers[" + i + "].freeText is longer than " + MAX_FREE_TEXT_LENGTH + " characters.");
            }
            if (selected.isEmpty() && freeText == null) {
                throw new InvalidQuestionsException("answers[" + i + "] carries neither a selection nor free text.");
            }
            UserQuestion question = questions == null ? null : findQuestion(questions, header);
            if (questions != null) {
                if (question == null) {
                    throw new InvalidQuestionsException("answers[" + i + "].header '" + header + "' matches no question that was asked.");
                }
                if (!question.multiSelect() && selected.size() > 1) {
                    throw new InvalidQuestionsException("answers[" + i + "] picks " + selected.size()
                            + " options but '" + header + "' allows one.");
                }
                Set<String> known = new HashSet<>();
                question.options().forEach(o -> known.add(o.label().toLowerCase(Locale.ROOT)));
                for (String s : selected) {
                    if (!known.contains(s.toLowerCase(Locale.ROOT))) {
                        throw new InvalidQuestionsException("answers[" + i + "] selects '" + s
                                + "', which is not an option of '" + header + "'.");
                    }
                }
            }
            boolean custom = selected.isEmpty();
            answers.add(new UserQuestionAnswer(question != null ? question.header() : header, selected, freeText, custom));
        }
        return answers;
    }

    /** A declared option whose label is one the card adds itself (a bare string or {label}). */
    private static boolean isReservedOption(Object entry) {
        Object label = entry instanceof Map<?, ?> map ? map.get("label") : entry;
        return label instanceof String s && RESERVED_LABELS.contains(s.trim().toLowerCase(Locale.ROOT));
    }

    private static UserQuestion findQuestion(List<UserQuestion> questions, String header) {
        for (UserQuestion q : questions) {
            if (q.header().equalsIgnoreCase(header)) {
                return q;
            }
        }
        return null;
    }

    private static String requireText(Object value, String path, int maxLength) {
        if (!(value instanceof String s) || s.isBlank()) {
            throw new InvalidQuestionsException(path + " is required and must be non-empty text.");
        }
        String text = s.trim();
        if (text.length() > maxLength) {
            throw new InvalidQuestionsException(path + " is longer than " + maxLength + " characters.");
        }
        return text;
    }

    private static boolean isTruthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && "true".equalsIgnoreCase(s.trim());
    }
}
