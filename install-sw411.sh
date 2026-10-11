#!/bin/bash
set -euo pipefail

echo "== SW411 Control installer =="

if [ "$(id -u)" -ne 0 ]; then
  echo "Запусти от root."
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y python3-flask python3-serial nginx

if ! getent passwd sw411 >/dev/null; then
  useradd --system --no-create-home --home-dir /opt/sw411 --shell /usr/sbin/nologin sw411
fi
usermod -aG dialout sw411

install -d -m 0755 /opt/sw411/templates

cat > /opt/sw411/app.py <<'PYAPP'
#!/usr/bin/env python3
import os
import re
import time
import threading
from collections import deque
from datetime import datetime

import serial
from flask import Flask, jsonify, request, render_template

PORT = os.environ.get("SW411_PORT", "/dev/sw411")
BAUD = int(os.environ.get("SW411_BAUD", "115200"))

app = Flask(__name__)

_log = deque(maxlen=100)
_log_lock = threading.Lock()
_status_lock = threading.Lock()
_status_cache = {"at": 0.0, "data": None}


def add_log(message, level="info"):
    with _log_lock:
        _log.appendleft({
            "time": datetime.now().strftime("%H:%M:%S"),
            "message": message,
            "level": level,
        })


class SW411Serial:
    def __init__(self, port, baud):
        self.port = port
        self.baud = baud
        self.lock = threading.RLock()
        self.ser = None

    def _ensure_open(self):
        if self.ser and self.ser.is_open:
            return
        self.ser = serial.Serial(
            port=self.port,
            baudrate=self.baud,
            bytesize=serial.EIGHTBITS,
            parity=serial.PARITY_NONE,
            stopbits=serial.STOPBITS_ONE,
            timeout=0.05,
            write_timeout=0.5,
            exclusive=True,
        )

    def close(self):
        with self.lock:
            if self.ser:
                try:
                    self.ser.close()
                except Exception:
                    pass
            self.ser = None

    def command(self, cmd, timeout=0.35, expect_reply=True):
        with self.lock:
            try:
                self._ensure_open()
                self.ser.reset_input_buffer()
                self.ser.write(cmd.encode("ascii"))
                self.ser.flush()

                if not expect_reply:
                    return ""

                deadline = time.monotonic() + timeout
                last_rx = None
                buf = bytearray()

                while time.monotonic() < deadline:
                    chunk = self.ser.read(256)
                    if chunk:
                        buf.extend(chunk)
                        last_rx = time.monotonic()
                    elif buf and last_rx and (time.monotonic() - last_rx) >= 0.035:
                        break

                return bytes(buf).decode("utf-8", errors="replace").strip("\x00\r\n \t")
            except Exception:
                self.close()
                raise


sw = SW411Serial(PORT, BAUD)


def _version(raw):
    m = re.search(r"\bv?\d+\.\d+\.\d+\b", raw or "", re.I)
    return ("v" + m.group(0).lstrip("vV")) if m else None


def _input(raw):
    if not raw:
        return None
    for pat in (
        r"(?:source|input|in)\D*([1-4])\b",
        r"\b([1-4])\b",
    ):
        m = re.search(pat, raw, re.I)
        if m:
            return int(m.group(1))
    return None


def _bool(raw):
    if not raw:
        return None
    s = raw.lower()
    if re.search(r"\b(on|enable|enabled|open|true)\b", s):
        return True
    if re.search(r"\b(off|disable|disabled|close|closed|false)\b", s):
        return False
    nums = re.findall(r"\b([01])\b", s)
    if nums:
        return nums[-1] == "1"
    return None


def _temperature(raw):
    if not raw:
        return None
    m = re.search(r"(-?\d+(?:\.\d+)?)\s*(?:°?\s*c)?", raw, re.I)
    if not m:
        return None
    try:
        value = float(m.group(1))
        return int(value) if value.is_integer() else value
    except ValueError:
        return None


def invalidate_status():
    with _status_lock:
        _status_cache["at"] = 0.0


def read_status(force=False):
    with _status_lock:
        now = time.monotonic()
        if not force and _status_cache["data"] and now - _status_cache["at"] < 2.0:
            return _status_cache["data"]

        data = {
            "connected": False,
            "port": PORT,
            "baud": BAUD,
            "firmware": None,
            "input": None,
            "power": None,
            "auto_switch": None,
            "temperature": None,
            "error": None,
            "raw": {},
        }

        try:
            raw_ver = sw.command("r fw version!")
            data["raw"]["version"] = raw_ver
            data["firmware"] = _version(raw_ver)
            data["connected"] = True

            for key, cmd in (
                ("input", "r in source!"),
                ("power", "r power!"),
                ("auto_switch", "r auto switch!"),
                ("temperature", "r temperature!"),
            ):
                try:
                    data["raw"][key] = sw.command(cmd)
                except Exception as e:
                    data["raw"][key] = f"ERROR: {e}"

            data["input"] = _input(data["raw"].get("input"))
            data["power"] = _bool(data["raw"].get("power"))
            data["auto_switch"] = _bool(data["raw"].get("auto_switch"))
            data["temperature"] = _temperature(data["raw"].get("temperature"))
        except Exception as e:
            data["error"] = str(e)
            data["connected"] = False

        _status_cache["at"] = now
        _status_cache["data"] = data
        return data


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/status")
def api_status():
    force = request.args.get("force") == "1"
    return jsonify(read_status(force=force))


@app.get("/api/logs")
def api_logs():
    with _log_lock:
        return jsonify(list(_log))


@app.post("/api/input/<int:source>")
def api_input(source):
    if source not in (1, 2, 3, 4):
        return jsonify(ok=False, error="Недопустимый HDMI-вход"), 400
    try:
        sw.command(f"s in source {source}!", timeout=0.12, expect_reply=False)
        invalidate_status()
        add_log(f"Переключение на HDMI {source}")
        return jsonify(ok=True, input=source)
    except Exception as e:
        add_log(f"Ошибка переключения HDMI {source}: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/auto")
def api_auto():
    payload = request.get_json(silent=True) or {}
    enabled = payload.get("enabled")
    if not isinstance(enabled, bool):
        return jsonify(ok=False, error="Поле enabled должно быть true/false"), 400
    try:
        sw.command(f"s auto switch {1 if enabled else 0}!", timeout=0.12, expect_reply=False)
        invalidate_status()
        add_log("Автопереключение включено" if enabled else "Автопереключение выключено")
        return jsonify(ok=True, auto_switch=enabled)
    except Exception as e:
        add_log(f"Ошибка режима автопереключения: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/power/toggle")
def api_power_toggle():
    try:
        sw.command("power z!", timeout=0.12, expect_reply=False)
        invalidate_status()
        add_log("Команда питания отправлена")
        return jsonify(ok=True)
    except Exception as e:
        add_log(f"Ошибка питания: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/reboot")
def api_reboot():
    try:
        sw.command("reboot!", timeout=0.2, expect_reply=False)
        sw.close()
        invalidate_status()
        add_log("SW411 перезагружается", "warn")
        return jsonify(ok=True)
    except Exception as e:
        add_log(f"Ошибка перезагрузки: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.get("/health")
def health():
    return jsonify(ok=True)


@app.after_request
def headers(response):
    response.headers["Cache-Control"] = "no-store"
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "SAMEORIGIN"
    return response


add_log("SW411 Control запущен")

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8787, threaded=True)

PYAPP
chmod 0755 /opt/sw411/app.py

cat > /opt/sw411/templates/index.html <<'HTML'
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#081018">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <title>SW411 Control</title>
  <style>
    :root{
      --bg:#071019;
      --bg2:#0b1621;
      --card:rgba(20,31,43,.78);
      --card2:rgba(27,40,54,.86);
      --line:rgba(167,190,214,.17);
      --text:#f4f7fb;
      --muted:#8fa0b5;
      --cyan:#22d3ee;
      --cyan2:#06b6d4;
      --green:#22e39a;
      --amber:#f8c45c;
      --red:#ff6b76;
      --shadow:0 18px 42px rgba(0,0,0,.30);
      --radius:22px;
    }
    *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
    html{background:var(--bg)}
    body{
      margin:0;
      color:var(--text);
      font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;
      background:
        radial-gradient(800px 520px at 100% -10%,rgba(34,211,238,.13),transparent 60%),
        radial-gradient(700px 500px at -20% 10%,rgba(74,105,255,.10),transparent 60%),
        linear-gradient(180deg,#09131d 0%,#061019 70%,#050c13 100%);
      min-height:100svh;
    }
    button{font:inherit}
    .app{
      width:min(100%,650px);
      margin:0 auto;
      padding:calc(18px + env(safe-area-inset-top)) 16px calc(28px + env(safe-area-inset-bottom));
    }
    .header{
      display:flex;align-items:center;gap:13px;margin:4px 2px 20px;
    }
    .device-icon,.round-icon{
      display:grid;place-items:center;flex:0 0 auto;
      width:52px;height:52px;border-radius:17px;
      background:linear-gradient(145deg,rgba(41,57,72,.95),rgba(18,29,40,.95));
      border:1px solid rgba(255,255,255,.05);
      box-shadow:inset 0 1px rgba(255,255,255,.05),0 12px 28px rgba(0,0,0,.2);
    }
    .device-icon svg{width:28px;height:28px}
    .headcopy{min-width:0;flex:1}
    h1{font-size:clamp(26px,7vw,36px);line-height:1.02;margin:0 0 5px;letter-spacing:-.03em}
    .subtitle{color:var(--muted);font-size:14px}
    .status-card{
      position:relative;overflow:hidden;
      background:linear-gradient(145deg,rgba(25,38,51,.93),rgba(13,24,34,.90));
      border:1px solid var(--line);border-radius:var(--radius);
      padding:20px;box-shadow:var(--shadow);
    }
    .status-card:after{
      content:"";position:absolute;width:210px;height:210px;border-radius:50%;
      right:-100px;top:-120px;background:rgba(34,211,238,.07);filter:blur(4px)
    }
    .connection{display:flex;align-items:center;gap:13px;position:relative;z-index:1}
    .dot{
      width:15px;height:15px;border-radius:50%;background:var(--red);
      box-shadow:0 0 0 8px rgba(255,107,118,.11),0 0 18px rgba(255,107,118,.45)
    }
    .dot.online{background:var(--green);box-shadow:0 0 0 8px rgba(34,227,154,.11),0 0 18px rgba(34,227,154,.45)}
    .conn-title{font-size:20px;font-weight:800}
    .conn-title.online{color:var(--green)}
    .conn-sub{margin-top:2px;color:var(--muted);font-size:13px}
    .metrics{
      display:grid;grid-template-columns:repeat(2,1fr);gap:1px;
      background:var(--line);border-radius:16px;overflow:hidden;margin-top:18px
    }
    .metric{background:rgba(8,17,26,.66);padding:13px 14px;min-width:0}
    .metric span{display:block;color:var(--muted);font-size:11px;margin-bottom:4px}
    .metric strong{display:block;font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    .section{margin-top:25px}
    .section-title{
      display:flex;align-items:center;justify-content:space-between;
      margin:0 2px 12px;
    }
    .section-title h2{font-size:20px;letter-spacing:-.02em;margin:0}
    .section-title small{color:#66b6ff;font-size:12px}
    .actions{display:grid;grid-template-columns:repeat(3,1fr);gap:10px}
    .action{
      min-height:90px;border:1px solid var(--line);border-radius:18px;
      color:var(--text);background:linear-gradient(145deg,rgba(27,41,55,.95),rgba(15,26,36,.95));
      box-shadow:inset 0 1px rgba(255,255,255,.04),0 10px 24px rgba(0,0,0,.16);
      display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;
      padding:11px 7px;cursor:pointer;transition:.16s transform,.16s border-color,.16s background;
    }
    .action:active,.input-btn:active{transform:scale(.97)}
    .action svg{width:25px;height:25px;color:#9fb7d3}
    .action span{font-size:12px;font-weight:700;text-align:center}
    .action.primary{border-color:rgba(34,211,238,.48);background:linear-gradient(145deg,rgba(13,74,85,.75),rgba(13,44,54,.75))}
    .action.primary svg,.action.primary span{color:#5ee7f8}
    .action.danger{border-color:rgba(255,107,118,.28)}
    .inputs{display:grid;grid-template-columns:repeat(4,1fr);gap:9px}
    .input-btn{
      position:relative;min-height:93px;border-radius:18px;border:1px solid var(--line);
      background:linear-gradient(145deg,rgba(27,41,55,.95),rgba(14,24,34,.95));
      color:#aab7c8;font-weight:800;cursor:pointer;transition:.16s;
      display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px
    }
    .input-btn svg{width:28px;height:28px}
    .input-btn .tiny-dot{width:7px;height:7px;border-radius:50%;background:#58697c}
    .input-btn.active{
      color:#eaffff;border-color:var(--cyan);
      background:radial-gradient(circle at 50% 15%,rgba(34,211,238,.28),transparent 58%),rgba(9,51,61,.78);
      box-shadow:0 0 0 1px rgba(34,211,238,.23),0 0 24px rgba(34,211,238,.25),inset 0 0 24px rgba(34,211,238,.10)
    }
    .input-btn.active .tiny-dot{background:var(--cyan);box-shadow:0 0 10px var(--cyan)}
    .panel{
      background:linear-gradient(145deg,rgba(25,38,51,.93),rgba(13,24,34,.90));
      border:1px solid var(--line);border-radius:var(--radius);overflow:hidden;box-shadow:var(--shadow)
    }
    .mode-row{
      display:flex;align-items:center;gap:14px;padding:17px 16px;border-bottom:1px solid var(--line)
    }
    .mode-row:last-child{border-bottom:0}
    .mode-icon{
      width:34px;height:34px;display:grid;place-items:center;color:#a9b9cc;flex:0 0 auto
    }
    .mode-icon svg{width:27px;height:27px}
    .mode-copy{flex:1;min-width:0}
    .mode-copy strong{display:block;font-size:16px}
    .mode-copy small{display:block;color:var(--muted);margin-top:3px;font-size:12px}
    .switch{
      width:52px;height:30px;border:0;border-radius:99px;padding:3px;background:#263444;cursor:pointer;
      transition:.2s;box-shadow:inset 0 0 0 1px rgba(255,255,255,.03)
    }
    .switch i{
      display:block;width:24px;height:24px;border-radius:50%;background:#eef4fa;
      box-shadow:0 2px 8px rgba(0,0,0,.34);transition:.2s
    }
    .switch.on{background:linear-gradient(90deg,var(--cyan2),var(--cyan));box-shadow:0 0 18px rgba(34,211,238,.24)}
    .switch.on i{transform:translateX(22px)}
    .badge{
      padding:6px 10px;border-radius:999px;background:#1b2835;color:#91a3b8;font-weight:700;font-size:11px
    }
    .logs{padding:4px 14px}
    .log{
      display:grid;grid-template-columns:10px 62px 1fr;align-items:start;gap:10px;
      padding:12px 0;border-bottom:1px solid var(--line);font-size:12px
    }
    .log:last-child{border-bottom:0}
    .log-dot{width:7px;height:7px;border-radius:50%;background:#7f91a7;margin-top:5px}
    .log-dot.info{background:var(--cyan)}
    .log-dot.warn{background:var(--amber)}
    .log-dot.error{background:var(--red)}
    .log-time{color:#74869b;font-variant-numeric:tabular-nums}
    .log-message{color:#c8d2df;line-height:1.38}
    .toast-wrap{
      position:fixed;left:50%;bottom:calc(18px + env(safe-area-inset-bottom));transform:translateX(-50%);
      width:min(calc(100% - 32px),610px);z-index:20;pointer-events:none
    }
    .toast{
      display:none;background:rgba(18,29,40,.96);border:1px solid var(--line);border-radius:16px;
      padding:13px 15px;box-shadow:0 18px 50px rgba(0,0,0,.4);font-size:13px
    }
    .toast.show{display:block;animation:pop .18s ease-out}
    .toast.error{border-color:rgba(255,107,118,.45)}
    @keyframes pop{from{transform:translateY(8px);opacity:0}to{transform:none;opacity:1}}
    .busy{opacity:.62;pointer-events:none}
    @media(min-width:560px){
      .app{padding-left:24px;padding-right:24px}
      .metrics{grid-template-columns:repeat(4,1fr)}
      .metric{padding:15px}
      .action span{font-size:13px}
    }
    @media(max-width:360px){
      .inputs{grid-template-columns:repeat(2,1fr)}
      .actions{gap:7px}
      .action{min-height:84px}
    }
    @media(prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
  </style>
</head>
<body>
<main class="app" id="app">
  <header class="header">
    <div class="device-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="7" width="18" height="10" rx="2"/><path d="M7 12h.01M17 12h.01"/></svg>
    </div>
    <div class="headcopy">
      <h1>SW411 Control</h1>
      <div class="subtitle">Управление HDMI переключателем</div>
    </div>
  </header>

  <section class="status-card">
    <div class="connection">
      <div class="dot" id="connDot"></div>
      <div>
        <div class="conn-title" id="connTitle">Подключение…</div>
        <div class="conn-sub" id="connSub">Проверяем /dev/sw411</div>
      </div>
    </div>
    <div class="metrics">
      <div class="metric"><span>Порт</span><strong id="port">/dev/sw411</strong></div>
      <div class="metric"><span>Прошивка</span><strong id="fw">—</strong></div>
      <div class="metric"><span>Активный вход</span><strong id="activeInput">—</strong></div>
      <div class="metric"><span>Температура</span><strong id="temp">—</strong></div>
    </div>
  </section>

  <section class="section">
    <div class="section-title"><h2>Быстрые действия</h2></div>
    <div class="actions">
      <button class="action primary" id="powerBtn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v10"/><path d="M6.4 5.6a8 8 0 1 0 11.2 0"/></svg>
        <span id="powerText">Питание</span>
      </button>
      <button class="action danger" id="rebootBtn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 11a8.1 8.1 0 1 0-2.4 5.7"/><path d="M20 4v7h-7"/></svg>
        <span>Перезагрузить</span>
      </button>
      <button class="action" id="refreshBtn">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a9 9 0 0 1-15.4 6.4L3 16"/><path d="M3 21v-5h5"/><path d="M3 12A9 9 0 0 1 18.4 5.6L21 8"/><path d="M21 3v5h-5"/></svg>
        <span>Обновить</span>
      </button>
    </div>
  </section>

  <section class="section">
    <div class="section-title"><h2>Входы</h2><small>Выберите источник</small></div>
    <div class="inputs">
      <button class="input-btn" data-input="1">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span>HDMI 1</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="2">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span>HDMI 2</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="3">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span>HDMI 3</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="4">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span>HDMI 4</span><i class="tiny-dot"></i>
      </button>
    </div>
  </section>

  <section class="section">
    <div class="section-title"><h2>Режим</h2></div>
    <div class="panel">
      <div class="mode-row">
        <div class="mode-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M20 7h-7l2.5-2.5"/><path d="M20 7 17.5 9.5"/><path d="M4 17h7l-2.5 2.5"/><path d="M4 17l2.5-2.5"/><path d="M6 7a7 7 0 0 1 11.6-1.8M18 17A7 7 0 0 1 6.4 18.8"/></svg>
        </div>
        <div class="mode-copy">
          <strong>Автопереключение</strong>
          <small>SW411 автоматически выбирает активный источник</small>
        </div>
        <button class="switch" id="autoSwitch" aria-label="Автопереключение"><i></i></button>
      </div>
      <div class="mode-row">
        <div class="mode-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 12h14"/><path d="m9 8-4 4 4 4"/></svg>
        </div>
        <div class="mode-copy">
          <strong>Ручной режим</strong>
          <small>Активен, когда автопереключение выключено</small>
        </div>
        <span class="badge" id="manualBadge">—</span>
      </div>
    </div>
  </section>

  <section class="section">
    <div class="section-title"><h2>Системный журнал</h2><small id="lastUpdate">—</small></div>
    <div class="panel logs" id="logs">
      <div class="log"><i class="log-dot"></i><span class="log-time">—</span><span class="log-message">Журнал пока пуст</span></div>
    </div>
  </section>
</main>

<div class="toast-wrap"><div class="toast" id="toast"></div></div>

<script>
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
let current = {};
let busy = false;
let toastTimer;
let pendingInput = null;
let pendingInputUntil = 0;

function toast(msg, error=false){
  const t=$("#toast");
  t.textContent=msg;
  t.className="toast show"+(error?" error":"");
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>t.className="toast",2600);
}

function setBusy(v){
  busy=v;
  $("#app").classList.toggle("busy",v);
}

async function api(url, options={}){
  const r=await fetch(url,{cache:"no-store",headers:{"Content-Type":"application/json"},...options});
  const data=await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
  return data;
}

function render(s){
  s = {...(s||{})};

  if(pendingInput !== null){
    if(s.input === pendingInput){
      pendingInput = null;
      pendingInputUntil = 0;
    }else if(Date.now() < pendingInputUntil){
      s.input = pendingInput;
    }else{
      pendingInput = null;
      pendingInputUntil = 0;
    }
  }

  current=s;
  const online=!!s.connected;
  $("#connDot").classList.toggle("online",online);
  $("#connTitle").textContent=online?"Подключено":"Нет связи";
  $("#connTitle").classList.toggle("online",online);
  $("#connSub").textContent=online?"Устройство готово к работе":(s.error||"SW411 не найден");
  $("#port").textContent=s.port||"/dev/sw411";
  $("#fw").textContent=s.firmware||"—";
  $("#activeInput").textContent=s.input?`HDMI ${s.input}`:"—";
  $("#temp").textContent=(s.temperature!==null&&s.temperature!==undefined)?`${s.temperature} °C`:"—";
  $$(".input-btn").forEach(b=>b.classList.toggle("active",Number(b.dataset.input)===s.input));

  const auto=s.auto_switch===true;
  $("#autoSwitch").classList.toggle("on",auto);
  $("#manualBadge").textContent=s.auto_switch===null?"—":(auto?"ВЫКЛ":"ВКЛ");
  $("#powerText").textContent=s.power===true?"Выключить":s.power===false?"Включить":"Питание";
  $("#lastUpdate").textContent=new Date().toLocaleTimeString("ru-RU",{hour:"2-digit",minute:"2-digit",second:"2-digit"});
}

async function refresh(force=false){
  try{
    render(await api("/api/status"+(force?"?force=1":"")));
    await loadLogs();
  }catch(e){
    render({connected:false,error:e.message,port:"/dev/sw411"});
  }
}

async function loadLogs(){
  try{
    const list=await api("/api/logs");
    const el=$("#logs");
    if(!list.length) return;
    el.innerHTML=list.slice(0,8).map(x=>`
      <div class="log">
        <i class="log-dot ${x.level||"info"}"></i>
        <span class="log-time">${esc(x.time||"")}</span>
        <span class="log-message">${esc(x.message||"")}</span>
      </div>`).join("");
  }catch(_){}
}

function esc(s){
  return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
}

$$(".input-btn").forEach(btn=>btn.addEventListener("click",async()=>{
  if(busy)return;
  setBusy(true);
  try{
    const n=Number(btn.dataset.input);
    await api(`/api/input/${n}`,{method:"POST"});
    pendingInput=n;
    pendingInputUntil=Date.now()+2500;
    current.input=n;
    render(current);
    toast(`HDMI ${n} выбран`);
    loadLogs();
    setTimeout(()=>refresh(true),450);
    setTimeout(()=>refresh(true),1100);
  }catch(e){toast(e.message,true)}
  finally{setBusy(false)}
}));

$("#autoSwitch").addEventListener("click",async()=>{
  if(busy)return;
  setBusy(true);
  try{
    const enabled=current.auto_switch!==true;
    await api("/api/auto",{method:"POST",body:JSON.stringify({enabled})});
    current.auto_switch=enabled;
    render(current);
    toast(enabled?"Автопереключение включено":"Ручной режим включён");
    loadLogs();
    setTimeout(()=>refresh(true),350);
  }catch(e){toast(e.message,true)}
  finally{setBusy(false)}
});

$("#powerBtn").addEventListener("click",async()=>{
  if(busy)return;
  setBusy(true);
  try{
    await api("/api/power/toggle",{method:"POST"});
    if(current.power!==null && current.power!==undefined) current.power=!current.power;
    render(current);
    toast("Команда питания выполнена");
    loadLogs();
    setTimeout(()=>refresh(true),500);
  }catch(e){toast(e.message,true)}
  finally{setBusy(false)}
});

$("#rebootBtn").addEventListener("click",async()=>{
  if(busy)return;
  if(!confirm("Перезагрузить SW411?"))return;
  setBusy(true);
  try{
    await api("/api/reboot",{method:"POST"});
    toast("SW411 перезагружается…");
    setTimeout(()=>refresh(true),1800);
  }catch(e){toast(e.message,true)}
  finally{setTimeout(()=>setBusy(false),900)}
});

$("#refreshBtn").addEventListener("click",async()=>{
  if(busy)return;
  setBusy(true);
  await refresh(true);
  setBusy(false);
  toast("Статус обновлён");
});

refresh(true);
setInterval(()=>{if(!busy && document.visibilityState==="visible")refresh(false)},2000);
</script>
</body>
</html>

HTML
chmod 0644 /opt/sw411/templates/index.html

cat > /etc/udev/rules.d/99-sw411.rules <<'UDEV'
SUBSYSTEM=="tty", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="7523", SYMLINK+="sw411", GROUP="dialout", MODE="0660"
UDEV
udevadm control --reload-rules
udevadm trigger || true

cat > /etc/systemd/system/sw411-control.service <<'UNIT'
[Unit]
Description=SW411 HDMI Switch Control
After=network.target

[Service]
Type=simple
User=sw411
Group=sw411
SupplementaryGroups=dialout
WorkingDirectory=/opt/sw411
Environment=PYTHONUNBUFFERED=1
Environment=SW411_PORT=/dev/sw411
Environment=SW411_BAUD=115200
ExecStart=/usr/bin/python3 /opt/sw411/app.py
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/nginx/sites-available/sw411 <<'NGINX'
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    server_name _;

    client_max_body_size 1m;

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_connect_timeout 3s;
        proxy_read_timeout 10s;
    }
}
NGINX

rm -f /etc/nginx/sites-enabled/default
ln -sfn /etc/nginx/sites-available/sw411 /etc/nginx/sites-enabled/sw411

python3 -m py_compile /opt/sw411/app.py
nginx -t

systemctl daemon-reload
systemctl enable --now nginx
nginx -t
systemctl reload nginx
systemctl enable sw411-control.service
systemctl restart sw411-control.service

sleep 1

echo
echo "== Проверка =="
systemctl --no-pager --full status sw411-control.service | sed -n '1,14p' || true
echo
if [ -e /dev/sw411 ]; then
  echo "SW411 serial: /dev/sw411 -> $(readlink -f /dev/sw411)"
else
  echo "ВНИМАНИЕ: /dev/sw411 пока не появился."
  echo "Проверь USB: lsusb | grep 1a86:7523"
fi
echo
IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo "Открывай: http://${IP:-192.168.2.162}/"
echo
echo "Логи сервиса: journalctl -u sw411-control -f"