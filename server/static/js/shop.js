/* Мелкие удобства витрины. Сайт полностью работает и без этого файла —
   все действия идут обычными формами. */

(function () {
  "use strict";

  // ---------------------------------------------------------- количество

  // Люди пишут количество и через запятую, и через точку.
  function num(v) {
    var n = parseFloat(String(v).replace(",", ".").replace(/\s/g, ""));
    return isNaN(n) ? 0 : n;
  }

  // Печатаем как на сервере: 1,5 · 2 · 0,25 — без хвостовых нулей.
  function showQty(v) {
    var s = (Math.round(v * 1000) / 1000).toFixed(3)
      .replace(/0+$/, "").replace(/\.$/, "");
    return s.replace(".", ",");
  }

  // «1 250,50 ₽» — теми же правилами, что и money() в Go, иначе пересчитанная
  // на лету сумма выглядела бы иначе, чем присланная сервером.
  function showMoney(v) {
    var neg = v < 0;
    if (neg) v = -v;
    var whole = Math.floor(v + 1e-9);
    var cents = Math.round((v - whole) * 100);
    if (cents === 100) { whole++; cents = 0; }
    var s = String(whole).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
    if (cents > 0) s += "," + (cents < 10 ? "0" : "") + cents;
    return (neg ? "−" : "") + s + " ₽";
  }

  // Покупатель заказывает целыми: и штуки, и килограммы. Дробное количество
  // проставляет продавец, когда человек по телефону просит взять поменьше.
  function wholeQty(v, min) {
    var n = Math.round(num(v));
    if (!(n >= min)) n = min;
    return n;
  }

  // Кнопки «−» и «+» рядом с полем количества. Шаг и минимум берём из разметки.
  document.addEventListener("click", function (e) {
    var btn = e.target.closest("[data-qty] button[data-step]");
    if (!btn) return;
    var input = btn.parentNode.querySelector("input");
    if (!input) return;

    var step = num(btn.dataset.step);
    var min = num(input.dataset.min);
    input.value = wholeQty(num(input.value) + step, min);
    input.dispatchEvent(new Event("change", { bubbles: true }));
  });

  // Число вписали руками: браузер в поле type=number пропускает и «1.5»,
  // и пустоту — округляем, как это сделает сервер, чтобы человек сразу видел,
  // сколько он на самом деле заказывает.
  document.addEventListener("change", function (e) {
    var input = e.target.closest("[data-qty] input");
    if (!input) return;
    var fixed = String(wholeQty(input.value, num(input.dataset.min)));
    if (fixed === input.value) return;
    input.value = fixed;
    // Итог в корзине считается по полям — пересчитаем его по исправленному
    // числу, иначе на экране осталась бы сумма за «1,5».
    input.dispatchEvent(new Event("input", { bubbles: true }));
  });

  // ------------------------------------------------------------- корзина

  var cartForm = document.querySelector("form[data-cart]");
  if (cartForm) {
    // Считаем строки и итог прямо на странице — видно сразу, ещё до отправки.
    function recalc() {
      var total = 0;
      cartForm.querySelectorAll("[data-line]").forEach(function (line) {
        var input = line.querySelector("[data-qty] input");
        var out = line.querySelector("[data-line-sum]");
        var sum = num(input ? input.value : 0) * num(line.dataset.price);
        total += sum;
        if (out) out.textContent = showMoney(sum);
      });
      var totalOut = document.querySelector("[data-cart-total]");
      if (totalOut) totalOut.textContent = showMoney(total);
    }
    cartForm.addEventListener("input", recalc);
    cartForm.addEventListener("change", recalc);
    recalc();
  }

  // --------------------------------------------- «В корзину» без перезагрузки

  // Корзина живёт в cookie: сервер её читает, дописывает и отдаёт обратно.
  // Поэтому запросы «в корзину» отправляем строго по одному — иначе два
  // быстрых нажатия подряд прочитали бы одну и ту же корзину, и второй ответ
  // затёр бы первый товар.
  var queue = Promise.resolve();

  // Набор заказа подряд: нажал на карточке — товар ушёл в корзину, страница
  // осталась на месте. Если что-то пошло не так, отправляем форму как обычно.
  document.addEventListener("submit", function (e) {
    var form = e.target.closest("form[data-add]");
    if (!form || !window.fetch) return;
    e.preventDefault();

    var btn = form.querySelector("button[type=submit]");
    var was = btn ? btn.textContent : "";
    if (btn) btn.disabled = true;

    queue = queue
      .then(function () {
        return fetch(form.action, {
          method: "POST",
          body: new FormData(form),
          credentials: "same-origin",
          headers: { "Accept": "application/json" }
        });
      })
      .then(function (res) { return res.ok ? res.json() : Promise.reject(res.status); })
      .then(function (data) {
        if (!data || !data.ok) return Promise.reject("отказ");
        setCount(data.count);
        setInCart(data.id, data.qty);
        toast("«" + data.title + "» в корзине — " + showQty(data.qty) + " " + data.unit);
        if (btn) {
          btn.textContent = "Добавлено ✓";
          setTimeout(function () { btn.textContent = was; btn.disabled = false; }, 1200);
        }
      })
      .catch(function () {
        if (btn) { btn.textContent = was; btn.disabled = false; }
        form.submit(); // без сети и без JSON работает обычная отправка формы
      });
  });

  function setCount(n) {
    var el = document.querySelector("[data-cart-count]");
    if (!el) return;
    el.textContent = n;
    el.hidden = !n;
  }

  // Подпись «В корзине 1,5 кг» под кнопкой — та же карточка может быть
  // на странице дважды (каталог и блок «похожие»), поэтому обновляем все.
  function setInCart(id, qty) {
    document.querySelectorAll('[data-incart][data-for="' + id + '"]').forEach(function (el) {
      var out = el.querySelector("[data-incart-qty]");
      if (out) out.textContent = showQty(qty);
      el.hidden = !qty;
    });
  }

  var toastTimer = null;
  function toast(text) {
    var el = document.querySelector("[data-toast]");
    if (!el) return;
    el.textContent = text;
    el.hidden = false;
    // Класс включает появление; сбрасываем таймер, если жмут кнопки подряд.
    requestAnimationFrame(function () { el.classList.add("on"); });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      el.classList.remove("on");
      setTimeout(function () { el.hidden = true; }, 300);
    }, 2200);
  }

  // ------------------------------------------------------------- телефон

  // Маска телефона: показываем +7 (999) 123-45-67, на сервер уходит как есть —
  // там номер всё равно приводится к единому виду.
  function maskPhone(value) {
    var d = value.replace(/\D/g, "");
    if (d.startsWith("8")) d = "7" + d.slice(1);
    if (!d.startsWith("7")) d = "7" + d;
    d = d.slice(0, 11);

    var out = "+7";
    if (d.length > 1) out += " (" + d.slice(1, 4);
    if (d.length >= 4) out += ")";
    if (d.length > 4) out += " " + d.slice(4, 7);
    if (d.length > 7) out += "-" + d.slice(7, 9);
    if (d.length > 9) out += "-" + d.slice(9, 11);
    return out;
  }

  document.querySelectorAll("input[data-phone]").forEach(function (input) {
    function apply() {
      if (input.value.replace(/\D/g, "") === "") return;
      input.value = maskPhone(input.value);
    }
    input.addEventListener("input", apply);
    input.addEventListener("focus", function () {
      if (!input.value) input.value = "+7 (";
    });
    apply();
  });

  // При самовывозе адрес не нужен — прячем поле, чтобы не путать.
  var deliveryRadios = document.querySelectorAll("input[data-delivery]");
  var addressField = document.querySelector("[data-address-field]");
  if (deliveryRadios.length && addressField) {
    function syncAddress() {
      var pickup = document.querySelector("input[data-delivery]:checked");
      var isPickup = pickup && pickup.value === "Самовывоз";
      addressField.style.display = isPickup ? "none" : "";
      var input = addressField.querySelector("input");
      if (input) input.required = !isPickup;
    }
    deliveryRadios.forEach(function (r) { r.addEventListener("change", syncAddress); });
    syncAddress();
  }
})();
