package st4

import (
	"fmt"
	"os"
	"time"
)

// The progress report the optimal parsers print: an exact percentage of the
// parse's inner-loop steps, and a time estimate fitted to how the parse has
// been slowing down.

const (
	meterWarmup   = 5
	meterBaseline = 15
)

// Meter counts a parse's steps and draws where something is watching.
type Meter struct {
	enabled   bool
	total     int64
	started   time.Time
	tickNanos [101]int64
	steps     int64
	shown     int
}

// NewMeter opens a meter over a parse of this many steps. A meter redraws
// one line with a carriage return, which is a display and not output: piped
// or redirected it is a wall of percentages in whatever reads it, so it draws
// only where something is watching.
func NewMeter(total int64, enabled bool) *Meter {
	return &Meter{
		enabled: enabled && isTerminal(os.Stdout),
		total:   total,
		started: time.Now(),
		shown:   -1,
	}
}

func isTerminal(file *os.File) bool {
	info, err := file.Stat()
	return err == nil && info.Mode()&os.ModeCharDevice != 0
}

// TotalSteps is the parse's total steps: positions skip..count-1, each
// against its window.
func TotalSteps(count, skip, offsetLimit int) int64 {
	return stepsBefore(count, offsetLimit) - stepsBefore(skip, offsetLimit)
}

func stepsBefore(end, offsetLimit int) int64 {
	if end <= 0 {
		return 0
	}
	ramp := int64(end) - 1
	if ramp > int64(offsetLimit) {
		ramp = int64(offsetLimit)
	}
	flat := int64(end) - 1 - int64(offsetLimit)
	if flat < 0 {
		flat = 0
	}
	return 1 + ramp*(ramp+1)/2 + flat*int64(offsetLimit)
}

// Advance takes one position's worth of steps, and reports when the percent
// moves.
func (m *Meter) Advance(delta int64) {
	m.steps += delta
	if !m.enabled {
		return
	}
	percent := int(m.steps * 100 / m.total)
	if percent == m.shown {
		return
	}
	m.shown = percent
	now := time.Since(m.started).Nanoseconds()
	m.tickNanos[percent] = now
	fmt.Printf("\r[%3d%%] %-12s", percent, m.estimate(percent, now))
}

// Finish draws the 100% line with the elapsed time; once, at the end.
func (m *Meter) Finish() {
	if m.steps != m.total {
		panic("the step count is meant to be exact, not an estimate")
	}
	if m.enabled {
		fmt.Printf("\r[100%%] %-12s\n",
			duration(time.Since(m.started).Nanoseconds()))
	}
}

// estimate is the time left, or "" until there is enough history to say.
func (m *Meter) estimate(percent int, now int64) string {
	baseAt := meterWarmup
	for baseAt < percent && m.tickNanos[baseAt] == 0 {
		baseAt++ // a percent the loop stepped over
	}
	mid := (baseAt + percent) / 2
	for mid > baseAt && m.tickNanos[mid] == 0 {
		mid--
	}
	if mid <= baseAt || mid >= percent || percent-baseAt < meterBaseline {
		return "" // too little history to fit
	}
	half := float64(mid - baseAt)
	span := float64(percent - baseAt)
	untilMid := float64(m.tickNanos[mid] - m.tickNanos[baseAt])
	untilNow := float64(now - m.tickNanos[baseAt])
	square := (untilNow*half - untilMid*span) / (half * span * (span - half))
	linear := (untilMid - square*half*half) / half
	whole := 100.0 - float64(baseAt)
	left := linear*whole + square*whole*whole - untilNow
	if !(left > 0) {
		return "" // NaN, or already there
	}
	return duration(int64(left)) + " left"
}

// duration is seconds, in the shortest readable form, rounded not floored.
func duration(nanos int64) string {
	if nanos < 0 {
		nanos = 0
	}
	seconds := (nanos + 500_000_000) / 1_000_000_000
	if seconds < 60 {
		return fmt.Sprintf("%ds", seconds)
	}
	return fmt.Sprintf("%dm %02ds", seconds/60, seconds%60)
}
