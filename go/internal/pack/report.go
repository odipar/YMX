package pack

import (
	"fmt"
	"io"
	"math"
	"strings"

	"github.com/odipar/ymx/internal/ym"
	"github.com/odipar/ymx/internal/ymx"
)

// What a pack cost, stream by stream. Every command that packs writes this
// same report, so a tune reads the same whether it was packed on its own or
// on the way to a program. A command on its way somewhere else leaves the
// per-stream lines out and writes the rest. It lived in one command's main
// before, which is how the play path came to print a pack it never described.

// Banner names the packer and the release it comes from, the line every
// command that packs opens with.
func Banner() string {
	return "YMX: YM chiptune packer v" + ymx.ReleaseName() +
		" by Robbert van Dalen, streaming ST4"
}

// songNotes describe the dump a pack started from: what it is, the samples it
// carries, and every effect frame the packer dropped. A dropped frame is the
// one thing here a listener can hear, so it is a note and not a detail.
func songNotes(song *ym.Song, effects *ymx.Extraction) []string {
	name := song.Name
	if strings.TrimSpace(name) == "" {
		name = "(untitled)"
	}
	by := ""
	if strings.TrimSpace(song.Author) != "" {
		by = " by " + song.Author
	}
	order := ""
	if !song.Interleaved {
		order = " (de-interleaved)"
	}
	notes := []string{song.Format + ": " + name + by + order}

	if len(effects.Samples) > 0 {
		bytes := 0
		for _, sample := range effects.Samples {
			bytes += len(sample) + 1
		}
		notes = append(notes, fmt.Sprintf("%d digidrum%s, %d bytes",
			len(effects.Samples), plural(len(effects.Samples)), bytes))
	}
	if effects.Sinus > 0 {
		notes = append(notes, fmt.Sprintf("Warning: %d Sinus-SID frame%s"+
			" dropped (the reference player runs an empty handler)",
			effects.Sinus, plural(effects.Sinus)))
	}
	if effects.TooFast > 0 {
		notes = append(notes, fmt.Sprintf("Warning: %d effect frame%s"+
			" dropped: timer above %d Hz",
			effects.TooFast, plural(effects.TooFast), ymx.MaxTimerHz))
	}
	if effects.Inert > 0 {
		notes = append(notes, fmt.Sprintf("Warning: %d effect frame%s"+
			" dropped: a prescaler of 0 is the MFP's stopped state, a counter"+
			" of 0 is 256, and neither is armed here",
			effects.Inert, plural(effects.Inert)))
	}
	if effects.MissingDrum > 0 {
		notes = append(notes, fmt.Sprintf("Warning: %d drum trigger%s"+
			" dropped: no such sample",
			effects.MissingDrum, plural(effects.MissingDrum)))
	}
	for _, note := range effects.Notes {
		notes = append(notes, "Warning: "+note)
	}
	return notes
}

// plural is the "s" a count takes, or "" for one of them.
func plural(count int) string {
	if count == 1 {
		return ""
	}
	return "s"
}

// percent is part of whole, in percent, rounded the way the tree this was
// ported from rounds it: a half goes up. Go and .NET take a half to the even
// digit, so 528 of 8448 reads 6.2 there and 6.3 in Java, and the three trees
// must not disagree about a figure a reader compares between them.
func percent(part, whole int) float64 {
	return math.Round(100.0*float64(part)/float64(whole)*10) / 10
}

// effectNames are the eleven script streams, in the order they follow the
// fourteen register streams.
var effectNames = [...]string{"M ", "X ", "T ", "A0", "P0", "A1", "P1",
	"A2", "P2", "A3", "P3"}

// Report writes the tune's shape, one line per stream with what it packed
// to, and what a player needs to decode it.
func Report(out io.Writer, result *ymx.EncodeResult) {
	report(out, result, true)
}

// ReportQuietly writes the same report with the per-stream lines dropped. A
// command on its way to an SNDH file or a program packs several tunes, and one
// line of ratios per stream per tune is noise in a build log.
//
// The other two trees drop those lines by filtering the text after it is
// formatted, because their packer sits behind a command's main with no seam at
// the report. This tree has the seam, so it selects the lines instead of
// matching them back out of the output.
func ReportQuietly(out io.Writer, result *ymx.EncodeResult) {
	report(out, result, false)
}

// report writes the report; streams selects the per-stream lines.
func report(out io.Writer, result *ymx.EncodeResult, streams bool) {
	// What was packed: one byte per frame per stream, script included.
	raw := result.Script.Frames * ymx.Streams
	tune := result.Tune
	fmt.Fprintf(out, "%d frames at %d Hz (%d:%02d), %d rings of %d bytes,"+
		" %d per call\n",
		tune.Frames, tune.FrameRate,
		tune.Frames/tune.FrameRate/60, tune.Frames/tune.FrameRate%60,
		ymx.Streams, result.RingSize, result.Chunk)
	fmt.Fprintln(out, result.StartingOver())
	for _, note := range result.Notes {
		fmt.Fprintln(out, note)
	}

	if streams {
		for _, stream := range result.Streams {
			name := fmt.Sprintf("R%-2d", stream.Register)
			if stream.Register >= ymx.RegisterStreams {
				name = effectNames[stream.Register-ymx.RegisterStreams] + " "
			}
			fmt.Fprintf(out, "  %s %6d -> %6d bytes (%5.1f%%)\n",
				name, stream.Frames, stream.PackedSize,
				percent(stream.PackedSize, stream.Frames))
		}
	}

	fmt.Fprintf(out, "Packed %d register bytes into %d (%.1f%%),"+
		" file %d bytes\n",
		raw, result.PackedSize(),
		percent(result.PackedSize(), raw), len(result.File))

	flags := int(result.File[ymx.OffsetFlags])<<8 |
		int(result.File[ymx.OffsetFlags+1])
	// The chunk is a slot count: one stream refilled per call, so it has to
	// cover the streams this tune DECODES, not all the format defines.
	live := ymx.LiveStreams(flags)
	fmt.Fprintf(out, "Player needs %d bytes of ring plus its state, and"+
		" decodes %d of the %d streams - one refill a call, so C=%d covers"+
		" them with %d slots idle\n",
		ymx.Streams*result.RingSize, live, ymx.Streams,
		result.Chunk, result.Chunk-live)
	for _, note := range result.Script.Notes {
		fmt.Fprintln(out, note)
	}

	if result.LongestOp() > 65535 {
		// A literal run, the one operation ZX1 cannot split. Only a tune
		// longer than 65535 frames with a register that never repeats can
		// reach this, and the 68000 decoder would mis-decode it.
		fmt.Fprintf(out, "Warning: longest operation is %d bytes, over the"+
			" 65535 the 68000 decoder can represent: do not play this"+
			" file\n", result.LongestOp())
	}
}
