package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

const (
	pbkdfIter    = 120_000
	pbkdfKeyLen  = 32
	sessionDays  = 60
	cookieClient = "mshop_session"
	cookieAdmin  = "mshop_admin"
	cookieCart   = "mshop_cart"
)

// ------------------------------------------------------------ пароли

// minPasswordLen — минимальная длина пароля покупателя, та же, что и при
// регистрации на сайте.
const minPasswordLen = 5

// errWeakPassword — пароль короче минимальной длины.
var errWeakPassword = errors.New("короткий пароль")

// setClientPassword задаёт покупателю новый пароль вместо забытого.
//
// Почты и СМС у магазина нет, поэтому «восстановление» тут человеческое:
// покупатель звонит, продавец задаёт новый пароль (из приложения или из
// админки) и диктует его. Пустой пароль означает «придумай сам» — вернётся
// сгенерированный, его и называют покупателю.
//
// Старые сессии закрываем: если паролем интересовался кто-то чужой, он
// вылетит из кабинета сразу, а не доживёт до конца своей cookie.
func setClientPassword(id int64, pw string) (string, error) {
	pw = strings.TrimSpace(pw)
	if pw == "" {
		pw = randomPassword()
	}
	if len([]rune(pw)) < minPasswordLen {
		return "", errWeakPassword
	}
	hash, err := hashPassword(pw)
	if err != nil {
		return "", err
	}
	if err := updateClientPassword(id, hash); err != nil {
		return "", err
	}
	_, err = db.Exec(`DELETE FROM sessions WHERE kind='client' AND subject_id=?`, id)
	return pw, err
}

// randomPassword — пароль, который не стыдно продиктовать по телефону:
// без похожих друг на друга символов (0 и O, 1 и l), только строчные буквы
// и цифры.
func randomPassword() string {
	const alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		// Случайности не досталось — пусть лучше пароль не задастся вовсе,
		// чем задастся предсказуемый.
		return ""
	}
	out := make([]byte, len(b))
	for i, v := range b {
		out[i] = alphabet[int(v)%len(alphabet)]
	}
	return string(out)
}

// hashPassword возвращает строку вида pbkdf2$sha256$iter$salt$hash.
func hashPassword(pw string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, pw, salt, pbkdfIter, pbkdfKeyLen)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("pbkdf2$sha256$%d$%s$%s", pbkdfIter,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(key)), nil
}

// checkPassword сравнивает пароль с хешем за постоянное время.
func checkPassword(pw, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 5 || parts[0] != "pbkdf2" || parts[1] != "sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[2])
	if err != nil || iter <= 0 {
		return false
	}
	salt, err := base64.RawStdEncoding.DecodeString(parts[3])
	if err != nil {
		return false
	}
	want, err := base64.RawStdEncoding.DecodeString(parts[4])
	if err != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(got, want) == 1
}

// ------------------------------------------------------------ телефон

// normalizePhone приводит любой ввод к виду +7XXXXXXXXXX.
// «8 (999) 123-45-67», «+7 999 1234567», «9991234567» — всё это один и тот же номер,
// иначе один человек зарегистрируется несколько раз.
func normalizePhone(s string) string {
	digits := make([]rune, 0, 16)
	for _, r := range s {
		if r >= '0' && r <= '9' {
			digits = append(digits, r)
		}
	}
	d := string(digits)
	switch {
	case len(d) == 11 && (d[0] == '8' || d[0] == '7'):
		return "+7" + d[1:]
	case len(d) == 10:
		return "+7" + d
	case len(d) > 0:
		return "+" + d
	}
	return ""
}

// validPhone — принимаем только российский формат из 11 цифр.
func validPhone(p string) bool {
	return len(p) == 12 && strings.HasPrefix(p, "+7")
}

// formatPhone — «+7 (999) 123-45-67» для показа.
func formatPhone(p string) string {
	if !validPhone(p) {
		return p
	}
	d := p[2:]
	return fmt.Sprintf("+7 (%s) %s-%s-%s", d[0:3], d[3:6], d[6:8], d[8:10])
}

// ------------------------------------------------------------ сессии

func newToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// createSession выдаёт токен и кладёт его в БД.
func createSession(kind string, subjectID int64) (string, error) {
	tok, err := newToken()
	if err != nil {
		return "", err
	}
	exp := time.Now().Add(sessionDays * 24 * time.Hour).UnixMilli()
	_, err = db.Exec(`INSERT INTO sessions (token,kind,subject_id,created_at,expires_at)
      VALUES (?,?,?,?,?)`, tok, kind, subjectID, nowMs(), exp)
	return tok, err
}

var errNoSession = errors.New("нет сессии")

func lookupSession(token, kind string) (int64, error) {
	if token == "" {
		return 0, errNoSession
	}
	var id int64
	var exp int64
	err := db.QueryRow(`SELECT subject_id, expires_at FROM sessions
      WHERE token = ? AND kind = ?`, token, kind).Scan(&id, &exp)
	if err == sql.ErrNoRows {
		return 0, errNoSession
	}
	if err != nil {
		return 0, err
	}
	if exp < nowMs() {
		db.Exec(`DELETE FROM sessions WHERE token = ?`, token)
		return 0, errNoSession
	}
	return id, nil
}

func destroySession(token string) {
	if token != "" {
		db.Exec(`DELETE FROM sessions WHERE token = ?`, token)
	}
}

// purgeExpiredSessions вызывается по таймеру, чтобы таблица не росла вечно.
func purgeExpiredSessions() {
	db.Exec(`DELETE FROM sessions WHERE expires_at < ?`, nowMs())
}

// ------------------------------------------------------------ cookie

func setCookie(w http.ResponseWriter, name, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     name,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   secureCookies,
		SameSite: http.SameSiteLaxMode,
	})
}

func clearCookie(w http.ResponseWriter, name string) {
	setCookie(w, name, "", -1)
}

func cookieValue(r *http.Request, name string) string {
	c, err := r.Cookie(name)
	if err != nil {
		return ""
	}
	return c.Value
}

// currentClient возвращает залогиненного покупателя или ok=false.
func currentClient(r *http.Request) (Client, bool) {
	id, err := lookupSession(cookieValue(r, cookieClient), "client")
	if err != nil {
		return Client{}, false
	}
	c, err := getClient(id)
	if err != nil {
		return Client{}, false
	}
	return c, true
}

// currentAdmin возвращает залогиненного сотрудника или ok=false.
func currentAdmin(r *http.Request) (Admin, bool) {
	id, err := lookupSession(cookieValue(r, cookieAdmin), "admin")
	if err != nil {
		return Admin{}, false
	}
	var a Admin
	err = db.QueryRow(`SELECT id,login,password_hash,name,created_at FROM admins
      WHERE id = ?`, id).Scan(&a.ID, &a.Login, &a.PasswordHash, &a.Name, &a.CreatedAt)
	if err != nil {
		return Admin{}, false
	}
	return a, true
}

// ensureAdmin создаёт учётку администратора при первом запуске.
func ensureAdmin(login, password string) error {
	var n int
	db.QueryRow(`SELECT COUNT(*) FROM admins`).Scan(&n)
	if n > 0 {
		return nil
	}
	h, err := hashPassword(password)
	if err != nil {
		return err
	}
	_, err = db.Exec(`INSERT INTO admins (login,password_hash,name,created_at)
      VALUES (?,?,?,?)`, login, h, "Администратор", nowMs())
	return err
}
