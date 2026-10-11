package main

import (
	"strings"
	"sync"
)

// Как бот понимает, что обращаются к нему.
//
// В личке бот отвечает на всё: там он один на один с покупателем.
// В группе — только когда его позвали: «бот, каталог», «@shopbot заказ»,
// «/каталог». Слова можно писать в любом порядке и с любыми знаками:
// «бот оформи заказ» и «заказ, бот» понимаются одинаково.
//
// Реакции на случайные слова («цена», «хочу») намеренно нет: в большой
// группе бот, влезающий в чужие разговоры, быстро начинает раздражать.

// Намерения покупателя.
const (
	intentNone     = ""
	intentMenu     = "menu"
	intentCatalog  = "catalog"
	intentCart     = "cart"
	intentCheckout = "checkout"
	intentCancel   = "cancel"
)

// Слова, по которым узнаём намерение. Сравниваем по началу слова, поэтому
// «каталога», «заказать», «оформи» и «корзину» тоже подходят.
var intentWords = []struct {
	intent string
	stems  []string
}{
	{intentCancel, []string{"отмена", "отменить", "стоп", "хватит", "передумал", "передумала"}},
	{intentCheckout, []string{"оформ", "заказать", "заказ", "куплю", "купить", "беру"}},
	{intentCart, []string{"корзин"}},
	{intentCatalog, []string{"каталог", "товар", "ассортимент", "прайс", "цены"}},
	{intentMenu, []string{"меню", "start", "начать", "привет", "помощь", "help"}},
}

// Слова-обращения к боту в группе. По умолчанию «бот», но в приложении
// можно задать любые — например «маруся», если бота так зовут.
var defaultCallWords = []string{"бот", "bot"}

var (
	callMu    sync.RWMutex
	callWords = defaultCallWords
)

// setCallWords принимает строку из приложения: «бот, маруся, помощник».
func setCallWords(list string) {
	var words []string
	split := func(r rune) bool {
		return r == ',' || r == ';' || r == '.' || r == '/' || r == '|' ||
			r == ' ' || r == '\n' || r == '\t' || r == '\r'
	}
	for _, w := range strings.FieldsFunc(strings.ToLower(list), split) {
		if w = strings.TrimSpace(w); len([]rune(w)) >= 3 {
			words = append(words, w)
		}
	}
	callMu.Lock()
	if len(words) == 0 {
		callWords = defaultCallWords
	} else {
		callWords = words
	}
	callMu.Unlock()
}

// currentCallWords — слова-обращения для показа в приложении.
func currentCallWords() []string {
	callMu.RLock()
	defer callMu.RUnlock()
	out := make([]string, len(callWords))
	copy(out, callWords)
	return out
}

// isCallWord — обращение ли это.
//
// Падежи ловим по основе: у «маруся» она «марус», поэтому подходят и
// «марусе», и «марусю». Хвост при этом разрешаем короткий — не длиннее
// двух букв, иначе «ботинок» и «марусенька» тоже считались бы обращением.
func isCallWord(word string) bool {
	callMu.RLock()
	defer callMu.RUnlock()
	for _, w := range callWords {
		if word == w {
			return true
		}
		stem := callStem(w)
		if strings.HasPrefix(word, stem) && len([]rune(word))-len([]rune(stem)) <= 2 {
			return true
		}
	}
	return false
}

// callStem — слово без хвостовой гласной: «маруся» → «марус», «бот» → «бот».
func callStem(word string) string {
	r := []rune(word)
	if len(r) < 4 {
		return word
	}
	if strings.ContainsRune("аяеиыоуёэю", r[len(r)-1]) {
		return string(r[:len(r)-1])
	}
	return word
}

// parseIntent разбирает сообщение.
//
// group = true — сообщение из группы: там нужно обращение к боту
// («бот», «@username» или команда со слэшем), иначе бот молчит.
// Возвращает намерение и признак того, что обращались именно к нему.
func parseIntent(text string, group bool) (string, bool) {
	words := splitWords(text)
	if len(words) == 0 {
		return intentNone, false
	}

	called := !group // в личке звать не нужно
	slash := strings.HasPrefix(strings.TrimSpace(text), "/")
	if slash {
		called = true
	}
	username := botUsername()
	for _, w := range words {
		if isCallWord(w) {
			called = true
			continue
		}
		if username != "" && (w == "@"+username || w == username) {
			called = true
		}
	}
	if !called {
		return intentNone, false
	}

	// Порядок слов не важен: идём по намерениям от самого «сильного»
	// (отмена) к самому общему (меню) и берём первое совпадение.
	for _, group := range intentWords {
		for _, w := range words {
			for _, stem := range group.stems {
				if strings.HasPrefix(w, stem) {
					return group.intent, true
				}
			}
		}
	}
	// Позвали, но без понятного слова — покажем меню.
	return intentMenu, true
}

// splitWords режет сообщение на слова в нижнем регистре, оставляя буквы,
// цифры и «@» — знаки препинания в обращении мешать не должны.
func splitWords(text string) []string {
	var words []string
	var b strings.Builder
	flush := func() {
		if b.Len() > 0 {
			words = append(words, b.String())
			b.Reset()
		}
	}
	for _, r := range strings.ToLower(text) {
		switch {
		case r == '@':
			flush()
			b.WriteRune(r)
		case isWordRune(r):
			b.WriteRune(r)
		default:
			flush()
		}
	}
	flush()
	return words
}

func isWordRune(r rune) bool {
	return r >= 'а' && r <= 'я' || r == 'ё' ||
		r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '_'
}
