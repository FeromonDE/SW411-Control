#!/bin/bash
set -euo pipefail

echo "== SW411 Control installer =="

if [ "$(id -u)" -ne 0 ]; then
  echo "Запусти от root."
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y python3-flask python3-serial python3-paho-mqtt nginx curl

if ! getent passwd sw411 >/dev/null; then
  useradd --system --no-create-home --home-dir /opt/sw411 --shell /usr/sbin/nologin sw411
fi
usermod -aG dialout sw411

install -d -m 0755 /opt/sw411/templates
install -d -o sw411 -g sw411 -m 0750 /var/lib/sw411-control

cat > /opt/sw411/app.py.new <<'PYAPP'
#!/usr/bin/env python3
import os
import re
import time
import json
import queue
import secrets
import socket
import threading
from collections import deque
from datetime import datetime

import serial
from flask import Flask, Response, jsonify, request, render_template, stream_with_context

try:
    import paho.mqtt.client as mqtt
except Exception:
    mqtt = None

PORT = os.environ.get("SW411_PORT", "/dev/sw411")
BAUD = int(os.environ.get("SW411_BAUD", "115200"))
APP_VERSION = "0.4.1"
UPDATE_REQUEST = "/run/sw411-control/update.request"
UPDATE_STATUS = "/var/lib/sw411-control/update-status.json"
CONFIG_PATH = os.environ.get("SW411_CONFIG", "/var/lib/sw411-control/config.json")

app = Flask(__name__)

_log = deque(maxlen=100)
_log_lock = threading.Lock()
_status_lock = threading.Lock()
_status_cache = {"at": 0.0, "data": None}
_config_lock = threading.RLock()
_sse_lock = threading.Lock()
_sse_clients = set()
_mqtt_lock = threading.RLock()
_mqtt_client = None
_mqtt_connected = False

DEFAULT_CONFIG = {
    "input_names": ["HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"],
    "api_token": "",
    "mqtt": {
        "enabled": False,
        "host": "",
        "port": 1883,
        "username": "",
        "password": "",
        "base_topic": "sw411",
        "discovery_prefix": "homeassistant"
    }
}


def _merge_config(raw):
    cfg = json.loads(json.dumps(DEFAULT_CONFIG))
    if isinstance(raw, dict):
        if isinstance(raw.get("input_names"), list) and len(raw["input_names"]) == 4:
            cfg["input_names"] = [str(x).strip()[:32] or f"HDMI {i+1}" for i, x in enumerate(raw["input_names"])]
        if isinstance(raw.get("api_token"), str):
            cfg["api_token"] = raw["api_token"]
        if isinstance(raw.get("mqtt"), dict):
            for key in cfg["mqtt"]:
                if key in raw["mqtt"]:
                    cfg["mqtt"][key] = raw["mqtt"][key]
    if not cfg["api_token"]:
        cfg["api_token"] = secrets.token_urlsafe(24)
    cfg["mqtt"]["port"] = int(cfg["mqtt"].get("port") or 1883)
    return cfg


def _save_config_unlocked(cfg):
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, ensure_ascii=False, indent=2)
    os.chmod(tmp, 0o600)
    os.replace(tmp, CONFIG_PATH)


def load_config():
    with _config_lock:
        raw = {}
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                raw = json.load(fh)
        except FileNotFoundError:
            pass
        except Exception as e:
            add_log(f"Ошибка чтения настроек: {e}", "error")
        cfg = _merge_config(raw)
        _save_config_unlocked(cfg)
        return cfg


_config = None


def add_log(message, level="info"):
    with _log_lock:
        _log.appendleft({
            "time": datetime.now().strftime("%H:%M:%S"),
            "message": message,
            "level": level,
        })


_config = load_config()


def config_snapshot(include_secret=False):
    with _config_lock:
        cfg = json.loads(json.dumps(_config))
    if not include_secret:
        cfg["mqtt"]["password"] = ""
    return cfg


def input_names():
    with _config_lock:
        return list(_config["input_names"])


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
            "earc": None,
            "temperature": None,
            "input_names": input_names(),
            "audio_edid_control": False,
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
                ("earc", "r earc!"),
                ("temperature", "r temperature!"),
            ):
                try:
                    data["raw"][key] = sw.command(cmd)
                except Exception as e:
                    data["raw"][key] = f"ERROR: {e}"

            data["input"] = _input(data["raw"].get("input"))
            data["power"] = _bool(data["raw"].get("power"))
            data["auto_switch"] = _bool(data["raw"].get("auto_switch"))
            data["earc"] = _bool(data["raw"].get("earc"))
            data["temperature"] = _temperature(data["raw"].get("temperature"))
            if data["input"] in (1, 2, 3, 4):
                data["input_name"] = data["input_names"][data["input"] - 1]
            else:
                data["input_name"] = None
        except Exception as e:
            data["error"] = str(e)
            data["connected"] = False

        _status_cache["at"] = now
        _status_cache["data"] = data
        return data


def _state_for_publish():
    with _status_lock:
        state = dict(_status_cache.get("data") or {})
    state["input_names"] = input_names()
    if state.get("input") in (1, 2, 3, 4):
        state["input_name"] = state["input_names"][state["input"] - 1]
    return state


def broadcast_state(state=None):
    state = state or _state_for_publish()
    payload = json.dumps(state, ensure_ascii=False, separators=(",", ":"))
    dead = []
    with _sse_lock:
        for q in list(_sse_clients):
            try:
                q.put_nowait(payload)
            except Exception:
                dead.append(q)
        for q in dead:
            _sse_clients.discard(q)
    mqtt_publish_state(state)


def schedule_confirm(delay=0.65):
    def worker():
        time.sleep(delay)
        try:
            state = read_status(force=True)
            broadcast_state(state)
        except Exception:
            pass
    threading.Thread(target=worker, daemon=True).start()


def _device_info(state=None):
    state = state or _state_for_publish()
    return {
        "identifiers": ["sw411-control"],
        "name": "FeinTech SW411",
        "manufacturer": "FeinTech",
        "model": "SW411",
        "sw_version": state.get("firmware") or "unknown"
    }


def mqtt_publish_discovery():
    global _mqtt_client
    with _mqtt_lock:
        client = _mqtt_client
        connected = _mqtt_connected
        cfg = config_snapshot(include_secret=True)
    if not client or not connected or not cfg["mqtt"]["enabled"]:
        return

    m = cfg["mqtt"]
    base = m["base_topic"].rstrip("/")
    disc = m["discovery_prefix"].rstrip("/")
    names = cfg["input_names"]
    dev = _device_info()

    entities = {
        f"{disc}/select/sw411/input/config": {
            "name": "Input",
            "unique_id": "sw411_input",
            "state_topic": f"{base}/status",
            "value_template": "{{ value_json.input_name }}",
            "command_topic": f"{base}/input/set",
            "options": names,
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/switch/sw411/auto_switch/config": {
            "name": "Auto Source",
            "unique_id": "sw411_auto_switch",
            "state_topic": f"{base}/status",
            "value_template": "{{ 'ON' if value_json.auto_switch else 'OFF' }}",
            "command_topic": f"{base}/auto/set",
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/switch/sw411/earc/config": {
            "name": "eARC",
            "unique_id": "sw411_earc",
            "state_topic": f"{base}/status",
            "value_template": "{{ 'ON' if value_json.earc else 'OFF' }}",
            "command_topic": f"{base}/earc/set",
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/switch/sw411/power/config": {
            "name": "Power",
            "unique_id": "sw411_power",
            "state_topic": f"{base}/status",
            "value_template": "{{ 'ON' if value_json.power else 'OFF' }}",
            "command_topic": f"{base}/power/set",
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/sensor/sw411/temperature/config": {
            "name": "Temperature",
            "unique_id": "sw411_temperature",
            "state_topic": f"{base}/status",
            "value_template": "{{ value_json.temperature }}",
            "device_class": "temperature",
            "unit_of_measurement": "°C",
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/sensor/sw411/firmware/config": {
            "name": "Firmware",
            "unique_id": "sw411_firmware",
            "state_topic": f"{base}/status",
            "value_template": "{{ value_json.firmware }}",
            "availability_topic": f"{base}/availability",
            "device": dev
        },
        f"{disc}/button/sw411/reboot/config": {
            "name": "Reboot",
            "unique_id": "sw411_reboot",
            "command_topic": f"{base}/reboot/set",
            "payload_press": "PRESS",
            "device_class": "restart",
            "availability_topic": f"{base}/availability",
            "device": dev
        }
    }

    for topic, payload in entities.items():
        client.publish(topic, json.dumps(payload, ensure_ascii=False), qos=0, retain=True)


def mqtt_publish_state(state=None):
    with _mqtt_lock:
        client = _mqtt_client
        connected = _mqtt_connected
        cfg = config_snapshot(include_secret=True)
    if not client or not connected or not cfg["mqtt"]["enabled"]:
        return
    base = cfg["mqtt"]["base_topic"].rstrip("/")
    state = state or _state_for_publish()
    client.publish(f"{base}/status", json.dumps(state, ensure_ascii=False), qos=0, retain=True)
    client.publish(f"{base}/availability", "online" if state.get("connected") else "offline", qos=0, retain=True)


def set_input(source):
    sw.command(f"s in source {source}!", timeout=0.12, expect_reply=False)
    invalidate_status()
    add_log(f"Переключение на {input_names()[source - 1]}")
    schedule_confirm()


def set_auto(enabled):
    sw.command(f"s auto switch {1 if enabled else 0}!", timeout=0.12, expect_reply=False)
    invalidate_status()
    add_log("Автопереключение включено" if enabled else "Автопереключение выключено")
    schedule_confirm()


def set_earc(enabled):
    sw.command(f"s earc {1 if enabled else 0}!", timeout=0.12, expect_reply=False)
    invalidate_status()
    add_log("eARC включён" if enabled else "eARC выключен")
    schedule_confirm()


def set_power(enabled):
    state = read_status(force=True)
    current = state.get("power")
    if current is None or bool(current) != bool(enabled):
        sw.command("power z!", timeout=0.12, expect_reply=False)
        invalidate_status()
        add_log("Питание включается" if enabled else "Питание выключается")
        schedule_confirm(0.8)


def reboot_device():
    sw.command("reboot!", timeout=0.2, expect_reply=False)
    sw.close()
    invalidate_status()
    add_log("SW411 перезагружается", "warn")
    schedule_confirm(1.8)


def _mqtt_on_connect(client, userdata, flags, rc):
    global _mqtt_connected
    _mqtt_connected = (rc == 0)
    if not _mqtt_connected:
        add_log(f"MQTT: ошибка подключения rc={rc}", "error")
        return
    cfg = config_snapshot(include_secret=True)
    base = cfg["mqtt"]["base_topic"].rstrip("/")
    for suffix in ("input/set", "auto/set", "earc/set", "power/set", "reboot/set"):
        client.subscribe(f"{base}/{suffix}")
    client.publish(f"{base}/availability", "online", retain=True)
    mqtt_publish_discovery()
    mqtt_publish_state()
    add_log("MQTT подключён")


def _mqtt_on_disconnect(client, userdata, rc):
    global _mqtt_connected
    _mqtt_connected = False


def _mqtt_on_message(client, userdata, msg):
    try:
        cfg = config_snapshot(include_secret=True)
        base = cfg["mqtt"]["base_topic"].rstrip("/")
        suffix = msg.topic[len(base) + 1:] if msg.topic.startswith(base + "/") else ""
        payload = msg.payload.decode("utf-8", errors="replace").strip()

        if suffix == "input/set":
            names = cfg["input_names"]
            if payload in names:
                set_input(names.index(payload) + 1)
            elif payload.isdigit() and int(payload) in (1, 2, 3, 4):
                set_input(int(payload))
        elif suffix == "auto/set":
            set_auto(payload.upper() == "ON")
        elif suffix == "earc/set":
            set_earc(payload.upper() == "ON")
        elif suffix == "power/set":
            set_power(payload.upper() == "ON")
        elif suffix == "reboot/set" and payload.upper() in ("PRESS", "REBOOT"):
            reboot_device()
    except Exception as e:
        add_log(f"MQTT command error: {e}", "error")


def mqtt_restart():
    global _mqtt_client, _mqtt_connected
    with _mqtt_lock:
        old = _mqtt_client
        _mqtt_client = None
        _mqtt_connected = False
    if old:
        try:
            old.loop_stop()
            old.disconnect()
        except Exception:
            pass

    cfg = config_snapshot(include_secret=True)
    m = cfg["mqtt"]
    if not m["enabled"] or not m["host"] or mqtt is None:
        return

    try:
        client = mqtt.Client(client_id=f"sw411-{socket.gethostname()}", clean_session=True)
        if m.get("username"):
            client.username_pw_set(m["username"], m.get("password") or None)
        client.on_connect = _mqtt_on_connect
        client.on_disconnect = _mqtt_on_disconnect
        client.on_message = _mqtt_on_message
        client.will_set(f"{m['base_topic'].rstrip('/')}/availability", "offline", retain=True)
        with _mqtt_lock:
            _mqtt_client = client
        client.connect_async(m["host"], int(m["port"]), 60)
        client.loop_start()
    except Exception as e:
        add_log(f"MQTT start error: {e}", "error")


def monitor_loop():
    previous = None
    while True:
        try:
            state = read_status(force=True)
            compact = {
                k: state.get(k)
                for k in ("connected", "firmware", "input", "power", "auto_switch", "earc", "temperature")
            }
            compact["input_names"] = state.get("input_names")
            if compact != previous:
                previous = compact
                broadcast_state(state)
        except Exception as e:
            add_log(f"Status monitor: {e}", "error")

        with _sse_lock:
            active_clients = len(_sse_clients)
        cfg = config_snapshot(include_secret=True)
        time.sleep(5 if active_clients or cfg["mqtt"]["enabled"] else 15)


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
        set_input(source)
        return jsonify(ok=True, input=source, name=input_names()[source - 1])
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
        set_auto(enabled)
        return jsonify(ok=True, auto_switch=enabled)
    except Exception as e:
        add_log(f"Ошибка режима автопереключения: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/earc")
def api_earc():
    payload = request.get_json(silent=True) or {}
    enabled = payload.get("enabled")
    if not isinstance(enabled, bool):
        return jsonify(ok=False, error="Поле enabled должно быть true/false"), 400
    try:
        set_earc(enabled)
        return jsonify(ok=True, earc=enabled)
    except Exception as e:
        add_log(f"Ошибка eARC: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/power/toggle")
def api_power_toggle():
    try:
        state = read_status(force=True)
        enabled = not bool(state.get("power"))
        set_power(enabled)
        return jsonify(ok=True, power=enabled)
    except Exception as e:
        add_log(f"Ошибка питания: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.post("/api/reboot")
def api_reboot():
    try:
        reboot_device()
        return jsonify(ok=True)
    except Exception as e:
        add_log(f"Ошибка перезагрузки: {e}", "error")
        return jsonify(ok=False, error=str(e)), 500


@app.get("/api/events")
def api_events():
    q = queue.Queue(maxsize=8)
    with _sse_lock:
        _sse_clients.add(q)

    @stream_with_context
    def generate():
        try:
            initial = json.dumps(read_status(force=True), ensure_ascii=False, separators=(",", ":"))
            yield f"data: {initial}\n\n"
            while True:
                try:
                    payload = q.get(timeout=15)
                    yield f"data: {payload}\n\n"
                except queue.Empty:
                    yield ": keepalive\n\n"
        finally:
            with _sse_lock:
                _sse_clients.discard(q)

    return Response(generate(), mimetype="text/event-stream", headers={"X-Accel-Buffering": "no"})


@app.get("/api/settings")
def api_settings():
    cfg = config_snapshot(include_secret=False)
    cfg["mqtt"]["connected"] = bool(_mqtt_connected)
    return jsonify(cfg)


@app.put("/api/settings")
def api_settings_put():
    payload = request.get_json(silent=True) or {}
    with _config_lock:
        old_password = _config["mqtt"].get("password", "")
        merged = _merge_config({**_config, **payload})
        if isinstance(payload.get("mqtt"), dict):
            mqtt_payload = payload["mqtt"]
            merged["mqtt"].update(_config["mqtt"])
            for key in ("enabled", "host", "port", "username", "base_topic", "discovery_prefix"):
                if key in mqtt_payload:
                    merged["mqtt"][key] = mqtt_payload[key]
            new_password = mqtt_payload.get("password")
            if isinstance(new_password, str) and new_password:
                merged["mqtt"]["password"] = new_password
            else:
                merged["mqtt"]["password"] = old_password
        _config.clear()
        _config.update(merged)
        _save_config_unlocked(_config)

    add_log("Настройки сохранены")
    invalidate_status()
    mqtt_restart()
    mqtt_publish_discovery()
    broadcast_state()
    return api_settings()


def _ha_authorized():
    auth = request.headers.get("Authorization", "")
    with _config_lock:
        token = _config["api_token"]
    return auth == f"Bearer {token}"


@app.get("/api/ha/state")
def ha_state():
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    return jsonify(read_status(force=True))


@app.post("/api/ha/input/<int:source>")
def ha_input(source):
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    if source not in (1, 2, 3, 4):
        return jsonify(error="bad input"), 400
    set_input(source)
    return jsonify(ok=True)


@app.post("/api/ha/auto")
def ha_auto():
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    enabled = bool((request.get_json(silent=True) or {}).get("enabled"))
    set_auto(enabled)
    return jsonify(ok=True)


@app.post("/api/ha/earc")
def ha_earc():
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    enabled = bool((request.get_json(silent=True) or {}).get("enabled"))
    set_earc(enabled)
    return jsonify(ok=True)


@app.post("/api/ha/power")
def ha_power():
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    enabled = bool((request.get_json(silent=True) or {}).get("enabled"))
    set_power(enabled)
    return jsonify(ok=True)


@app.post("/api/ha/reboot")
def ha_reboot():
    if not _ha_authorized():
        return jsonify(error="unauthorized"), 401
    reboot_device()
    return jsonify(ok=True)


@app.get("/manifest.webmanifest")
def manifest():
    payload = {
        "name": "SW411 Control",
        "short_name": "SW411",
        "start_url": "/",
        "scope": "/",
        "display": "standalone",
        "background_color": "#071019",
        "theme_color": "#081018",
        "icons": [{"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any maskable"}]
    }
    return Response(json.dumps(payload, ensure_ascii=False), mimetype="application/manifest+json")


@app.get("/sw.js")
def service_worker():
    js = """const C='sw411-v0.4.1';
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(['/','/manifest.webmanifest','/icon.svg'])))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==C).map(k=>caches.delete(k)))));self.clients.claim()});
self.addEventListener('fetch',e=>{if(e.request.method!=='GET')return;const u=new URL(e.request.url);if(u.pathname.startsWith('/api/'))return;e.respondWith(fetch(e.request).catch(()=>caches.match(e.request).then(r=>r||caches.match('/'))))});"""
    return Response(js, mimetype="application/javascript", headers={"Service-Worker-Allowed": "/"})


@app.get("/icon.svg")
def icon_svg():
    svg = """<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 512 512'><defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop stop-color='#163047'/><stop offset='1' stop-color='#071019'/></linearGradient></defs><rect width='512' height='512' rx='112' fill='url(#g)'/><rect x='96' y='170' width='320' height='172' rx='42' fill='none' stroke='#e9f7ff' stroke-width='24'/><circle cx='160' cy='256' r='14' fill='#22d3ee'/><circle cx='352' cy='256' r='14' fill='#22e39a'/></svg>"""
    return Response(svg, mimetype="image/svg+xml")


def read_update_status():
    try:
        with open(UPDATE_STATUS, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except FileNotFoundError:
        return {"state": "idle", "current_version": APP_VERSION}
    except Exception as e:
        return {"state": "unknown", "current_version": APP_VERSION, "error": str(e)}


def write_update_status(state, **extra):
    payload = {
        "state": state,
        "current_version": APP_VERSION,
        "updated_at": datetime.now().isoformat()
    }
    payload.update(extra)
    tmp = UPDATE_STATUS + ".app.tmp"
    os.makedirs(os.path.dirname(UPDATE_STATUS), exist_ok=True)
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, ensure_ascii=False)
    os.chmod(tmp, 0o600)
    os.replace(tmp, UPDATE_STATUS)
    return payload


@app.get("/api/update-status")
def api_update_status():
    return jsonify(read_update_status())


@app.get("/api/app")
def api_app():
    return jsonify(
        name="SW411 Control",
        version=APP_VERSION,
        update_supported=True,
    )


@app.post("/api/update-control")
def api_update_control():
    try:
        os.makedirs(os.path.dirname(UPDATE_REQUEST), exist_ok=True)
        status = read_update_status()
        if os.path.exists(UPDATE_REQUEST) or status.get("state") in ("queued", "running"):
            return jsonify(ok=True, already_running=True, status=status, version=APP_VERSION), 202

        write_update_status("queued", source="github")
        with open(UPDATE_REQUEST, "w", encoding="utf-8") as fh:
            fh.write(datetime.now().isoformat())

        add_log("Запущено обновление SW411 Control из GitHub", "info")
        return jsonify(ok=True, version=APP_VERSION, status=read_update_status()), 202
    except Exception as e:
        try:
            write_update_status("failed", error=str(e))
        except Exception:
            pass
        add_log(f"Ошибка запуска обновления SW411 Control: {e}", "error")
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
mqtt_restart()
threading.Thread(target=monitor_loop, daemon=True).start()

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8787, threaded=True)

PYAPP
python3 -m py_compile /opt/sw411/app.py.new
mv -f /opt/sw411/app.py.new /opt/sw411/app.py
chmod 0755 /opt/sw411/app.py

cat > /opt/sw411/templates/index.html.new <<'HTML'
<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="theme-color" content="#081018">
  <meta name="apple-mobile-web-app-capable" content="yes">
  <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
  <meta name="mobile-web-app-capable" content="yes">
  <link rel="manifest" href="/manifest.webmanifest">
  <link rel="icon" href="/icon.svg" type="image/svg+xml">
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
    .more-wrap{position:relative;flex:0 0 auto}
    .more-btn{
      width:46px;height:46px;border:1px solid var(--line);border-radius:15px;
      display:grid;place-items:center;color:#c7d2df;cursor:pointer;
      background:linear-gradient(145deg,rgba(31,45,59,.94),rgba(15,26,36,.94));
      box-shadow:inset 0 1px rgba(255,255,255,.04),0 10px 24px rgba(0,0,0,.18)
    }
    .more-btn:active{transform:scale(.96)}
    .more-btn svg{width:24px;height:24px}
    .more-menu{
      display:none;position:absolute;z-index:30;right:0;top:54px;width:min(310px,calc(100vw - 32px));
      border:1px solid var(--line);border-radius:18px;padding:8px;
      background:rgba(13,24,34,.98);box-shadow:0 22px 60px rgba(0,0,0,.48);
      backdrop-filter:blur(16px)
    }
    .more-menu.open{display:block;animation:pop .14s ease-out}
    .menu-title{
      padding:9px 10px 10px;color:var(--muted);font-size:12px;
      display:flex;justify-content:space-between;gap:12px
    }
    .menu-action{
      width:100%;border:0;border-radius:14px;padding:12px;
      display:flex;align-items:center;gap:12px;text-align:left;color:var(--text);
      background:rgba(31,45,59,.72);cursor:pointer
    }
    .menu-action:active{transform:scale(.985)}
    .menu-action svg{width:25px;height:25px;color:var(--cyan);flex:0 0 auto}
    .menu-action span{display:block;min-width:0}
    .menu-action strong{display:block;font-size:14px}
    .menu-action small{display:block;color:var(--muted);font-size:11px;margin-top:2px}
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
    .live-badge{display:inline-flex;align-items:center;gap:6px}
    .live-badge:before{content:"";width:6px;height:6px;border-radius:50%;background:var(--green);box-shadow:0 0 8px var(--green)}
    .audio-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:7px;padding:13px 14px 15px}
    .audio-opt{
      border:1px solid var(--line);border-radius:13px;padding:10px 4px;
      background:rgba(13,24,34,.72);color:#8292a6;font-weight:800;font-size:12px
    }
    .audio-opt:disabled{opacity:.6}
    .audio-note{padding:0 14px 13px;color:var(--muted);font-size:11px;line-height:1.4}
    .modal{
      position:fixed;inset:0;z-index:60;display:none;align-items:flex-end;justify-content:center;
      padding:12px;background:rgba(0,0,0,.58);backdrop-filter:blur(7px)
    }
    .modal.open{display:flex}
    .sheet{
      width:min(100%,620px);max-height:88svh;overflow:auto;border-radius:24px;
      border:1px solid var(--line);background:#0c1722;box-shadow:0 28px 90px rgba(0,0,0,.58);
      padding:18px
    }
    .sheet-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:15px}
    .sheet-head h3{margin:0;font-size:21px}
    .icon-btn{border:0;background:#1a2836;color:#d5deea;width:38px;height:38px;border-radius:12px;font-size:22px}
    .form-section{padding:15px 0;border-top:1px solid var(--line)}
    .form-section:first-of-type{border-top:0;padding-top:0}
    .form-title{font-weight:800;margin-bottom:10px}
    .field-grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
    .field{display:flex;flex-direction:column;gap:5px}
    .field.full{grid-column:1/-1}
    .field label{font-size:11px;color:var(--muted)}
    .field input{
      width:100%;border:1px solid var(--line);border-radius:12px;background:#101e2b;color:var(--text);
      padding:11px 12px;outline:none;font:inherit
    }
    .field input:focus{border-color:rgba(34,211,238,.55)}
    .check-row{display:flex;align-items:center;gap:10px;margin:5px 0 12px}
    .check-row input{width:19px;height:19px}
    .sheet-actions{display:flex;gap:10px;position:sticky;bottom:-18px;padding:12px 0 2px;background:#0c1722}
    .sheet-actions button{flex:1;border:0;border-radius:14px;padding:12px;font-weight:800}
    .save-btn{background:linear-gradient(90deg,var(--cyan2),var(--cyan));color:#03232a}
    .cancel-btn{background:#1b2936;color:#c9d4df}
    .token-row{display:flex;gap:8px}
    .token-row input{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:11px}
    .copy-btn{border:1px solid var(--line);background:#1a2937;color:#dbe7f3;border-radius:12px;padding:0 12px}
    .hold-protected{position:relative;overflow:hidden}
    .hold-protected:after{
      content:"";position:absolute;left:0;bottom:0;height:3px;width:0;background:var(--red)
    }
    .hold-protected.holding:after{width:100%;transition:width 1.5s linear}
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
    <div class="more-wrap">
      <button class="more-btn" id="moreBtn" aria-label="Меню" aria-expanded="false">
        <svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/></svg>
      </button>
      <div class="more-menu" id="moreMenu">
        <div class="menu-title">SW411 Control <span id="appVersion">v—</span></div>
        <button class="menu-action" id="settingsBtn">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.8 1.8 0 0 0 .36 2l.06.06-2.12 2.12-.06-.06a1.8 1.8 0 0 0-2-.36 1.8 1.8 0 0 0-1.1 1.65V20.5h-3v-.09A1.8 1.8 0 0 0 10.4 18.8a1.8 1.8 0 0 0-2 .36l-.06.06-2.12-2.12.06-.06a1.8 1.8 0 0 0 .36-2A1.8 1.8 0 0 0 5 13.9H4.9v-3H5A1.8 1.8 0 0 0 6.6 9.8a1.8 1.8 0 0 0-.36-2l-.06-.06 2.12-2.12.06.06a1.8 1.8 0 0 0 2 .36A1.8 1.8 0 0 0 11.5 4.4V4.3h3v.1a1.8 1.8 0 0 0 1.1 1.64 1.8 1.8 0 0 0 2-.36l.06-.06 2.12 2.12-.06.06a1.8 1.8 0 0 0-.36 2A1.8 1.8 0 0 0 21 10.9h.1v3H21A1.8 1.8 0 0 0 19.4 15Z"/></svg>
          <span><strong>Настройки</strong><small>Имена входов, MQTT, Home Assistant</small></span>
        </button>
        <button class="menu-action" id="installPwaBtn">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M12 3v12"/><path d="m7 10 5 5 5-5"/><path d="M5 20h14"/></svg>
          <span><strong>Установить приложение</strong><small>Добавить SW411 Control на главный экран</small></span>
        </button>
        <button class="menu-action hold-protected" id="updateControlBtn">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/></svg>
          <span><strong>Обновить из GitHub</strong><small>Удерживай 1,5 сек.</small></span>
        </button>
      </div>
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
      <button class="action danger hold-protected" id="rebootBtn">
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
        <span class="input-label">HDMI 1</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="2">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span class="input-label">HDMI 2</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="3">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span class="input-label">HDMI 3</span><i class="tiny-dot"></i>
      </button>
      <button class="input-btn" data-input="4">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M5 8h14l2 3-4 5H7l-4-5 2-3Z"/><path d="M8 11h8"/></svg>
        <span class="input-label">HDMI 4</span><i class="tiny-dot"></i>
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
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 8h16v8H4z"/><path d="M8 12h8"/><path d="M12 5v3"/></svg>
        </div>
        <div class="mode-copy">
          <strong>eARC / ARC</strong>
          <small>Звук с телевизора и HDMI-CEC аудио</small>
        </div>
        <button class="switch" id="earcSwitch" aria-label="eARC"><i></i></button>
      </div>
      <div class="mode-row" style="display:block">
        <div style="display:flex;align-items:center;gap:14px">
          <div class="mode-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 9v6"/><path d="M8 6v12"/><path d="M12 4v16"/><path d="M16 7v10"/><path d="M20 10v4"/></svg>
          </div>
          <div class="mode-copy">
            <strong>Audio EDID</strong>
            <small>2.0 / 5.1 / 7.1 / PASS</small>
          </div>
          <span class="badge">USB CLI нет</span>
        </div>
        <div class="audio-grid">
          <button class="audio-opt" disabled>2.0</button>
          <button class="audio-opt" disabled>5.1</button>
          <button class="audio-opt" disabled>7.1</button>
          <button class="audio-opt" disabled>PASS</button>
        </div>
        <div class="audio-note">Прошивка SW411 не публикует команду Audio EDID в serial CLI. Я не отправляю непроверенные команды устройству.</div>
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
    <div class="section-title"><h2>Системный журнал</h2><small class="live-badge" id="lastUpdate">LIVE</small></div>
    <div class="panel logs" id="logs">
      <div class="log"><i class="log-dot"></i><span class="log-time">—</span><span class="log-message">Журнал пока пуст</span></div>
    </div>
  </section>
</main>

<div class="modal" id="settingsModal">
  <div class="sheet">
    <div class="sheet-head">
      <h3>Настройки</h3>
      <button class="icon-btn" id="closeSettingsBtn" aria-label="Закрыть">×</button>
    </div>

    <div class="form-section">
      <div class="form-title">Имена входов</div>
      <div class="field-grid">
        <div class="field"><label>HDMI 1</label><input id="name1" maxlength="32"></div>
        <div class="field"><label>HDMI 2</label><input id="name2" maxlength="32"></div>
        <div class="field"><label>HDMI 3</label><input id="name3" maxlength="32"></div>
        <div class="field"><label>HDMI 4</label><input id="name4" maxlength="32"></div>
      </div>
    </div>

    <div class="form-section">
      <div class="form-title">Home Assistant REST API</div>
      <div class="field full">
        <label>Bearer token</label>
        <div class="token-row">
          <input id="apiToken" readonly>
          <button class="copy-btn" id="copyTokenBtn" type="button">Копировать</button>
        </div>
      </div>
      <div class="audio-note" style="padding:8px 0 0">Состояние: GET /api/ha/state · Заголовок Authorization: Bearer TOKEN</div>
    </div>

    <div class="form-section">
      <div class="form-title">MQTT + Home Assistant Discovery</div>
      <label class="check-row"><input type="checkbox" id="mqttEnabled"> Включить MQTT</label>
      <div class="field-grid">
        <div class="field full"><label>MQTT broker</label><input id="mqttHost" placeholder="192.168.2.x"></div>
        <div class="field"><label>Порт</label><input id="mqttPort" type="number" value="1883"></div>
        <div class="field"><label>Пользователь</label><input id="mqttUser"></div>
        <div class="field full"><label>Пароль (пусто = не менять)</label><input id="mqttPass" type="password"></div>
        <div class="field"><label>Base topic</label><input id="mqttTopic" value="sw411"></div>
        <div class="field"><label>Discovery prefix</label><input id="mqttDiscovery" value="homeassistant"></div>
      </div>
      <div class="audio-note" id="mqttState" style="padding:8px 0 0">MQTT выключен</div>
    </div>

    <div class="sheet-actions">
      <button class="cancel-btn" id="cancelSettingsBtn">Отмена</button>
      <button class="save-btn" id="saveSettingsBtn">Сохранить</button>
    </div>
  </div>
</div>

<div class="toast-wrap"><div class="toast" id="toast"></div></div>

<script>
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];

let current = {};
let busy = false;
let toastTimer;
let pendingInput = null;
let pendingInputUntil = 0;
let eventSource = null;
let deferredInstallPrompt = null;

function toast(msg, error=false){
  const t=$("#toast");
  t.textContent=msg;
  t.className="toast show"+(error?" error":"");
  clearTimeout(toastTimer);
  toastTimer=setTimeout(()=>t.className="toast",2800);
}

function setBusy(v){
  busy=v;
  $("#app").classList.toggle("busy",v);
}

async function api(url, options={}){
  const r=await fetch(url,{
    cache:"no-store",
    headers:{"Content-Type":"application/json", ...(options.headers||{})},
    ...options
  });
  const data=await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(data.error || `HTTP ${r.status}`);
  return data;
}

function render(s, fromServer=false){
  s={...(s||{})};

  if(pendingInput !== null){
    if(fromServer && s.input === pendingInput){
      pendingInput=null;
      pendingInputUntil=0;
    }else if(Date.now() < pendingInputUntil){
      s.input=pendingInput;
    }else{
      pendingInput=null;
      pendingInputUntil=0;
    }
  }

  current=s;
  const names=Array.isArray(s.input_names)&&s.input_names.length===4
    ? s.input_names
    : ["HDMI 1","HDMI 2","HDMI 3","HDMI 4"];

  $$(".input-btn").forEach((b,i)=>{
    b.classList.toggle("active",Number(b.dataset.input)===s.input);
    const label=b.querySelector(".input-label");
    if(label) label.textContent=names[i];
  });

  const online=!!s.connected;
  $("#connDot").classList.toggle("online",online);
  $("#connTitle").textContent=online?"Подключено":"Нет связи";
  $("#connTitle").classList.toggle("online",online);
  $("#connSub").textContent=online?"Устройство готово к работе":(s.error||"SW411 не найден");
  $("#port").textContent=s.port||"/dev/sw411";
  $("#fw").textContent=s.firmware||"—";
  $("#activeInput").textContent=s.input ? (names[s.input-1]||`HDMI ${s.input}`) : "—";
  $("#temp").textContent=(s.temperature!==null&&s.temperature!==undefined)?`${s.temperature} °C`:"—";

  const auto=s.auto_switch===true;
  $("#autoSwitch").classList.toggle("on",auto);
  $("#manualBadge").textContent=s.auto_switch===null||s.auto_switch===undefined?"—":(auto?"ВЫКЛ":"ВКЛ");

  const earc=s.earc===true;
  $("#earcSwitch").classList.toggle("on",earc);

  $("#powerText").textContent=s.power===true?"Выключить":s.power===false?"Включить":"Питание";
}

async function refresh(force=false){
  try{
    const status=await api("/api/status"+(force?"?force=1":""));
    render(status,true);
  }catch(e){
    render({connected:false,error:e.message,port:"/dev/sw411"},true);
  }
}

async function loadLogs(){
  try{
    const list=await api("/api/logs");
    const el=$("#logs");
    if(!list.length)return;
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

function startLive(){
  if(eventSource) eventSource.close();
  eventSource=new EventSource("/api/events");

  eventSource.onopen=()=>{
    $("#lastUpdate").textContent="LIVE";
  };

  eventSource.onmessage=e=>{
    try{
      render(JSON.parse(e.data),true);
    }catch(_){}
  };

  eventSource.onerror=()=>{
    $("#lastUpdate").textContent="RECONNECT";
  };
}

$$(".input-btn").forEach(btn=>btn.addEventListener("click",async()=>{
  if(busy)return;
  const n=Number(btn.dataset.input);
  pendingInput=n;
  pendingInputUntil=Date.now()+4000;
  current={...current,input:n};
  render(current,false);

  setBusy(true);
  try{
    const r=await api(`/api/input/${n}`,{method:"POST"});
    toast(`${r.name||("HDMI "+n)} выбран`);
    loadLogs();
  }catch(e){
    pendingInput=null;
    pendingInputUntil=0;
    toast(e.message,true);
    refresh(true);
  }finally{
    setBusy(false);
  }
}));

$("#autoSwitch").addEventListener("click",async()=>{
  if(busy)return;
  const enabled=current.auto_switch!==true;
  current={...current,auto_switch:enabled};
  render(current,false);
  setBusy(true);
  try{
    await api("/api/auto",{method:"POST",body:JSON.stringify({enabled})});
    toast(enabled?"Автопереключение включено":"Ручной режим включён");
    loadLogs();
  }catch(e){
    toast(e.message,true);
    refresh(true);
  }finally{
    setBusy(false);
  }
});

$("#earcSwitch").addEventListener("click",async()=>{
  if(busy)return;
  const enabled=current.earc!==true;
  current={...current,earc:enabled};
  render(current,false);
  setBusy(true);
  try{
    await api("/api/earc",{method:"POST",body:JSON.stringify({enabled})});
    toast(enabled?"eARC включён":"eARC выключен");
    loadLogs();
  }catch(e){
    toast(e.message,true);
    refresh(true);
  }finally{
    setBusy(false);
  }
});

$("#powerBtn").addEventListener("click",async()=>{
  if(busy)return;
  if(current.power===true && !confirm("Выключить SW411?")) return;
  setBusy(true);
  try{
    const r=await api("/api/power/toggle",{method:"POST"});
    current={...current,power:r.power};
    render(current,false);
    toast(r.power?"SW411 включается":"SW411 выключается");
    loadLogs();
  }catch(e){
    toast(e.message,true);
    refresh(true);
  }finally{
    setBusy(false);
  }
});

$("#refreshBtn").addEventListener("click",async()=>{
  if(busy)return;
  setBusy(true);
  await refresh(true);
  await loadLogs();
  setBusy(false);
  toast("Статус обновлён");
});

function bindHold(btn, action, ms=1500){
  let timer=null;
  let fired=false;

  const cancel=()=>{
    if(timer) clearTimeout(timer);
    timer=null;
    btn.classList.remove("holding");
  };

  btn.addEventListener("pointerdown",e=>{
    if(busy)return;
    e.preventDefault();
    fired=false;
    btn.classList.add("holding");
    timer=setTimeout(async()=>{
      timer=null;
      fired=true;
      btn.classList.remove("holding");
      await action();
    },ms);
  });

  ["pointerup","pointerleave","pointercancel"].forEach(ev=>btn.addEventListener(ev,()=>{
    if(!fired)cancel();
  }));
  btn.addEventListener("contextmenu",e=>e.preventDefault());
}

bindHold($("#rebootBtn"),async()=>{
  setBusy(true);
  try{
    await api("/api/reboot",{method:"POST"});
    toast("SW411 перезагружается…");
    loadLogs();
  }catch(e){
    toast(e.message,true);
  }finally{
    setTimeout(()=>setBusy(false),900);
  }
});

async function loadAppInfo(){
  try{
    const info=await api("/api/app");
    $("#appVersion").textContent="v"+(info.version||"—");
    return info;
  }catch(_){
    $("#appVersion").textContent="v—";
    return null;
  }
}

$("#moreBtn").addEventListener("click",e=>{
  e.stopPropagation();
  const open=!$("#moreMenu").classList.contains("open");
  $("#moreMenu").classList.toggle("open",open);
  $("#moreBtn").setAttribute("aria-expanded",open?"true":"false");
  if(open)loadAppInfo();
});

$("#moreMenu").addEventListener("click",e=>e.stopPropagation());

document.addEventListener("click",()=>{
  $("#moreMenu").classList.remove("open");
  $("#moreBtn").setAttribute("aria-expanded","false");
});

async function updateControl(){
  $("#moreMenu").classList.remove("open");
  $("#moreBtn").setAttribute("aria-expanded","false");

  const before=(await loadAppInfo())?.version||null;
  setBusy(true);

  try{
    await api("/api/update-control",{method:"POST"});
    toast("Обновление запущено…");
  }catch(e){
    setBusy(false);
    toast(e.message,true);
    return;
  }

  if(eventSource){
    eventSource.close();
    eventSource=null;
  }

  const started=Date.now();
  let sawOffline=false;

  const check=async()=>{
    try{
      const st=await api("/api/update-status");
      if(st.state==="failed"){
        setBusy(false);
        toast(st.error||"Обновление завершилось ошибкой",true);
        startLive();
        return;
      }
      if(st.state==="success"){
        const info=await loadAppInfo();
        setBusy(false);
        const after=info?.version||st.target_version||before||"";
        toast(after ? ("SW411 Control v"+after+" установлен") : "SW411 Control обновлён");
        setTimeout(()=>location.reload(),700);
        return;
      }
    }catch(_){
      sawOffline=true;
    }

    if(Date.now()-started < 180000){
      setTimeout(check,1200);
    }else{
      setBusy(false);
      toast(sawOffline ? "Сервис ещё не вернулся после обновления" : "Обновление не завершилось за 3 минуты",true);
      startLive();
    }
  };

  setTimeout(check,800);
}

bindHold($("#updateControlBtn"),updateControl,1500);

async function openSettings(){
  $("#moreMenu").classList.remove("open");
  $("#moreBtn").setAttribute("aria-expanded","false");
  try{
    const cfg=await api("/api/settings");
    const names=cfg.input_names||["HDMI 1","HDMI 2","HDMI 3","HDMI 4"];
    [1,2,3,4].forEach((n,i)=>$("#name"+n).value=names[i]||`HDMI ${n}`);
    $("#apiToken").value=cfg.api_token||"";

    const m=cfg.mqtt||{};
    $("#mqttEnabled").checked=!!m.enabled;
    $("#mqttHost").value=m.host||"";
    $("#mqttPort").value=m.port||1883;
    $("#mqttUser").value=m.username||"";
    $("#mqttPass").value="";
    $("#mqttTopic").value=m.base_topic||"sw411";
    $("#mqttDiscovery").value=m.discovery_prefix||"homeassistant";
    $("#mqttState").textContent=m.enabled?(m.connected?"MQTT подключён":"MQTT включён, соединения пока нет"):"MQTT выключен";

    $("#settingsModal").classList.add("open");
  }catch(e){
    toast(e.message,true);
  }
}

function closeSettings(){
  $("#settingsModal").classList.remove("open");
}

$("#settingsBtn").addEventListener("click",openSettings);
$("#closeSettingsBtn").addEventListener("click",closeSettings);
$("#cancelSettingsBtn").addEventListener("click",closeSettings);
$("#settingsModal").addEventListener("click",e=>{
  if(e.target===$("#settingsModal"))closeSettings();
});

$("#copyTokenBtn").addEventListener("click",async()=>{
  try{
    await navigator.clipboard.writeText($("#apiToken").value);
    toast("API token скопирован");
  }catch(_){
    $("#apiToken").select();
    document.execCommand("copy");
    toast("API token скопирован");
  }
});

$("#saveSettingsBtn").addEventListener("click",async()=>{
  const payload={
    input_names:[1,2,3,4].map(n=>$("#name"+n).value.trim()||`HDMI ${n}`),
    mqtt:{
      enabled:$("#mqttEnabled").checked,
      host:$("#mqttHost").value.trim(),
      port:Number($("#mqttPort").value)||1883,
      username:$("#mqttUser").value.trim(),
      password:$("#mqttPass").value,
      base_topic:$("#mqttTopic").value.trim()||"sw411",
      discovery_prefix:$("#mqttDiscovery").value.trim()||"homeassistant"
    }
  };

  setBusy(true);
  try{
    const cfg=await api("/api/settings",{method:"PUT",body:JSON.stringify(payload)});
    current={...current,input_names:cfg.input_names};
    render(current,false);
    closeSettings();
    toast("Настройки сохранены");
  }catch(e){
    toast(e.message,true);
  }finally{
    setBusy(false);
  }
});

window.addEventListener("beforeinstallprompt",e=>{
  e.preventDefault();
  deferredInstallPrompt=e;
  $("#installPwaBtn").style.display="";
});

$("#installPwaBtn").addEventListener("click",async()=>{
  $("#moreMenu").classList.remove("open");
  if(deferredInstallPrompt){
    deferredInstallPrompt.prompt();
    await deferredInstallPrompt.userChoice;
    deferredInstallPrompt=null;
    return;
  }
  toast("На HTTP используй Chrome ⋮ → Добавить на главный экран");
});

if("serviceWorker" in navigator){
  window.addEventListener("load",()=>navigator.serviceWorker.register("/sw.js").catch(()=>{}));
}

loadAppInfo();
refresh(true);
loadLogs();
startLive();
</script>
</body>
</html>

HTML
mv -f /opt/sw411/templates/index.html.new /opt/sw411/templates/index.html
chmod 0644 /opt/sw411/templates/index.html

cat > /etc/udev/rules.d/99-sw411.rules <<'UDEV'
SUBSYSTEM=="tty", ATTRS{idVendor}=="1a86", ATTRS{idProduct}=="7523", SYMLINK+="sw411", GROUP="dialout", MODE="0660"
UDEV
udevadm control --reload-rules
udevadm trigger || true

cat > /usr/local/sbin/sw411-control-update <<'UPDATER'
#!/bin/bash
set -euo pipefail

REQ=/run/sw411-control/update.request
STATUS_DIR=/var/lib/sw411-control
STATUS="$STATUS_DIR/update-status.json"
TMP=$(mktemp /tmp/sw411-control-update.XXXXXX.sh)
URL=https://raw.githubusercontent.com/FeromonDE/SW411-Control/main/install-sw411.sh

mkdir -p "$STATUS_DIR"
rm -f "$REQ"

write_status() {
  local state="$1"
  local target="${2:-}"
  local error="${3:-}"
  local tmp_status
  tmp_status=$(mktemp "$STATUS_DIR/.update-status.XXXXXX")
  python3 - "$tmp_status" "$state" "$target" "$error" <<'PY'
import json, sys
from datetime import datetime
path, state, target, error = sys.argv[1:5]
data = {"state": state, "updated_at": datetime.now().isoformat()}
if target:
    data["target_version"] = target
if error:
    data["error"] = error
with open(path, "w", encoding="utf-8") as fh:
    json.dump(data, fh, ensure_ascii=False)
PY
  chown sw411:sw411 "$tmp_status"
  chmod 0600 "$tmp_status"
  mv -f "$tmp_status" "$STATUS"
}

cleanup() {
  local rc=$?
  rm -f "$TMP"
  if [ "$rc" -ne 0 ]; then
    write_status failed "${TARGET_VERSION:-}" "Updater exited with code $rc"
  fi
  exit "$rc"
}
trap cleanup EXIT

write_status running
curl -fsSL --retry 3 --connect-timeout 10 "$URL" -o "$TMP"
bash -n "$TMP"

TARGET_VERSION=$(sed -n 's/^APP_VERSION = "\([^"]*\)".*/\1/p' "$TMP" | head -n1)
write_status running "$TARGET_VERSION"

/bin/bash "$TMP"

write_status success "$TARGET_VERSION"
UPDATER
chmod 0755 /usr/local/sbin/sw411-control-update

cat > /etc/systemd/system/sw411-update.service <<'UPDATEUNIT'
[Unit]
Description=Update SW411 Control from GitHub
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/sw411-control-update
UPDATEUNIT

cat > /etc/systemd/system/sw411-update.path <<'PATHUNIT'
[Unit]
Description=Watch for SW411 Control update requests

[Path]
PathExists=/run/sw411-control/update.request
Unit=sw411-update.service

[Install]
WantedBy=multi-user.target
PATHUNIT

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
RuntimeDirectory=sw411-control
RuntimeDirectoryMode=0750
StateDirectory=sw411-control
StateDirectoryMode=0750
Environment=SW411_CONFIG=/var/lib/sw411-control/config.json
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
        proxy_read_timeout 3600s;\n        proxy_buffering off;
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
systemctl enable --now sw411-update.path
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