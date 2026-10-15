#!/usr/bin/env python3
"""Yeelight LAN: detect devices and read properties. Python stdlib only, read-only."""
import json
import socket
import time

# Exact Yeelight device IDs and names from the supplied HA device/entity registries.
LAMPS = {
    0x07980E72: "Тех. комната (HWR)",
    0x10D9AAA1: "Meteorite",
    0x13E3AFB2: "Ванная",
    0x13E3B798: "2-й этаж",
    0x04A3DC76: "Гостиная (потолок)",
    0x13E3B01D: "Андрей (потолок)",
    0x1819FC2F: "Кухня",
    0x13F2052E: "Гостиная (LED-лента)",
    0x13E3B012: "Миша (потолок)",
    0x13E3AFEE: "Спальня",
    0x102FEF6C: "Андрей (настольная)",
    0x13E3AF9F: "Прихожая №1",
    0x13E3B82E: "Прихожая №2",
    0x12C66F8B: "Гостевая",
}

SEARCH = (b'M-SEARCH * HTTP/1.1\r\n'
          b'HOST: 239.255.255.250:1982\r\n'
          b'MAN: "ssdp:discover"\r\n'
          b'ST: wifi_bulb\r\n\r\n')


def discover(seconds=5):
    found = {}
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP) as sock:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 4)
        sock.bind(("", 0))
        deadline = time.monotonic() + seconds
        next_send = 0
        while time.monotonic() < deadline:
            if time.monotonic() >= next_send:
                sock.sendto(SEARCH, ("239.255.255.250", 1982))
                next_send = time.monotonic() + 1.5
            sock.settimeout(min(0.4, max(0.01, deadline - time.monotonic())))
            try:
                raw, src = sock.recvfrom(8192)
            except socket.timeout:
                continue
            hdr = {}
            for line in raw.decode("utf-8", "replace").splitlines():
                k, sep, v = line.partition(":")
                if sep:
                    hdr[k.strip().lower()] = v.strip()
            try:
                device_id = int(hdr["id"], 16)
            except (ValueError, KeyError):
                continue
            found[device_id] = src[0]
    return found


def props(ip):
    request = b'{"id":1,"method":"get_prop","params":["power","save_state","lan_ctrl"]}\r\n'
    with socket.create_connection((ip, 55443), timeout=2.5) as conn:
        conn.settimeout(2.5)
        conn.sendall(request)
        buffer = bytearray()
        while b'\n' not in buffer and len(buffer) < 8192:
            part = conn.recv(4096)
            if not part:
                break
            buffer.extend(part)
    data = json.loads(bytes(buffer).split(b'\n', 1)[0].strip())
    result = data.get('result')
    if isinstance(result, list) and len(result) >= 3:
        return [str(x) for x in result[:3]]
    return ['ERR', str(data.get('error', 'Bad reply')), '-']


def main():
    print('Поиск ламп Yeelight в LAN (5 сек.)...')
    discovered = discover()
    print(f'{"Лампа":25} {"IP":16} {"Power":8} {"Save":7} LAN')
    print('-' * 68)
    for device_id, name in LAMPS.items():
        ip = discovered.pop(device_id, None)
        if ip is None:
            print(f'{name:25} {"—":16} не найдена')
            continue
        try:
            power, save, lan = props(ip)
            print(f'{name:25} {ip:16} {power:8} {save:7} {lan}')
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f'{name:25} {ip:16} ERROR: {exc}')
    for device_id, ip in discovered.items():
        try:
            power, save, lan = props(ip)
            print(f'{"Неизвестная " + hex(device_id):25} {ip:16} {power:8} {save:7} {lan}')
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f'{"Неизвестная " + hex(device_id):25} {ip:16} ERROR: {exc}')
    print('\nSave: 0 — выкл., 1 — вкл. (автосохранение состояния, НЕ Auto-on).')


if __name__ == '__main__':
    main()
