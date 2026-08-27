package pack

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/odipar/ymx/internal/ym"
)

// TuneSet is what a set of dumps calls itself: each tune's name, a composer
// they all share, and a title made of the lot. A name that says nothing gives
// way to the file stem; a composer is only claimed when every tune agrees.
type TuneSet struct {
	Names    []string
	Composer string
	Title    string
}

// SetOf reads the dumps and says what the set calls itself.
func SetOf(command string, tunes []string) (TuneSet, error) {
	names := make([]string, 0, len(tunes))
	composer := ""
	claimed := false
	agree := true
	for _, tune := range tunes {
		song, err := readSong(command, tune)
		if err != nil {
			return TuneSet{}, err
		}
		name := strings.TrimSpace(song.Name)
		if saysNothing(name) {
			name = Stem(tune)
		}
		names = append(names, name)
		author := strings.TrimSpace(song.Author)
		if !claimed && author != "" {
			composer, claimed = author, true
		} else if author != composer {
			agree = false
		}
	}
	set := TuneSet{Names: names, Title: strings.Join(names, " / ")}
	if agree {
		set.Composer = composer
	}
	return set, nil
}

// PlayerHz is the rate every tune must share: one SNDH declares one.
func PlayerHz(command, tune string) (int, error) {
	song, err := readSong(command, tune)
	if err != nil {
		return 0, err
	}
	return song.PlayerHz, nil
}

// Stem is a dump's file name without its extension.
func Stem(tune string) string {
	name := filepath.Base(tune)
	if strings.EqualFold(filepath.Ext(name), ".ym") {
		return name[:len(name)-3]
	}
	return name
}

// readSong reads one dump. A file that does not open names the command,
// because the fault is the command line; a file that opens and is not a
// dump names the file, because the fault is in it. The other two trees
// split it the same way, and nothing of the system call reaches either
// message.
func readSong(command, tune string) (*ym.Song, error) {
	data, err := os.ReadFile(tune)
	if err != nil {
		return nil, fmt.Errorf("%s: cannot read %s", command, tune)
	}
	song, err := ym.Read(data)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", tune, err)
	}
	return song, nil
}

func saysNothing(name string) bool {
	lower := strings.ToLower(name)
	return lower == "" || lower == "unknown" || lower == "untitled" ||
		lower == "<unknown>"
}
