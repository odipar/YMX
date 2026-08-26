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
func SetOf(tunes []string) (TuneSet, error) {
	names := make([]string, 0, len(tunes))
	composer := ""
	claimed := false
	agree := true
	for _, tune := range tunes {
		song, err := readSong(tune)
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
func PlayerHz(tune string) (int, error) {
	song, err := readSong(tune)
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

func readSong(tune string) (*ym.Song, error) {
	data, err := os.ReadFile(tune)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", tune, err)
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
