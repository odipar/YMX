package org.ymr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ymx.EffectScript;
import org.ymx.Tune;
import org.ymx.YmxFormat;

/**
 * The conversion from .YMR's language into the engine's: what each timer
 * becomes, where the parameters the script reads off the chip are put, and
 * which of the format's ideas had to be given up on the way.
 *
 * <p>The fixtures are built here rather than read from a file, the same way
 * {@link YmrReaderTest} builds its own and for the same reason - nothing in
 * this repository packs ZX1, so a stream with a ring size of 0 is the only
 * kind a test can write. The builder below is that one, copied rather than
 * shared: it belongs to the reader's tests as much as to these, and neither
 * file is the natural home for it.
 */
final class YmrEffectsTest {

    // Stream indices, by the names the format gives them.
    private static final int VOLUME_A = 6;
    private static final int VOLUME_B = 7;
    private static final int VOLUME_C = 8;
    private static final int ENVELOPE_SHAPE = 10;
    private static final int TIMER_A_EFFECT = 11;
    private static final int TIMER_A_RATE = 12;
    private static final int TIMER_A_SAMPLE = 13;
    private static final int TIMER_B_EFFECT = 14;
    private static final int TIMER_B_RATE = 15;
    private static final int TIMER_D_EFFECT = 17;
    private static final int TIMER_D_RATE = 18;

    /** A rate no test depends on: prescaler 3, counter 200. */
    private static final int PRESCALER = 3;
    private static final int COUNTER = 200;

    /** Bits 5-4 of a code byte hold the voice plus one. */
    private static int voiceBits(int voice) {
        return (voice + 1) << 4;
    }

    @Test
    void eachTimerBecomesTheChannelTheSpecBindsItsVoiceTo() {
        // Timer A drives voice A, Timer B voice B, and Timer D - not C - voice
        // C. The spec's Timer Effects table calls that binding normative, so
        // the only thing a converter may do with it is apply it.
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE, TIMER_B_EFFECT, TIMER_B_RATE,
                        TIMER_D_EFFECT, TIMER_D_RATE)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.PWM)
                .stream(TIMER_A_RATE, PRESCALER, COUNTER)
                .stream(TIMER_B_EFFECT, YmrReader.TimerFrame.RTE)
                .stream(TIMER_B_RATE, PRESCALER, COUNTER)
                .stream(TIMER_D_EFFECT, YmrReader.TimerFrame.PWM)
                .stream(TIMER_D_RATE, PRESCALER, COUNTER)
                .build();

        byte[][] codes = convert(image).codes();

        assertEquals(Tune.KIND_TOGGLE | voiceBits(0) | PRESCALER,
                codes[0][0] & 0xFF);
        assertEquals(Tune.KIND_RETRIGGER | voiceBits(1) | PRESCALER,
                codes[1][0] & 0xFF);
        assertEquals(Tune.KIND_TOGGLE | voiceBits(2) | PRESCALER,
                codes[2][0] & 0xFF);
        assertEquals(0, codes[3][0]);           // no .ymr ever fills the fourth
    }

    @Test
    void theChannelToTimerMapIsTheBindingWrittenTheWayTheTStreamCarriesIt() {
        assertEquals(YmxFormat.TIMER_A, YmxFormat.timerOf(YmrEffects.TIMERS, 0));
        assertEquals(YmxFormat.TIMER_B, YmxFormat.timerOf(YmrEffects.TIMERS, 1));
        assertEquals(YmxFormat.TIMER_D, YmxFormat.timerOf(YmrEffects.TIMERS, 2));
        // The channel no .YMR fills takes the leftover timer, which
        // keeps the map a permutation and costs the host nothing: the header
        // never flags a channel the tune leaves idle.
        assertEquals(YmxFormat.TIMER_C, YmxFormat.timerOf(YmrEffects.TIMERS, 3));
    }

    @Test
    void aPwmIsAToggleStreamThatLastsUntilTheEffectStreamPopsAZero() {
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE)
                .frame()
                .frame(TIMER_A_EFFECT)
                .frame()
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.PWM,
                        YmrReader.TimerFrame.NONE)
                .stream(TIMER_A_RATE, PRESCALER, COUNTER)
                .build();

        Tune tune = convert(image);

        int toggle = Tune.KIND_TOGGLE | voiceBits(0) | PRESCALER;
        assertArrayEquals(new byte[] {(byte) toggle, (byte) toggle, 0, 0},
                tune.codes()[0]);
        // The count is the MFP's data register, and it goes quiet with the code.
        assertArrayEquals(new byte[] {(byte) COUNTER, (byte) COUNTER, 0, 0},
                tune.counts()[0]);
    }

    @Test
    void aRateWithNothingToCountIsNotAnEffectAtAll() {
        // Prescaler 0 is the MFP's stopped state, so a timer configured with it
        // never ticks however much its effect stream says it is running.
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.PWM)
                .stream(TIMER_A_RATE, 0, COUNTER)
                .build();

        Tune tune = convert(image);

        assertEquals(0, tune.codes()[0][0]);
        assertTrue(note(tune, "stopped state"), tune.notes().toString());
    }

    @Test
    void anEffectTypeTheSpecReservesIsDroppedRatherThanGuessedAt() {
        byte[] image = new Ymr()
                .frame(TIMER_D_EFFECT, TIMER_D_RATE)
                .stream(TIMER_D_EFFECT, 4)
                .stream(TIMER_D_RATE, PRESCALER, COUNTER)
                .build();

        Tune tune = convert(image);

        assertEquals(0, tune.codes()[2][0]);
        assertTrue(note(tune, "reserves"), tune.notes().toString());
    }

    // ------------------------------------------------------------------- RTE

    @Test
    void anRteLeavesTheVoicesVolumeRegisterExactlyAsTheDumpHadIt() {
        // Format v8 lets a file say its retrigger streams take their shape
        // from R13, and a .YMR says so, because that is where RhYMe keeps it.
        // So there is nothing to smuggle: the volume register carries the
        // dump's own levels on every frame the buzzer runs, bit 4 set or not.
        byte[] image = new Ymr()
                .frame(VOLUME_B, ENVELOPE_SHAPE, TIMER_B_EFFECT, TIMER_B_RATE)
                .frame(VOLUME_B)
                .frame()
                .stream(VOLUME_B, 0x1F, 0x0C)
                .stream(ENVELOPE_SHAPE, 0x0A)
                .stream(TIMER_B_EFFECT, YmrReader.TimerFrame.RTE)
                .stream(TIMER_B_RATE, PRESCALER, COUNTER)
                .build();

        Tune tune = convert(image);
        byte[] volumeB = tune.registers()[9];

        assertEquals(0x1F, volumeB[0] & 0xFF);
        assertEquals(0x0C, volumeB[1] & 0xFF);
        assertEquals(0x0C, volumeB[2] & 0xFF);
        assertTrue(tune.notes().isEmpty(), tune.notes().toString());
    }

    @Test
    void theShapeCarriedIsTheOneTheEnvelopeStreamLastPopped() {
        // A .YMR files the shape where the chip does, so the front end
        // resolves it here and the tune carries the answer: nothing
        // downstream depends on which format it came from.
        byte[] image = new Ymr()
                .frame(VOLUME_C, TIMER_D_EFFECT, TIMER_D_RATE)
                .frame(ENVELOPE_SHAPE)
                .frame()
                .stream(VOLUME_C, 0x10)
                .stream(ENVELOPE_SHAPE, 0x0A)
                .stream(TIMER_D_EFFECT, YmrReader.TimerFrame.RTE)
                .stream(TIMER_D_RATE, PRESCALER, COUNTER)
                .build();

        Tune tune = convert(image);

        // Frame 0 popped no shape, so the buzzer restarts the one the spec
        // says to assume; frames 1 and 2 carry what the stream popped, the
        // second of them because a shape stays in force until another comes.
        assertEquals(0x08, tune.shapes()[0]);
        assertEquals(0x0A, tune.shapes()[1]);
        assertEquals(0x0A, tune.shapes()[2]);
        // and the voice keeps its own byte, envelope-mode bit and all
        assertEquals(0x10, tune.registers()[10][0] & 0xFF);
    }

    @Test
    void registerThirteenStillSaysNothingOnAFrameThatDoesNotPopTheShape() {
        byte[] image = new Ymr()
                .frame(ENVELOPE_SHAPE)
                .frame()
                .frame(ENVELOPE_SHAPE)
                .frame()
                .stream(ENVELOPE_SHAPE, 0x0A, 0x0A)
                .build();

        byte[] shape = convert(image).registers()[13];

        // Writing R13 restarts the envelope, so the marker has to survive the
        // conversion untouched: the value the YMX pipeline reads as "leave the
        // envelope alone" is the same $FF the reader wrote.
        assertArrayEquals(new byte[] {0x0A, (byte) 0xFF, 0x0A, (byte) 0xFF}, shape);
    }

    // ---------------------------------------------------------------- samples

    @Test
    void aSampleTriggerPutsItsIndexInTheVoicesVolumeRegisterAndFlipsBitThree() {
        // The script reads a PCM stream's sample number out of R(8+voice), and
        // acts when a code byte CHANGES - so a second pop of the same index at
        // the same rate has to arrive as a different byte, which is what bit 3
        // is for.
        byte[] image = new Ymr()
                .frame(VOLUME_A, TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .frame()
                .frame(TIMER_A_SAMPLE)
                .frame()
                .stream(VOLUME_A, 0x0D)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, 7, 255)
                .stream(TIMER_A_SAMPLE, 1, 1)
                .sample(new byte[] {1, 2}, false, 0)
                .sample(new byte[] {3, 4, 5, 6}, false, 0)
                .build();

        Tune tune = convert(image);
        byte[] codes = tune.codes()[0];
        int pcm = Tune.KIND_PCM | voiceBits(0) | 7;

        assertEquals(pcm | YmrEffects.TRIGGER, codes[0] & 0xFF);
        assertEquals(pcm | YmrEffects.TRIGGER, codes[1] & 0xFF);   // held, not re-fired
        assertEquals(pcm, codes[2] & 0xFF);                    // popped again: a new byte
        assertEquals(codes[2], codes[3]);
        assertNotEquals(codes[1], codes[2]);
        // Sample 1, on every frame the code is armed - the script recomputes a
        // sample's length wherever its code changes, not only on the first
        // trigger, so the number has to be readable on all of them.
        for (int frame = 0; frame < 4; frame++) {
            assertEquals(1, tune.registers()[8][frame] & 31);
        }
    }

    @Test
    void aSampleThatHasPlayedOutLetsTheVolumeRegisterGoAgain() {
        // Two bytes at prescaler 1, counter 4 is a divisor of 16: the whole
        // sample and its marker are gone inside one frame, and the frame after
        // it the voice's own volume comes back.
        byte[] image = new Ymr()
                .frame(VOLUME_A, TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .frame()
                .stream(VOLUME_A, 0x0D)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, 1, 4)
                .stream(TIMER_A_SAMPLE, 0)
                .sample(new byte[] {1, 2}, false, 0)
                .build();

        Tune tune = convert(image);

        assertEquals(Tune.KIND_PCM | voiceBits(0) | YmrEffects.TRIGGER | 1,
                tune.codes()[0][0] & 0xFF);
        assertEquals(0, tune.codes()[0][1]);
        assertEquals(0, tune.registers()[8][0] & 31);
        assertEquals(0x0D, tune.registers()[8][1] & 0xFF);
    }

    @Test
    void aTriggerOfASampleThatIsNotInTheFileSaysSoAndPlaysNothing() {
        byte[] image = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, PRESCALER, COUNTER)
                .stream(TIMER_A_SAMPLE, 0)
                .build();

        Tune tune = convert(image);

        assertEquals(0, tune.codes()[0][0]);
        assertTrue(note(tune, "no block behind it"), tune.notes().toString());
    }

    @Test
    void samplesPastTheFormatsCeilingAreDroppedAndTriggeringOneIsReported() {
        // A YMX sample number is the five bits the script reads out of a volume
        // register, so the table stops at 32 however many a .ymr carries.
        Ymr builder = new Ymr()
                .frame(TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, PRESCALER, COUNTER)
                .stream(TIMER_A_SAMPLE, YmxFormat.MAX_SAMPLES);
        for (int i = 0; i <= YmxFormat.MAX_SAMPLES; i++) {
            builder.sample(new byte[] {1, 2}, false, 0);
        }

        Tune tune = convert(builder.build());

        assertEquals(YmxFormat.MAX_SAMPLES, tune.samples().length);
        assertEquals(0, tune.codes()[0][0]);
        assertTrue(note(tune, "dropped"), tune.notes().toString());
        assertTrue(note(tune, "past the 32"), tune.notes().toString());
    }

    @Test
    void aLoopedSampleIsCarriedWholeWithThePointItComesBackTo() {
        byte[] image = new Ymr()
                .frame(VOLUME_A)
                .stream(VOLUME_A, 0x0F)
                .sample(new byte[] {1, 2, 3, 4, 5, 6}, true, 2)
                .build();

        Tune tune = YmrEffects.convert(YmrReader.read(image), "test");

        // Since v10 the file says where a sample comes back to and the player
        // does the coming back, so the sample is the six bytes the .ymr
        // stores and the loop point rides beside it. Before that a loop had
        // to be written out again and again until some ceiling stopped it,
        // which made every long loop both wrong and enormous.
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, tune.samples()[0]);
        assertEquals(2, tune.sampleLoops()[0]);
    }

    @Test
    void aSampleThatEndsIsMarkedAsOneShotRatherThanLoopingToZero() {
        byte[] image = new Ymr()
                .frame(VOLUME_A)
                .stream(VOLUME_A, 0x0F)
                .sample(new byte[] {1, 2, 3, 4}, false, 0)
                .build();

        Tune tune = YmrEffects.convert(YmrReader.read(image), "test");

        // A loop point of zero is a real answer - a sample that repeats from
        // its first byte - so "does not loop" needs a value no length can
        // reach rather than the falsy one.
        assertEquals(YmxFormat.SAMPLE_ONE_SHOT, tune.sampleLoops()[0]);
    }

    @Test
    void aSampleThatDoesNotLoopIsHandedOverExactlyAsTheFileStoresIt() {
        // RhYMe's exporter has already reduced a sample to the 4-bit levels the
        // volume register takes, which is the one thing a .ymr hands over that
        // needs no conversion at all.
        byte[] levels = {0x08, 0x0F, 0x00, 0x08};
        byte[] image = new Ymr()
                .frame(VOLUME_A)
                .stream(VOLUME_A, 0x0F)
                .sample(levels, false, 0)
                .build();

        Tune tune = convert(image);

        assertArrayEquals(levels, tune.samples()[0]);
        assertTrue(tune.notes().isEmpty(), tune.notes().toString());
    }

    // ------------------------------------------------- and what the script does

    @Test
    void theScriptStartsTheRightStreamOnTheRightVoiceForEveryChannel() {
        byte[] image = new Ymr()
                .frame(VOLUME_A, VOLUME_B, VOLUME_C, ENVELOPE_SHAPE,
                        TIMER_A_EFFECT, TIMER_A_RATE, TIMER_B_EFFECT, TIMER_B_RATE,
                        TIMER_D_EFFECT, TIMER_D_RATE)
                .frame()
                .stream(VOLUME_A, 0x0D)
                .stream(VOLUME_B, 0x1F)
                .stream(VOLUME_C, 0x0C)
                .stream(ENVELOPE_SHAPE, 0x0A)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.PWM)
                .stream(TIMER_A_RATE, PRESCALER, COUNTER)
                .stream(TIMER_B_EFFECT, YmrReader.TimerFrame.RTE)
                .stream(TIMER_B_RATE, PRESCALER, COUNTER)
                .stream(TIMER_D_EFFECT, YmrReader.TimerFrame.PWM)
                .stream(TIMER_D_RATE, PRESCALER, COUNTER)
                .build();

        EffectScript.Result script = compile(convert(image));

        assertEquals(EffectScript.action(EffectScript.VERB_START_TOGGLE, 0, PRESCALER),
                script.actions()[0][0] & 0xFF);
        assertEquals(EffectScript.action(EffectScript.VERB_START_RETRIGGER, 1, PRESCALER),
                script.actions()[1][0] & 0xFF);
        assertEquals(EffectScript.action(EffectScript.VERB_START_TOGGLE, 2, PRESCALER),
                script.actions()[2][0] & 0xFF);
        assertEquals(0, script.actions()[3][0]);
        // Voices A and C are skipped for their toggle streams; B is not, since
        // a retrigger stream writes R13 and never touches a volume register.
        assertEquals(0b101, (script.m()[0] & 0xFF) >> EffectScript.M_SKIP_SHIFT);
    }

    @Test
    void aHeldSampleIsStartedOnceAndLeftToPlay() {
        byte[] image = new Ymr()
                .frame(VOLUME_A, TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .frame()
                .frame()
                .frame()
                .stream(VOLUME_A, 0x0D)
                .stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, 7, 255)
                .stream(TIMER_A_SAMPLE, 0)
                .sample(new byte[] {1, 2, 3, 4}, false, 0)
                .build();

        EffectScript.Result script = compile(convert(image));

        // A .ymr's trigger is a pop, not the code's continued presence, so the
        // frames after it are silent in the script: no second START_PCM, and
        // nothing at all where a YM dump would have re-fired the sample.
        assertEquals(EffectScript.action(EffectScript.VERB_START_PCM, 0, 7),
                script.actions()[0][0] & 0xFF);
        for (int frame = 1; frame < script.frames(); frame++) {
            assertEquals(0, script.m()[frame] & EffectScript.M_CHANNEL_0,
                    "channel 0 acted again on frame " + frame);
        }
        // And the mixer is the song's throughout: RhYMe's player never
        // disconnects a voice for an effect, so nothing is forced into R7.
        for (byte forced : script.r7force()) {
            assertEquals(0, forced);
        }
    }

    /**
     * The trigger frame of a sample forty levels long at prescaler 7, counter
     * 255 - a drum that runs for 43 frames - and the block behind it. The
     * caller adds the frames that do something to it.
     */
    private static Ymr drumOnFrameZero() {
        return new Ymr()
                .frame(VOLUME_A, TIMER_A_EFFECT, TIMER_A_RATE, TIMER_A_SAMPLE)
                .stream(VOLUME_A, 0x0D)
                .stream(TIMER_A_SAMPLE, 0)
                .sample(new byte[40], false, 0);
    }

    /** Frames that pop nothing at all, which change nothing at all. */
    private static Ymr quiet(Ymr builder, int count) {
        for (int frame = 0; frame < count; frame++) {
            builder.frame();
        }
        return builder;
    }

    @Test
    void anExplicitStopEndsARunningSampleOnTheFrameThatSaysSo() {
        // RhYMe routes an effect pop of 0 to _ymr_stop_channel, which stops the
        // timer, drops the sample and writes the voice's volume back out of
        // the shadow. Leaving the drum to its marker instead would play 38
        // frames - 760 ms - of it that the song ended.
        Ymr builder = drumOnFrameZero();
        quiet(builder, 4);                              // frames 1 to 4
        builder.frame(TIMER_A_EFFECT);                  // frame 5: the stop
        quiet(builder, 44);
        builder.stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE,
                        YmrReader.TimerFrame.NONE)
                .stream(TIMER_A_RATE, 7, 255);

        Tune tune = convert(builder.build());
        EffectScript.Result script = compile(tune);

        assertEquals(EffectScript.action(EffectScript.VERB_START_PCM, 0, 7),
                script.actions()[0][0] & 0xFF);
        // A hard stop: RELEASE with bit 0 clear stops the timer, where the bit
        // set would only mask a toggle stream's interrupt and leave it running.
        assertEquals(EffectScript.action(EffectScript.VERB_RELEASE, 0, 0),
                script.actions()[0][5] & 0xFF);
        // And the voice rejoins the frame write on that same frame. The player
        // applies the skip bits before the register burst and the script's actions after it, so
        // the voice's own volume is back on the chip inside the frame the song
        // placed it in - no skew to correct anywhere.
        assertEquals(EffectScript.M_CHANNEL_0 | EffectScript.M_SKIPS,
                script.m()[5] & 0xFF);
        assertEquals(0x0D, tune.registers()[8][5] & 0xFF);
        assertTrue(script.reopens().stream()
                .anyMatch(edge -> edge[0] == 5 && edge[1] == 0),
                script.reopens().toString());
        for (int frame = 6; frame < script.frames(); frame++) {
            assertEquals(0, script.m()[frame] & 0xFF, "acted at frame " + frame);
        }
    }

    @Test
    void aSampleThatRanOutIsNotStoppedAgainWhenItsCodeLetsGo() {
        // The code goes idle at the computed end whether or not the song ever
        // stopped it, and there the marker tick has already stopped the timer
        // and the script has already lifted the skip. Spending a RELEASE on
        // that would be a stream byte for stopping a stopped timer.
        Ymr builder = drumOnFrameZero();
        quiet(builder, 49);
        builder.stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE)
                .stream(TIMER_A_RATE, 7, 255);

        EffectScript.Result script = compile(convert(builder.build()));

        assertEquals(EffectScript.M_SKIPS, script.m()[43] & 0xFF);
        for (int frame = 1; frame < script.frames(); frame++) {
            assertEquals(0, script.m()[frame] & EffectScript.M_CHANNEL_0,
                    "channel 0 acted at frame " + frame);
        }
    }

    @Test
    void anEffectAfterAStoppedSampleArmsOnItsOwnFrame() {
        // The stop on frame 5 gives the voice up, so the PWM on frame 6 has
        // nothing to wait for. Deferring it to the sample's computed end would
        // start the square 37 frames late, over the drum tail.
        Ymr builder = drumOnFrameZero();
        quiet(builder, 4);
        builder.frame(TIMER_A_EFFECT);                  // frame 5: the stop
        builder.frame(TIMER_A_EFFECT, TIMER_A_RATE);    // frame 6: the PWM
        quiet(builder, 43);
        builder.stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE,
                        YmrReader.TimerFrame.NONE, YmrReader.TimerFrame.PWM)
                .stream(TIMER_A_RATE, 7, 255, PRESCALER, COUNTER);

        EffectScript.Result script = compile(convert(builder.build()));

        assertEquals(EffectScript.action(EffectScript.VERB_RELEASE, 0, 0),
                script.actions()[0][5] & 0xFF);
        assertEquals(EffectScript.action(EffectScript.VERB_START_TOGGLE, 0, PRESCALER),
                script.actions()[0][6] & 0xFF);
    }

    @Test
    void anEffectConfiguredOverALiveSampleTakesTheVoiceThere() {
        // The same thing with no stop between them: a .configure of a different
        // type reprograms the one timer the sample was ticking on, so the
        // sample ends there whether or not the song said stop first.
        Ymr builder = drumOnFrameZero();
        quiet(builder, 4);
        builder.frame(TIMER_A_EFFECT, TIMER_A_RATE);    // frame 5: the PWM
        quiet(builder, 44);
        builder.stream(TIMER_A_EFFECT, YmrReader.TimerFrame.SAMPLE,
                        YmrReader.TimerFrame.PWM)
                .stream(TIMER_A_RATE, 7, 255, PRESCALER, COUNTER);

        EffectScript.Result script = compile(convert(builder.build()));

        assertEquals(EffectScript.action(EffectScript.VERB_START_TOGGLE, 0, PRESCALER),
                script.actions()[0][5] & 0xFF);
        // The skip never lifts: the sample needed voice A skipped and so does
        // square, so this is a change of owner and not a sample-end edge.
        assertEquals(EffectScript.M_CHANNEL_0, script.m()[5] & 0xFF);
        assertTrue(script.reopens().isEmpty(), script.reopens().toString());
    }

    // --------------------------------------------------------------- fixtures

    private static Tune convert(byte[] image) {
        return YmrEffects.convert(YmrReader.read(image), "test");
    }

    private static EffectScript.Result compile(Tune tune) {
        return EffectScript.compile(tune, -1, 1, YmrEffects.TIMERS);
    }

    private static boolean note(Tune tune, String fragment) {
        return tune.notes().stream().anyMatch(n -> n.contains(fragment));
    }

    /**
     * Builds a .YMR image, storing every stream uncompressed - a ring size of 0,
     * which the format defines as "the bytes are the data". That is what lets a
     * fixture be written here at all: nothing in this repository packs ZX1, and
     * the reader has no way to tell an exporter's uncompressed stream from one
     * of these.
     *
     * <p>The header is fixed at twenty streams and the map is always written in
     * full, because a stream that is absent is absent by carrying an offset of
     * 0, not by being left out of the map.
     */
    private static final class Ymr {

        private static final int STREAMS = 20;
        private static final int HEADER_SIZE = 28 + STREAMS * 12;

        private final byte[][] streams = new byte[STREAMS][];
        private final ByteArrayOutputStream commands = new ByteArrayOutputStream();
        private final List<byte[]> sampleBlocks = new ArrayList<>();
        private int frameCount;
        private long loopFrame = 0xFFFFFFFFL;
        private int frameRate = 50;

        /** One frame, popping the given streams; they must be in ascending order. */
        Ymr frame(int... pops) {
            for (int pop : pops) {
                commands.write(pop);
            }
            commands.write(0x00);
            frameCount++;
            return this;
        }

        Ymr stream(int index, int... bytes) {
            byte[] entries = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                entries[i] = (byte) bytes[i];
            }
            streams[index] = entries;
            return this;
        }

        Ymr sample(byte[] data, boolean looped, int loopStart) {
            ByteArrayOutputStream block = new ByteArrayOutputStream();
            writeU32(block, data.length);
            block.writeBytes(data);
            block.write(looped ? 1 : 0);
            writeU16(block, loopStart);
            block.write(0);
            sampleBlocks.add(block.toByteArray());
            return this;
        }

        byte[] build() {
            streams[0] = commands.toByteArray();

            int sampleBytes = 0;
            for (byte[] block : sampleBlocks) {
                sampleBytes += block.length;
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes("YMR!".getBytes(StandardCharsets.US_ASCII));
            writeU16(out, 0x0103);
            writeU32(out, frameCount);
            writeU32(out, loopFrame);
            writeU16(out, frameRate);
            writeU16(out, sampleBlocks.size());
            writeU32(out, 2000000L);
            writeU16(out, STREAMS);
            writeU32(out, 0);                           // reserved

            int offset = HEADER_SIZE + sampleBytes;
            for (byte[] stream : streams) {
                if (stream == null) {
                    writeU32(out, 0);                   // the stream is absent
                    writeU32(out, 0);
                    writeU16(out, 0);
                    writeU16(out, 0);
                    continue;
                }
                writeU32(out, offset);
                writeU32(out, 0);                       // loop offset
                writeU16(out, 0);                       // ring size: uncompressed
                writeU16(out, 0);                       // reserved
                offset += stream.length;
            }

            for (byte[] block : sampleBlocks) {
                out.writeBytes(block);
            }
            for (byte[] stream : streams) {
                if (stream != null) {
                    out.writeBytes(stream);
                }
            }
            return out.toByteArray();
        }

        private static void writeU16(ByteArrayOutputStream out, int value) {
            out.write(value >> 8);
            out.write(value);
        }

        private static void writeU32(ByteArrayOutputStream out, long value) {
            out.write((int) (value >> 24));
            out.write((int) (value >> 16));
            out.write((int) (value >> 8));
            out.write((int) value);
        }
    }
}
