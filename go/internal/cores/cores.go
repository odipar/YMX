// Package cores carries the prebuilt 68000 binaries a standalone build was
// published with, so it combines an SNDH file or a program without a
// repository beside it.
//
// A build made from a tree whose dist/ was empty carries none, and Stage
// then leaves the combiners to resolve them as they always have.
package cores

import (
	"embed"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

//go:embed data
var data embed.FS

// Read gives one binary's bytes, or nil where this build carries none. The
// name is matched on its tail, so a data directory holding an older
// release's binaries offers nothing here by accident.
func Read(wanted string) []byte {
	entries, err := fs.ReadDir(data, "data")
	if err != nil {
		return nil
	}
	for _, entry := range entries {
		if !strings.HasSuffix(entry.Name(), wanted) {
			continue
		}
		bytes, err := fs.ReadFile(data, "data/"+entry.Name())
		if err != nil {
			return nil
		}
		return bytes
	}
	return nil
}

// Staged is what Stage wrote, and what to put back when the run ends.
type Staged struct {
	dir   string
	saved map[string]string
}

// Stage writes the binaries a run needs to a temporary directory and points
// YMX_CORE and YMX_STUB at them, which is the seam the combiners already
// read. Close removes the directory and puts back whatever the caller had
// set. A binary this build does not carry is left to the combiners.
func Stage(core, stub string) (*Staged, error) {
	coreBytes := Read(core)
	var stubBytes []byte
	if stub != "" {
		stubBytes = Read(stub)
	}
	if coreBytes == nil && stubBytes == nil {
		return &Staged{}, nil // nothing carried
	}
	dir, err := os.MkdirTemp("", "ymx-")
	if err != nil {
		return nil, fmt.Errorf("cannot stage the carried binaries: %w", err)
	}
	staged := &Staged{dir: dir, saved: map[string]string{}}
	write := func(variable, name string, bytes []byte) error {
		if bytes == nil {
			return nil
		}
		path := filepath.Join(dir, name)
		if err := os.WriteFile(path, bytes, 0o644); err != nil {
			return err
		}
		staged.saved[variable] = os.Getenv(variable)
		return os.Setenv(variable, path)
	}
	if err := write("YMX_CORE", "core.bin", coreBytes); err != nil {
		staged.Close()
		return nil, err
	}
	if err := write("YMX_STUB", "stub.bin", stubBytes); err != nil {
		staged.Close()
		return nil, err
	}
	return staged, nil
}

// Close puts the environment back and removes what Stage wrote.
func (s *Staged) Close() {
	for variable, was := range s.saved {
		if was == "" {
			os.Unsetenv(variable)
		} else {
			os.Setenv(variable, was)
		}
	}
	if s.dir != "" {
		os.RemoveAll(s.dir)
	}
}
