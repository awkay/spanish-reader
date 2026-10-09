package main

import (
	"strings"
	"unicode"
)

// Lesson text from a transcript, and the reverse: sentence times for a page of that text. The clients split text
// into sentences and pages themselves, so the server matches their sentences back to the recognized words by
// comparing runs of letters/digits (case- and accent-insensitive). Punctuation and sentence boundaries don't
// matter, so client tokenizer details can change freely.

const (
	paragraphPauseMs = 1500 // a pause this long after a sentence end starts a new paragraph
	paragraphMaxWord = 120  // ... and so does a sentence end after this many words
)

// lessonText joins the segments into paragraphs.
func lessonText(t *Transcript) string {
	segs := t.Segments
	if len(segs) == 0 && len(t.Words) > 0 {
		parts := make([]string, len(t.Words))
		for i, w := range t.Words {
			parts[i] = w.W
		}
		segs = []Segment{{Text: strings.Join(parts, " "), S: t.Words[0].S, E: t.Words[len(t.Words)-1].E}}
	}
	var b strings.Builder
	words := 0
	for i, s := range segs {
		if i > 0 {
			endsSentence := strings.ContainsAny(lastRune(segs[i-1].Text), ".?!…")
			if endsSentence && (s.S-segs[i-1].E >= paragraphPauseMs || words >= paragraphMaxWord) {
				b.WriteString("\n\n")
				words = 0
			} else {
				b.WriteByte(' ')
			}
		}
		b.WriteString(s.Text)
		words += len(strings.Fields(s.Text))
	}
	return b.String()
}

func lastRune(s string) string {
	s = strings.TrimRight(s, " \"'»”)")
	if s == "" {
		return ""
	}
	r := []rune(s)
	return string(r[len(r)-1])
}

var foldMap = map[rune]rune{'á': 'a', 'à': 'a', 'ä': 'a', 'â': 'a', 'é': 'e', 'è': 'e', 'ë': 'e', 'ê': 'e',
	'í': 'i', 'ì': 'i', 'ï': 'i', 'î': 'i', 'ó': 'o', 'ò': 'o', 'ö': 'o', 'ô': 'o', 'ú': 'u', 'ù': 'u', 'ü': 'u',
	'û': 'u', 'ñ': 'n', 'ç': 'c'}

// matchTokens splits text into lowercased, accent-folded runs of letters/digits.
func matchTokens(text string) []string {
	var out []string
	var cur []rune
	flush := func() {
		if len(cur) > 0 {
			out = append(out, string(cur))
			cur = cur[:0]
		}
	}
	for _, r := range strings.ToLower(text) {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			if f, ok := foldMap[r]; ok {
				r = f
			}
			cur = append(cur, r)
		case unicode.Is(unicode.Mn, r): // combining accents (decomposed text)
		default:
			flush()
		}
	}
	flush()
	return out
}

// timedToken is one letter run of a recognized word, carrying that word's time.
type timedToken struct {
	tok  string
	s, e int
}

func timeline(words []TimedWord) []timedToken {
	var out []timedToken
	for _, w := range words {
		for _, tok := range matchTokens(w.W) {
			out = append(out, timedToken{tok, w.S, w.E})
		}
	}
	return out
}

const (
	startWindow = 8 // page tokens compared when looking for where the page starts
	lookahead   = 6 // recognized tokens skipped at most to resynchronize
)

// alignSentences returns [startMs, endMs] for each sentence in absolute media time. Sentences without a single
// matched token get times interpolated from their neighbours; ok is false when no sentence matched at all.
func alignSentences(words []TimedWord, sentences []string) (out []Timing, ok bool) {
	tl := timeline(words)
	out = make([]Timing, len(sentences))
	if len(tl) == 0 {
		return out, false
	}
	type span struct{ first, last int }
	toks := make([][]string, len(sentences))
	var page []string
	for i, s := range sentences {
		toks[i] = matchTokens(s)
		page = append(page, toks[i]...)
	}
	j := pageStart(tl, page)
	spans := make([]span, len(sentences))
	for i, ts := range toks {
		spans[i] = span{-1, -1}
		for _, tok := range ts {
			found := -1
			for k := j; k < len(tl) && k <= j+lookahead; k++ {
				if tl[k].tok == tok {
					found = k
					break
				}
			}
			if found < 0 {
				continue
			}
			if spans[i].first < 0 {
				spans[i].first = found
			}
			spans[i].last = found
			j = found + 1
		}
	}
	for i, sp := range spans {
		if sp.first >= 0 {
			out[i] = Timing{tl[sp.first].s, tl[sp.last].e}
			ok = true
		} else {
			out[i] = Timing{-1, -1}
		}
	}
	// Interpolate unmatched sentences between the nearest matched ones.
	for i := 0; i < len(out); i++ {
		if out[i][0] >= 0 {
			continue
		}
		k := i
		for k < len(out) && out[k][0] < 0 {
			k++
		}
		var from, to int
		switch {
		case i > 0 && k < len(out): // between two matched sentences
			from, to = out[i-1][1], out[k][0]
		case i > 0: // trailing: nothing to measure against
			from, to = out[i-1][1], out[i-1][1]
		case k < len(out): // leading
			from, to = out[k][0], out[k][0]
		default: // nothing matched at all
			return out, false
		}
		n := k - i
		for m := 0; m < n; m++ {
			out[i+m] = Timing{from + (to-from)*m/n, from + (to-from)*(m+1)/n}
		}
		i = k - 1
	}
	return out, ok
}

// pageStart finds the recognized-token index where the page begins: the first position whose next tokens best
// match the page's first tokens.
func pageStart(tl []timedToken, page []string) int {
	n := min(startWindow, len(page))
	if n == 0 {
		return 0
	}
	best, bestScore := 0, -1
	for p := 0; p < len(tl); p++ {
		score := 0
		for k := 0; k < n && p+k < len(tl); k++ {
			if tl[p+k].tok == page[k] {
				score++
			}
		}
		if score > bestScore {
			best, bestScore = p, score
			if score == n {
				break
			}
		}
	}
	return best
}
