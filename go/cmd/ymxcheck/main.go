// Command ymxcheck reads a packed tune back against SPEC.md §9.3, the rules
// a player does not check.
//
// One line per file - "within §9.3", or a count and one line per place the
// file leaves them, each naming the frame and the rule. A non-zero exit
// where any file reports one.
package main

import (
	"fmt"
	"os"

	"github.com/odipar/ymx/internal/check"
)

func main() {
	failed := 0
	for _, name := range os.Args[1:] {
		file, err := os.ReadFile(name)
		if err != nil {
			fmt.Fprintln(os.Stderr, "ymxcheck: cannot read "+name)
			os.Exit(1)
		}
		faults := check.Check(file)
		if len(faults) == 0 {
			fmt.Println(name + ": within §9.3")
		} else {
			fmt.Printf("%s: %d outside §9.3\n", name, len(faults))
		}
		for _, fault := range faults {
			fmt.Println("  " + fault.String())
			failed = 1
		}
	}
	os.Exit(failed)
}
