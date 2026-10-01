package com.apimarketplace.agent.service.execution;

import com.apimarketplace.conversation.client.StreamRedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The park that turns "your turn ended, please connect and ask again" into "the agent is
 * waiting for your click". Every non-approval path must degrade to the pre-gate behaviour,
 * so each is pinned here.
 */
@DisplayName("ToolApprovalGate - parking a tool call on the user's answer")
class ToolApprovalGateTest {

    private static final String CONVERSATION = "conv-1";
    private static final String GATE_KEY = "call-1";
    private static final String STREAM_ID = "stream-1";
    private static final String KEY = StreamRedisKeys.approvalDecisionKey(CONVERSATION, GATE_KEY);
    private static final String CANCEL_KEY = StreamRedisKeys.cancelKey(STREAM_ID);

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private ToolApprovalGate gate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.lenient().when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false);
        gate = new ToolApprovalGate(redisTemplate);
        // Short poll + generous default so the tests finish fast but still exercise the loop.
        gate.configureForTest(true, 2_000, 10);
    }

    /**
     * The {@code agent.tool.approval-gate.timeout-ms} default, read off the class under test.
     *
     * <p>The park budget and the key's TTL are two numbers in two modules that must stay in
     * a relationship; a copy of one of them in a test cannot notice when the other moves.
     */
    private static long configuredParkBudgetMs() throws java.io.IOException {
        java.nio.file.Path source = java.nio.file.Path.of(
                "src/main/java/com/apimarketplace/agent/service/execution/ToolApprovalGate.java");
        String text = java.nio.file.Files.readString(source);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("agent\\.tool\\.approval-gate\\.timeout-ms:(\\d+)").matcher(text);
        assertThat(matcher.find()).as("the park budget default must be declared in the gate").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /** A park on the usual chat shape, with no ceiling other than the gate's own budget. */
    private static ToolApprovalGate.ParkRequest park(long hardDeadlineEpochMs) {
        return new ToolApprovalGate.ParkRequest(CONVERSATION, GATE_KEY, STREAM_ID, hardDeadlineEpochMs, 0, 0, 0, false);
    }

    @Test
    @DisplayName("An 'approved' verdict releases the call and consumes the verdict")
    void approvedVerdictReleasesTheCall() {
        when(valueOps.get(KEY)).thenReturn("approved");

        ToolApprovalGate.Decision decision = gate.awaitDecision(park(0));

        assertThat(decision).isEqualTo(ToolApprovalGate.Decision.APPROVED);
        // Consumed, so a later call reusing the same key cannot inherit this answer.
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("A 'denied' verdict refuses the call")
    void deniedVerdictRefusesTheCall() {
        when(valueOps.get(KEY)).thenReturn("denied");

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.DENIED);
    }

    @Test
    @DisplayName("A verdict arriving mid-park is picked up on a later poll")
    void verdictArrivingLateIsPickedUp() {
        // Nobody has answered for the first two polls, then the user clicks.
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("No answer before the deadline expires the park rather than hanging")
    void noAnswerExpires() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 60, 10);

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
    }

    @Test
    @DisplayName("An unrecognised verdict is treated as a refusal, never as permission")
    void unrecognisedVerdictFailsClosed() {
        when(valueOps.get(KEY)).thenReturn("maybe");

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.DENIED);
    }

    @Test
    @DisplayName("Disabled by config, the gate never parks")
    void disabledGateNeverParks() {
        gate.configureForTest(false, 60_000, 10);

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("A missing conversation or gate key cannot be parked on")
    void missingIdentifiersAreUnavailable() {
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(null, GATE_KEY, STREAM_ID, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest("  ", GATE_KEY, STREAM_ID, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(CONVERSATION, null, STREAM_ID, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(CONVERSATION, "  ", STREAM_ID, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("A Redis outage gives up quickly instead of holding the chat for the full deadline")
    void redisOutageGivesUpFast() {
        when(valueOps.get(KEY)).thenThrow(new IllegalStateException("redis down"));
        // A deadline far beyond what the test would tolerate: only the fast bail-out can end it.
        gate.configureForTest(true, 60_000, 1);

        long startedAt = System.currentTimeMillis();
        ToolApprovalGate.Decision decision = gate.awaitDecision(park(0));

        assertThat(decision).isEqualTo(ToolApprovalGate.Decision.UNAVAILABLE);
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000);
    }

    @Test
    @DisplayName("A transient read failure does not abandon a park that then succeeds")
    void transientFailureDoesNotAbandonThePark() {
        when(valueOps.get(KEY))
                .thenThrow(new IllegalStateException("blip"))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(null)          // recovered - the failure streak must reset here
                .thenThrow(new IllegalStateException("blip"))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn("approved");

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("A caller deadline already in the past skips the park entirely")
    void pastCallerDeadlineSkipsThePark() {
        assertThat(gate.awaitDecision(park(System.currentTimeMillis() - 1_000)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("Pressing Stop ends the park, and the answer that follows never runs the tool")
    void stopEndsThePark() {
        // Stop writes a flag; nothing interrupts a parked thread. Reading the flag here is
        // the only thing between "the user cancelled" and "the tool ran anyway": the card
        // stays on screen after a stop, and clicking it would otherwise release this park.
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(true);
        // The user approved a moment BEFORE stopping - a real ordering, since the card stays
        // clickable after a Stop. Honouring that approval would run the action on a cancelled
        // turn, so a stop must discard it rather than take one last look at the key.
        when(valueOps.getAndDelete(KEY)).thenReturn("approved");
        gate.configureForTest(true, 60_000, 10);

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(park(0))).isEqualTo(ToolApprovalGate.Decision.STOPPED);

        // And it ends AT the stop, not at the deadline - otherwise Stop appears to hang.
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000);
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("A stop landing mid-park wins over a verdict that has not arrived")
    void stopMidParkEndsIt() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(false, false, true);
        gate.configureForTest(true, 60_000, 10);

        assertThat(gate.awaitDecision(park(0))).isEqualTo(ToolApprovalGate.Decision.STOPPED);
    }

    @Test
    @DisplayName("An unreadable stop flag keeps the park - a Redis blip must not cancel a live turn")
    void unreadableStopFlagDoesNotCancel() {
        when(redisTemplate.hasKey(CANCEL_KEY)).thenThrow(new IllegalStateException("redis down"));
        when(valueOps.get(KEY)).thenReturn("approved");

        assertThat(gate.awaitDecision(park(0))).isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("With no stream there is no stop flag to read, and the park still works")
    void noStreamStillParks() {
        when(valueOps.get(KEY)).thenReturn("approved");

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(CONVERSATION, GATE_KEY, null, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("A park uses at most HALF the run's inactivity window, leaving the rest to run the tool")
    void parkTakesHalfTheInactivityWindow() {
        when(valueOps.get(KEY)).thenReturn(null);
        // The gate would happily wait a minute; the run is killed after 400ms of silence.
        gate.configureForTest(true, 60_000, 5);

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 400, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);

        long waited = System.currentTimeMillis() - startedAt;
        // Half the window, not all of it: a park that eats the whole window is killed
        // mid-execution right after the user approves, which is the outcome parking exists
        // to avoid. Generous upper bound - the point is that it is nowhere near 400ms.
        //
        // The lower bound matters just as much. This caller declares no call start, and the
        // ceilings measured from it fall back to "now"; without that fallback they would
        // resolve against epoch 0 and every such park would expire on entry, turning the
        // gate into a silent no-op that an upper bound alone reads as a pass.
        assertThat(waited).isBetween(120L, 350L);
    }

    @Test
    @DisplayName("The ceiling is refused BEFORE a card exists, so no card can claim a hold that never happened")
    void beginParkRefusesPastTheCeiling() {
        // Reserving here rather than at the wait is what stops a burst all passing the check
        // and all painting a card that says the assistant is waiting. Such a card would also
        // be buffered, so it would keep saying it on every reload for the stream's lifetime.
        gate.configureForTest(true, 60_000, 10, 1);

        assertThat(gate.beginPark(park(0))).isTrue();
        assertThat(gate.beginPark(park(0)))
                .as("the second park must be refused before its caller shows anything")
                .isFalse();

        // And giving the first one back frees the slot for the next caller.
        gate.abandonPark(CONVERSATION, GATE_KEY);
        assertThat(gate.beginPark(park(0))).isTrue();
    }

    @Test
    @DisplayName("A park with no time left is refused before its card is drawn")
    void beginParkRefusesWhenTheBudgetIsSpent() {
        // The second park of one call inherits what the first left of a shared window. With
        // nothing left, holding is impossible - and a card drawn anyway would announce a
        // hold that ended before the user could see it.
        assertThat(gate.beginPark(park(System.currentTimeMillis() - 1_000))).isFalse();
        verify(valueOps, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    @DisplayName("The half-window is a CEILING: a tighter budget still wins")
    void theInactivityWindowNeverRaisesATighterBudget() {
        when(valueOps.get(KEY)).thenReturn(null);
        // A window whose half is ABOVE the gate's own budget - the shape every other windowed
        // test lacks, since theirs all sit below every competing bound, so writing that
        // ceiling as an assignment instead of a min passes them all. In production a window
        // may legally run to 7200 s, which would then hand a park an hour, well past the
        // marker's own lifetime; the ratio is what matters here, so keep the numbers small
        // enough that a regression fails in a second rather than blocking the build for the
        // whole mistaken ceiling.
        gate.configureForTest(true, 150, 20);

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 2_000, startedAt, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt)
                .as("the gate's own budget must survive a generous window")
                .isLessThan(900);
    }

    @Test
    @DisplayName("A park whose budget lapses between reserving and waiting gives its slot back")
    void aParkThatLapsesAfterReservingReleasesItsSlot() throws Exception {
        // The budget is measured against absolute instants, and real work happens between the
        // two checks (the marker write, then painting the card), so a park with a moment left
        // passes beginPark and is out of time by the time it waits. That return used to sit
        // outside the try that gives the slot back, so the slot was held for the life of the
        // process. Nothing visible breaks at first: parks keep working until enough have
        // lapsed, and then the gate simply stops parking, with one warn line and no cards.
        //
        // Staged on the inactivity-window ceiling rather than the caller's deadline, which
        // reserves a 5 s margin and so cannot be brought this close to lapsing.
        gate.configureForTest(true, 60_000, 10, 1);
        ToolApprovalGate.ParkRequest park = new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 1_000,
                System.currentTimeMillis() - 440, 0, false);   // half the window ends in ~60ms

        assertThat(gate.beginPark(park)).as("it still has a sliver of budget here").isTrue();
        Thread.sleep(120);   // the window closes while the card is being painted
        assertThat(gate.awaitDecision(park)).isEqualTo(ToolApprovalGate.Decision.EXPIRED);

        // The only way to observe the counter: the ceiling is 1, so a slot that was not
        // returned makes the next caller unable to park at all.
        assertThat(gate.beginPark(park(0)))
                .as("the lapsed park must not keep the slot it reserved")
                .isTrue();
    }

    @Test
    @DisplayName("Past the concurrency ceiling the gate declines to park rather than eat the thread pool")
    void tooManyParksDeclineRatherThanBlock() throws Exception {
        // A park blocks its thread, and on the CLI-bridge route that thread belongs to the
        // servlet connector. Enough simultaneous parks and every other request queues behind
        // users who stepped away - so a park that is RUNNING must hold its slot against the
        // ceiling, and the next caller must be turned away before it can add a blocked thread.
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 60_000, 5, 1);

        assertThat(gate.beginPark(park(0))).isTrue();
        Thread holder = new Thread(() -> gate.awaitDecision(park(0)));
        holder.setDaemon(true);
        holder.start();
        Thread.sleep(150);   // let it settle into the poll loop, holding the only slot

        long startedAt = System.currentTimeMillis();
        assertThat(gate.beginPark(park(0)))
                .as("the slot is taken by a park that is still running")
                .isFalse();
        assertThat(System.currentTimeMillis() - startedAt)
                .as("declining must be immediate - the point is not to block")
                .isLessThan(2_000);

        holder.interrupt();
        holder.join(5_000);

        // The slot comes back once that park ends, so the ceiling is a limit, not a leak.
        assertThat(gate.beginPark(park(0))).isTrue();
    }

    @Test
    @DisplayName("The park hands back enough of the deadline for the tool to actually RUN")
    void parkReservesTimeForTheToolToRun() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 60_000, 5);
        // The caller affords 1 s in total and the tool itself needs 800 ms of it. A park that
        // reserved only the token margin would eat almost all of it, and the approved call
        // would then be charged and discarded as a timeout.
        long hardDeadline = System.currentTimeMillis() + 1_000;

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, hardDeadline, 0, 0, 800, false)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);

        assertThat(System.currentTimeMillis() - startedAt)
                .as("the park must end early enough to leave the tool its own time")
                .isLessThan(600);
    }

    @Test
    @DisplayName("A SECOND park in the same call inherits the window, it does not restart it")
    void aSecondParkDoesNotRestartTheWindow() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 60_000, 5);
        // The call began 500 ms ago and the run tolerates 800 ms of silence: half of that,
        // measured from the START, is already spent. Restarting the clock here would let two
        // parks of one call outlast the watchdog that has been counting since before the first.
        long callStarted = System.currentTimeMillis() - 500;

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 800, callStarted, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);

        assertThat(System.currentTimeMillis() - startedAt)
                .as("the second park gets what is LEFT of the window, not a fresh half")
                .isLessThan(300);
    }

    @Test
    @DisplayName("A bridge park is kept short enough that no CLI stops waiting on us first")
    void bridgeParkIsCappedBelowEveryCliFloor() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 240_000, 20);
        // A generous window: half of it (150 s) would otherwise be the ceiling, which is
        // longer than the shortest documented CLI inline wait (codex, 60 s). A CLI that
        // stops waiting does not cancel the call - it stays held, and a later answer runs
        // the tool for real with nobody to read the result.
        long window = 300_000;

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, window,
                startedAt - ToolApprovalGate.BRIDGE_MAX_PARK_MS, 0, true)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);

        // Already past the cap when it started, so it must give up at once rather than run
        // to half the window.
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000);
    }

    @Test
    @DisplayName("The bridge cap can only SHORTEN a park, never extend one a tighter bound cut")
    void bridgeCapNeverRaisesALowerCeiling() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 240_000, 20);
        // A short watchdog window (30 s is legal, the contract floor is 10 s) puts the
        // half-window ceiling at 15 s, BELOW the 25 s cap. Written as an assignment instead
        // of a min, the cap would hand this park 25 s and it would outlive the watchdog: the
        // run gets KILLED for silence, which no later answer can recover, where the ceiling
        // it overwrote would merely have expired the card. Every other bridge test starts
        // the clock a full cap in the past, so all of them stay green through that mutation.
        long shortWindow = 30_000;
        long callStarted = System.currentTimeMillis() - 16_000;   // past 15 s, short of 25 s

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, shortWindow, callStarted, 0, true)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt)
                .as("the tighter half-window ceiling must still win on a bridge call")
                .isLessThan(2_000);
    }

    @Test
    @DisplayName("The bridge park lasts the WHOLE cap, not some fraction of it")
    void bridgeParkRunsToTheFullCap() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 240_000, 20);
        // The other bridge tests pin "already expired" and the constant's value; none reads
        // the length actually applied, so halving the expression (or subtracting a margin
        // from it) passes them all. This call has 300 ms of cap left: it must still be held
        // for them. Users would otherwise get a fraction of the documented wait, the card
        // would fall back to two turns more often, and nothing would be red.
        long callStarted = System.currentTimeMillis() - (ToolApprovalGate.BRIDGE_MAX_PARK_MS - 300);

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000, callStarted, 0, true)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt)
                .as("the cap must be applied whole, measured from the start of the call")
                .isBetween(150L, 2_000L);
    }

    @Test
    @DisplayName("A bridge park still WAITS for the answer - the cap bounds it, it does not skip it")
    void bridgeParkStillWaitsForTheAnswer() {
        // Every other bridge test starts the clock at or before the cap, so each one asserts
        // only "gives up at once" - and stays green if the cap is written so that a bridge
        // park has no time at all (measuring from the wrong instant does it). The gate would
        // then be a no-op on the very route this exists for: the card falls back to the old
        // two-turn flow, and the only trace is an info line that reads like normal operation.
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");
        gate.configureForTest(true, 240_000, 20);

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000,
                System.currentTimeMillis(), 0, true)))
                .as("a call that starts NOW must be held long enough to read the verdict")
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("The bridge cap leaves the larger half of the shortest CLI floor to the tool")
    void bridgeCapIsPinnedAgainstTheShortestCliFloor() {
        // Every other bridge test builds its clock FROM the constant, so all of them stay
        // green whatever it is set to. This is the one that reads it as a number.
        //
        // The constant covers a session that declares no CLI wait: an older bridge, or a
        // CLI whose timeout the bridge cannot write. The shortest per-call wait any
        // supported CLI documents for an UNCONFIGURED server is codex's 60 s, and an older
        // bridge writes no tool_timeout_sec, so that is the case to survive. The CLI's timer
        // covers the wait AND the tool run that follows an approval, so the cap has to lose
        // that race twice over: expire before the CLI gives up, and leave more of the floor
        // to the tool than it took for itself. Raise it past 30 s and an approved call
        // starts a tool with under half a minute to finish on a route where being abandoned
        // means it runs anyway, for nobody. (A session that DOES declare its wait is bounded
        // by that instead - see declaredCliWaitRaisesTheBridgeCap.)
        long shortestPublishedCliFloorMs = 60_000;

        assertThat(ToolApprovalGate.BRIDGE_MAX_PARK_MS)
                .as("a held call must end before the shortest CLI stops waiting")
                .isLessThan(shortestPublishedCliFloorMs)
                .as("and must leave the tool more of that floor than the wait took")
                .isLessThan(shortestPublishedCliFloorMs / 2)
                // The javadoc tells a future maintainer to LOWER this if a CLI is seen
                // giving up first, so the floor matters as much as the ceiling: taken down
                // to a few seconds it stops being a wait a present user can answer, and the
                // second park of a call - which inherits what the first left - is refused
                // outright by beginPark's "no budget left" guard.
                .as("but must stay long enough for a present user to read a card and click")
                .isGreaterThanOrEqualTo(10_000);
    }

    @Test
    @DisplayName("The park ceiling leaves at least two thirds of the servlet pool to everything else")
    void concurrentParkCeilingLeavesThePoolRoom() {
        // A park holds a servlet thread for its whole wait, and a bridge park now runs to half
        // the inactivity window (150 s by default) instead of 25 s. The ceiling protects the
        // pool: with Spring's default 200 Tomcat threads (nothing in the repo sets
        // server.tomcat.threads.max), a third is the most parks may ever hold.
        int tomcatDefaultThreads = 200;

        assertThat(ToolApprovalGate.DEFAULT_MAX_CONCURRENT_PARKS)
                .as("parks must never hold more than a third of the default pool")
                .isLessThanOrEqualTo(tomcatDefaultThreads / 3)
                .as("and must cover more than a couple of people answering cards at once")
                .isGreaterThanOrEqualTo(32)
                // The number itself, so a quiet edit to the @Value default shows up here
                // rather than as a pool that fills up under load.
                .as("the shipped default is 64: change it here and in the javadoc together")
                .isEqualTo(64);
    }

    @Test
    @DisplayName("The cap holds even with the watchdog switched OFF - that is a legal setting")
    void bridgeCapSurvivesADisabledWatchdog() {
        when(valueOps.get(KEY)).thenReturn(null);
        // A 60 s budget, not the 240 s default: still far above the cap, so the test means
        // the same thing, but a regression to the old window-based inference now fails here
        // in one minute instead of four - and as a failed assertion rather than whatever
        // per-test timeout happens to fire first.
        gate.configureForTest(true, 60_000, 20);
        // inactivityTimeout=0 disables the watchdog and is settable from the chat options.
        // Deriving "a CLI is holding this" from the window made the cap vanish precisely
        // there, leaving a full-budget hold on a route where the CLI stops waiting sooner.
        long startedAt = System.currentTimeMillis();

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 0,
                startedAt - ToolApprovalGate.BRIDGE_MAX_PARK_MS, 0, true)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000);
    }

    @Test
    @DisplayName("A bridge session that declares its CLI's wait is held up to THAT, past the shortest-CLI floor")
    void declaredCliWaitRaisesTheBridgeCap() {
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");
        gate.configureForTest(true, 240_000, 20);
        // The bridge writes codex's per-call timeout and reads claude-code's idle window,
        // so it knows a call may wait longer than the 25 s floor on those CLIs. It says so
        // per session. This call is already PAST the floor: with the floor it would expire
        // at once; with the declared 60 s it must still be held and read the verdict.
        long declaredWaitMs = 60_000;
        long pastTheFloor = System.currentTimeMillis() - (ToolApprovalGate.BRIDGE_MAX_PARK_MS + 5_000);

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000, pastTheFloor, 0, true, declaredWaitMs)))
                .as("the session's own wait must bound the park, not the fallback floor")
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("A declared CLI wait BELOW the floor is a ceiling of its own: held inside it, expired past it")
    void declaredCliWaitBelowTheFloorIsHonoured() {
        gate.configureForTest(true, 240_000, 20);
        long declaredWaitMs = 10_000;

        // Inside the declared 10 s: still held, reads the verdict.
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000,
                System.currentTimeMillis() - 5_000, 0, true, declaredWaitMs)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);

        // Past 10 s but still under the 25 s floor: the floor must NOT rescue it, because
        // the bridge said this CLI stops waiting sooner than the floor assumes.
        when(valueOps.get(KEY)).thenReturn(null);
        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000,
                startedAt - 11_000, 0, true, declaredWaitMs)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000);
    }

    @Test
    @DisplayName("A declared CLI wait still ends the park: it is a ceiling of its own, not a blank cheque")
    void declaredCliWaitIsStillACeiling() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 240_000, 20);
        long declaredWaitMs = 60_000;
        long pastTheDeclaredWait = System.currentTimeMillis() - (declaredWaitMs + 1_000);

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000, pastTheDeclaredWait, 0, true, declaredWaitMs)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt)
                .as("past the declared wait the park must give up at once")
                .isLessThan(2_000);
    }

    @Test
    @DisplayName("A declared CLI wait never outruns half the inactivity window - the watchdog still kills a silent run")
    void declaredCliWaitStaysUnderTheHalfWindow() {
        when(valueOps.get(KEY)).thenReturn(null);
        gate.configureForTest(true, 240_000, 20);
        // 30 s window => 15 s ceiling. A declared 60 s wait must not lift that: the run
        // would be killed for silence, which no later answer can recover.
        long callStarted = System.currentTimeMillis() - 16_000;

        long startedAt = System.currentTimeMillis();
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 30_000, callStarted, 0, true, 60_000)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(2_000);
    }

    @Test
    @DisplayName("A declared CLI wait is ignored on the direct route - nothing is holding that call")
    void declaredCliWaitIsOnlyReadOnTheBridge() {
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");
        gate.configureForTest(true, 240_000, 20);
        // A stray value on a direct-route call must not cut a 240 s budget down to it.
        long pastTheDeclaredWait = System.currentTimeMillis() - 11_000;

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000, pastTheDeclaredWait, 0, false, 10_000)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("The direct route is not cut by the bridge cap - no CLI is holding that call")
    void directRouteIsNotCappedByTheBridgeLimit() {
        when(valueOps.get(KEY)).thenReturn(null, null, "approved");
        gate.configureForTest(true, 240_000, 20);
        // A DIRECT chat that configures an inactivity window looks exactly like a bridge run
        // to anything that reads the window - which is how the cap ended up applying here,
        // cutting a 240 s budget down to the bridge's on a route where no MCP call is
        // waiting on us at all. The marker is what tells the two apart.
        long alreadyPastTheBridgeCap = System.currentTimeMillis() - ToolApprovalGate.BRIDGE_MAX_PARK_MS;

        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 300_000, alreadyPastTheBridgeCap, 0, false)))
                .as("no CLI is holding this call, so the bridge cap must not apply")
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("An unknown inactivity window imposes no ceiling of its own")
    void unknownInactivityWindowIsNotACeiling() {
        when(valueOps.get(KEY)).thenReturn("approved");

        // 0 means "no watchdog / not told", and must not be read as "no time at all".
        assertThat(gate.awaitDecision(new ToolApprovalGate.ParkRequest(
                CONVERSATION, GATE_KEY, STREAM_ID, 0, 0, 0, 0, false)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("Beginning a park advertises it, so the answer side can tell somebody is holding")
    void beginParkAdvertisesTheHold() {
        assertThat(gate.beginPark(park(0))).isTrue();

        verify(valueOps).set(KEY, "pending", StreamRedisKeys.APPROVAL_DECISION_TTL);
    }

    @Test
    @DisplayName("The park key expires soon after the longest park, so a dead holder cannot strand a click")
    void parkKeyOutlivesTheParkOnlyBriefly() throws java.io.IOException {
        // While the marker exists, the answer side reports "a call was released" and the
        // frontend trusts that enough to skip the message that restarts the turn. If the
        // process holding the park dies, nothing removes the marker - so its lifetime IS the
        // window in which a user's click is silently swallowed. It must cover the longest
        // park (so a last-instant verdict is still readable) and little more.
        java.time.Duration ttl = StreamRedisKeys.APPROVAL_DECISION_TTL;
        // Read from the source rather than repeated here: hardcoding it would let someone
        // raise the park budget past the TTL with this very test still green, which is the
        // one thing it exists to prevent.
        long maxParkMs = configuredParkBudgetMs();

        assertThat(ttl.toMillis())
                .as("must outlive the longest park")
                .isGreaterThan(maxParkMs);
        assertThat(ttl.toMillis() - maxParkMs)
                .as("but not by much - this margin is how long a dead pod can strand a conversation")
                .isLessThanOrEqualTo(180_000);
    }

    @Test
    @DisplayName("The pending marker is not an answer - the park keeps waiting on it")
    void pendingMarkerIsNotAnAnswer() {
        // Its own marker read back must not end the park, or every call would resolve
        // instantly as "denied" (the fail-closed branch for anything unrecognised).
        when(valueOps.get(KEY)).thenReturn("pending");
        gate.configureForTest(true, 60, 10);

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
    }

    @Test
    @DisplayName("Redis refusing the advertisement means no park is claimed at all")
    void beginParkFailsWhenRedisRefuses() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));

        // False is what stops the caller from showing a card claiming a hold that does not
        // exist - a card nobody can release is a conversation that can never continue.
        assertThat(gate.beginPark(park(0))).isFalse();
    }

    @Test
    @DisplayName("Disabled or unidentified, nothing is advertised")
    void beginParkRefusesWhenItCannotPark() {
        gate.configureForTest(false, 60_000, 10);
        assertThat(gate.beginPark(park(0))).isFalse();

        gate.configureForTest(true, 60_000, 10);
        assertThat(gate.beginPark(new ToolApprovalGate.ParkRequest(null, GATE_KEY, STREAM_ID, 0, 0, 0, 0, false))).isFalse();
        assertThat(gate.beginPark(new ToolApprovalGate.ParkRequest(CONVERSATION, "  ", STREAM_ID, 0, 0, 0, 0, false))).isFalse();

        verify(valueOps, never()).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
    }

    @Test
    @DisplayName("An answer landing in the instant the park gives up is still honoured")
    void answerRacingTheDeadlineIsNotLost() {
        // Every poll says "nobody answered", so the park runs to its deadline - but the
        // user clicked between the last poll and the give-up. Reading and deleting in one
        // operation is what keeps that answer from being silently dropped.
        when(valueOps.get(KEY)).thenReturn(null);
        when(valueOps.getAndDelete(KEY)).thenReturn("approved");
        gate.configureForTest(true, 60, 10);

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.APPROVED);
    }

    @Test
    @DisplayName("A Stop beats an approval that lands in that same instant, on EVERY way of giving up")
    void aStopBeatsTheAnswerRacingTheDeadline() {
        // The intersection of the two tests around this one, and the hole between them. The
        // loop checks the cancel flag on every pass, but no park ENDS in the loop: it ends
        // because the deadline passed, Redis failed, the thread was interrupted, or the
        // budget was already spent. Each of those took one last look at the key and honoured
        // whatever it found - so an approval clicked a moment before the Stop ran the action
        // for real on a cancelled turn: charged, and for an external call, done in the world.
        // That is the one outcome Decision.STOPPED promises never happens.
        // Staged on the exit that never enters the loop at all - the budget is already spent
        // when the park is asked to wait - so the cancel flag can only be read by the last
        // look itself. Any other staging would let the loop catch the Stop and prove nothing.
        when(valueOps.getAndDelete(KEY)).thenReturn("approved");
        when(redisTemplate.hasKey(CANCEL_KEY)).thenReturn(true);
        gate.configureForTest(true, 60_000, 10);

        assertThat(gate.awaitDecision(park(System.currentTimeMillis() - 1_000)))
                .as("the late approval must be discarded, not taken")
                .isEqualTo(ToolApprovalGate.Decision.STOPPED);
        verify(valueOps).getAndDelete(KEY);   // and the marker is still cleaned up
    }

    @Test
    @DisplayName("Giving up stops advertising the park, so a later answer knows nobody is holding")
    void givingUpStopsAdvertisingThePark() {
        when(valueOps.get(KEY)).thenReturn(null);
        when(valueOps.getAndDelete(KEY)).thenReturn("pending");
        gate.configureForTest(true, 60, 10);

        assertThat(gate.awaitDecision(park(0)))
                .isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        // Removed, not left behind: a leftover marker would make the next click report a
        // release that nobody is waiting for, and the user's answer would vanish.
        verify(valueOps).getAndDelete(KEY);
    }

    @Test
    @DisplayName("The caller's deadline wins over the gate's own, minus the execution margin")
    void callerDeadlineCapsThePark() {
        when(valueOps.get(KEY)).thenReturn(null);
        // Gate would wait a minute; the caller only affords the margin plus a sliver.
        gate.configureForTest(true, 60_000, 5);
        long hardDeadline = System.currentTimeMillis() + ToolApprovalGate.DEADLINE_MARGIN_MS + 60;

        long startedAt = System.currentTimeMillis();
        ToolApprovalGate.Decision decision = gate.awaitDecision(park(hardDeadline));

        assertThat(decision).isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        // Bounded by the caller's budget, nowhere near the gate's own 60s.
        assertThat(System.currentTimeMillis() - startedAt).isLessThan(5_000);
    }

    @Test
    @DisplayName("The running-turn \"don't ask again\" grant is read from the key conversation-service writes")
    void conversationWideGrantIsReadFromTheSharedKey() {
        when(redisTemplate.hasKey(StreamRedisKeys.conversationWideApprovalKey("conv-1"))).thenReturn(true);

        assertThat(gate.isConversationWideApproved("conv-1")).isTrue();
        assertThat(gate.isConversationWideApproved("conv-2")).isFalse();
        assertThat(gate.isConversationWideApproved(null)).isFalse();
    }

    @Test
    @DisplayName("An unreadable running-turn grant fails CLOSED: a card, never an unconsented action")
    void conversationWideGrantFailsClosed() {
        when(redisTemplate.hasKey(StreamRedisKeys.conversationWideApprovalKey("conv-1")))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(gate.isConversationWideApproved("conv-1")).isFalse();
    }

    @Test
    @DisplayName("A held authorization is released when \"don't ask again\" is ticked on ANOTHER card")
    void authorizationParkReleasedByTheConversationWideGrant() {
        // No verdict ever lands on this card: the browser may not even have received it.
        when(valueOps.get(KEY)).thenReturn("pending");
        when(redisTemplate.hasKey(StreamRedisKeys.conversationWideApprovalKey(CONVERSATION))).thenReturn(false, true);

        assertThat(gate.awaitDecision(park(0))).isEqualTo(ToolApprovalGate.Decision.APPROVED);
        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("A Deny on this very card still wins over a conversation-wide grant")
    void explicitDenyBeatsTheConversationWideGrant() {
        when(valueOps.get(KEY)).thenReturn("denied");
        org.mockito.Mockito.lenient().when(redisTemplate.hasKey(StreamRedisKeys.conversationWideApprovalKey(CONVERSATION))).thenReturn(true);

        assertThat(gate.awaitDecision(park(0))).isEqualTo(ToolApprovalGate.Decision.DENIED);
    }

    @Test
    @DisplayName("A question or connect park (awaitAnswer) is never answered by a permission")
    void nonAuthorizationParkIgnoresTheConversationWideGrant() {
        when(valueOps.get(KEY)).thenReturn("pending");
        org.mockito.Mockito.lenient().when(redisTemplate.hasKey(StreamRedisKeys.conversationWideApprovalKey(CONVERSATION))).thenReturn(true);
        gate.configureForTest(true, 60, 10);

        assertThat(gate.awaitAnswer(park(0)).decision()).isEqualTo(ToolApprovalGate.Decision.EXPIRED);
        verify(redisTemplate, never()).hasKey(StreamRedisKeys.conversationWideApprovalKey(CONVERSATION));
    }
}
