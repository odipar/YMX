# Register clustering: correlated pairs in one stream

**Question.** ymx packs each YM register as its own stream because each
register's own history is what repeats. But some registers move together -
a channel's fine and coarse period, the envelope period pair. Would
interleaving correlated pairs into one stream pack better, with the
converter deciding per tune which clustering wins?

**Method.** For every tune the full corpus could read (514 files), each
candidate cluster was packed both ways with the real St4EventOptimizer:
separately at k=1 with a 960-frame window, and interleaved (joint) at k=1
and k=2 with the same window in frames. "Best" keeps the smaller per tune -
what an adaptive converter would do.

**Numbers** (bytes over 514 tunes; "joint wins" = tunes where interleaving
beat separate):

| cluster | separate | joint k1 | joint k2 | per-tune best | joint wins |
|---|---:|---:|---:|---:|---:|
| (0,1) period A | 495,526 | +0.6% | +12.7% | -4.3% | 328/514 |
| (2,3) period B | 393,128 | -3.0% | +8.1% | -7.3% | 372/514 |
| (4,5) period C | 441,552 | -8.0% | +1.8% | -11.1% | 424/514 |
| (11,12) envelope | 38,254 | -33.4% | -35.0% | -37.1% | 509/514 |
| (8,9,10) volumes | 304,924 | +98.5% | - | -2.2% | 113/514 |

Adaptive clustering saves 8.3% of the clustered registers' bytes, about 5%
of a file - 250-500 bytes per tune. It is RAM-neutral (same bytes,
interleaved) and roughly cycle-neutral (a second cursor costs ~40
cycles/frame; fewer, longer refills give most of it back). The theory
held exactly: joint streams win where registers change together (the
envelope pair almost always), and lose where they do not (the volume
triple jointly is a catastrophe - a match must cover every clustered
register's history at once).

**Verdict.** Declined. The adaptive version needs a stream-layout menu in
the header, and every consumer pays for the configuration space forever:
init derives the layout, the burst patcher goes table-driven, refills need
per-stream budgets, the rig's verification matrix multiplies. A permanent
complexity tax for bytes that were never this project's pressure point -
the pressure points are cycles and RAM, and clustering moves neither.

**The door left open.** If file size ever becomes the constraint (many
tunes on one disk), the cheap variant is fixed, menu-less, always-on
clustering of (2,3), (4,5) and (11,12): one layout, two cursors, no
adaptivity, -3.3% of register data. The E1:T1/E2:T2 effect pairs would
likely join for the same correlation reason the envelope pair wins.

**Postscript 2026-08-21.** Those effect pairs have different names now:
since v5 they are the compiled script's A/P pairs, and four of them since
v7 - A0:P0 .. A3:P3. The correlation argument survives the rename, twice
over on a YM tune (a YM frame starts at most two effects, so the other two
channels' streams pack to nothing) and four times for a source that names
all four channels.
