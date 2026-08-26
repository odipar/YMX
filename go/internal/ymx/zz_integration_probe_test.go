package ymx

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

// TestProbeHeaders dumps every field ReadHeader reads, for comparison against
// the C# tool. Temporary; delete after the check.
func TestProbeHeaders(t *testing.T) {
	dir := os.Getenv("PROBE_DIR")
	if dir == "" {
		t.Skip("no PROBE_DIR")
	}
	names, _ := filepath.Glob(filepath.Join(dir, "*.ymx"))
	sort.Strings(names)
	out, err := os.Create(os.Getenv("PROBE_OUT"))
	if err != nil {
		t.Fatal(err)
	}
	defer out.Close()
	for _, name := range names {
		h, err := ReadHeader(name)
		if err != nil {
			fmt.Fprintf(out, "%s\tERROR\t%v\n", filepath.Base(name), err)
			continue
		}
		fmt.Fprintf(out, "%s\tring=%d\tchunk=%d\tunit=%d\thz=%d\tflags=%d\tframes=%d\ttimers=%d\tclaimed=%d\tfrms=%d\tshape=%s\n",
			filepath.Base(name), h.Ring, h.Chunk, h.Unit, h.Hz, h.Flags,
			h.Frames, h.Timers, h.ClaimedTimers(), h.Frms(), h.Shape())
	}
}

// TestProbeSndh runs the whole combine, so the bytes can be compared against
// the C# tool's. Temporary; delete after the check.
func TestProbeSndh(t *testing.T) {
	list := os.Getenv("PROBE_TUNES")
	if list == "" {
		t.Skip("no PROBE_TUNES")
	}
	tunes, _ := filepath.Glob(list)
	sort.Strings(tunes)
	options, err := SndhOptionsOf(os.Getenv("PROBE_OUT"), tunes, "Probe",
		"Nobody", nil, false, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := BuildSndhWithCore(options, os.Getenv("YMX_CORE")); err != nil {
		t.Fatal(err)
	}
}
