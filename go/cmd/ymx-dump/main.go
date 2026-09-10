// Command ymx-dump reads a .ymx file out as text: its header, and every
// stream decoded to one byte a frame (SPEC.md 2).
//
// It converts nothing and judges nothing. A tool in another repository
// reads this rather than the container, so that what a .ymx holds is read
// once, here, by the tree that writes them.
package main

import (
	"bufio"
	"fmt"
	"os"

	"github.com/odipar/ymx/go/check"
	"github.com/odipar/ymx/go/internal/ymx"
)

const usage = `Usage: ymx-dump file.ymx

  The header as "name value" lines, then one line a frame: the frame
  number and the twenty-five stream bytes of it, in the order SPEC.md 2
  gives them.`

func main() {
	if len(os.Args) != 2 || os.Args[1] == "-help" {
		fmt.Fprintln(os.Stderr, usage)
		os.Exit(2)
	}
	file, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "ymx-dump: cannot read "+os.Args[1])
		os.Exit(1)
	}
	read, err := check.ReadFile(file)
	if err != nil {
		fmt.Fprintln(os.Stderr, "ymx-dump: "+err.Error())
		os.Exit(1)
	}
	out := bufio.NewWriter(os.Stdout)
	defer out.Flush()
	fmt.Fprintf(out, "frames %d\nrate %d\nloop %d\nflags %d\nring %d\nstreams %d\n",
		read.Frames, read.Rate, read.LoopFrame, read.Flags, read.Ring, ymx.Streams)
	fmt.Fprintf(out, "samples %d\n", len(read.Lengths))
	for i := range read.Lengths {
		fmt.Fprintf(out, "sample %d %d %d", i, read.Lengths[i], read.Loops[i])
		for _, level := range read.Samples[i] {
			fmt.Fprintf(out, " %d", level)
		}
		fmt.Fprintln(out)
	}
	for frame := 0; frame < read.Frames; frame++ {
		fmt.Fprintf(out, "%d", frame)
		for stream := 0; stream < ymx.Streams; stream++ {
			fmt.Fprintf(out, " %d", read.Streams[stream][frame])
		}
		fmt.Fprintln(out)
	}
}
