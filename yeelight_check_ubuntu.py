#!/usr/bin/env python3
"""Yeelight LAN: detect devices and read properties. Python stdlib only, read-only."""
import json
import socket
import time
import ipaddress
import concurrent.futures
import argparse
import base64

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

# MACs taken from the supplied Home Assistant registry snapshot, to identify
# devices even if their multicast responses are filtered by the network.
MAC_BY_ID = {
    0x07980E72: "04:cf:8c:2c:fd:09",
    0x10D9AAA1: "44:23:7c:32:a6:58",
    0x13E3AFB2: "64:90:c1:86:31:85",
    0x13E3B798: "64:90:c1:86:39:6b",
    0x04A3DC76: "78:11:dc:ad:9c:2d",
    0x13E3B01D: "64:90:c1:86:31:f0",
    0x1819FC2F: "58:b6:23:3d:2b:c5",
    0x13F2052E: "64:90:c1:94:87:01",
    0x13E3B012: "64:90:c1:86:31:e5",
    0x13E3AFEE: "64:90:c1:86:31:c1",
    0x102FEF6C: "50:ec:50:95:8e:fc",
    0x13E3AF9F: "64:90:c1:86:3a:01",
    0x13E3B82E: "64:90:c1:86:31:72",
    0x12C66F8B: "5c:e5:0c:b4:30:06",
}
ID_BY_MAC = {mac: dev_id for dev_id, mac in MAC_BY_ID.items()}

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
    """Read only 3 properties and retry short-lived Yeelight TCP timeouts."""
    request = b'{"id":1,"method":"get_prop","params":["power","save_state","lan_ctrl","init_power_opt"]}\r\n'
    last_error = None
    for attempt in range(3):
        try:
            with socket.create_connection((ip, 55443), timeout=3.0) as conn:
                conn.settimeout(3.0)
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
                return [str(x) for x in (result + [''])[:4]]
            return ['ERR', str(data.get('error', 'Bad reply')), '-', '']
        except (OSError, ValueError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(0.5)
    raise OSError(f'3 попытки: {last_error}')

def scan_subnet(network):
    """Detect Yeelight LAN TCP responders, even when SSDP is blocked."""
    hosts = [str(ip) for ip in network.hosts()]
    def probe(ip):
        try:
            with socket.create_connection((ip, 55443), timeout=0.65):
                return ip
        except OSError:
            return None
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as executor:
        return sorted(filter(None, executor.map(probe, hosts)), key=ipaddress.ip_address)


def arp_entries():
    """Resolve IP -> MAC from the local Linux ARP cache after TCP scan."""
    result = {}
    try:
        with open('/proc/net/arp', encoding='ascii') as arp:
            next(arp, None)
            for line in arp:
                fields = line.split()
                if len(fields) >= 4 and fields[3] != '00:00:00:00:00:00':
                    result[fields[0]] = fields[3].lower()
    except OSError:
        pass
    return result


def display_name(raw):
    if not raw or raw in ('0', 'None', 'null'):
        return ''
    try:
        name = base64.b64decode(raw, validate=True).decode('utf-8')
        if name.strip() and name.isprintable():
            return name.strip()
    except (ValueError, UnicodeError):
        pass
    return raw if raw.isprintable() else ''


def main():
    parser = argparse.ArgumentParser(description='Read Yeelight power/save_state over LAN')
    parser.add_argument('--ip', help='Опросить только один IP, без сканирования')
    parser.add_argument('--subnet', default='192.168.2.0/24',
                        help='IPv4 subnet to scan (default 192.168.2.0/24)')
    args = parser.parse_args()
    if args.ip:
        ip = str(ipaddress.IPv4Address(args.ip))
        try:
            power, saved, lan, init = props(ip)
            print(f'{ip}: power={power}, save_state={saved}, lan_ctrl={lan}, init_power_opt={init}')
        except OSError as exc:
            print(f'{ip}: ERROR {exc}')
        return
    network = ipaddress.IPv4Network(args.subnet, strict=False)
    if network.num_addresses > 1024:
        parser.error('Укажи подсеть не шире /22')

    print(f'Сканирование {network}: TCP 55443 ...', flush=True)
    reachable = scan_subnet(network)
    if not reachable:
        print('На TCP 55443 нет доступных ламп. Проверь подсеть и LAN Control.')
        return

    print(f'Доступных TCP адресов: {len(reachable)}. Определяю названия ...', flush=True)
    discovered_by_ip = {ip: id_ for id_, ip in discover(3).items()}
    neighbor_macs = arp_entries()
    found_names = set()
    unknown_count = 0

    print(f'{"Лампа":27} {"IP":16} {"Power":7} {"Save":5} {"LAN":5} {"Init":6} Способ')
    print('-' * 94)
    for ip in reachable:
        known_id = discovered_by_ip.get(ip)
        source = 'ID' if known_id in LAMPS else 'MAC'
        if known_id not in LAMPS:
            known_id = ID_BY_MAC.get(neighbor_macs.get(ip, ''))
        try:
            power, saved, lan, init = props(ip)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            power, saved, lan, init = 'ERROR', '-', '-', '-'
            error_info = str(exc)[:50]
        else:
            error_info = ''

        name = LAMPS.get(known_id)
        if name:
            found_names.add(name)
        else:
            unknown_count += 1
            name = f'Yeelight ({ip})'
            source = 'IP/Name'
        print(f'{name[:27]:27} {ip:16} {power:7} {saved:5} {lan:5} {init:6} {source}'
              + (f' {error_info}' if error_info else ''))

    print(f'\nTCP найдено: {len(reachable)}; по реестрам сопоставлено: '
          f'{len(found_names)}/{len(LAMPS)}; неопознано: {unknown_count}')
    if len(found_names) != len(LAMPS):
        print('Без подтверждённого соответствия IP (могут быть среди неопознанных):')
        print(', '.join(name for name in LAMPS.values() if name not in found_names))
    print('Save = автоматическое сохранение состояния (0 выкл., 1 вкл.),'
          ' не настройка Auto on after power loss.')
    print('Init = init_power_opt: 1 — автовключение, 2 — не включать (проверено для ceil29).')
    print('Только чтение: скрипт ничего не изменяет.')


if __name__ == '__main__':
    main()
