#!/usr/bin/env python3
"""Yeelight LAN: detect devices and read properties. Python stdlib only, read-only."""
import json
import socket
import time
import ipaddress
import concurrent.futures
import argparse

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


def read_init_power_opt(ip):
    """Only read Yeelight boot-power setting. Never modify the lamp."""
    request = b'{"id":1,"method":"get_prop","params":["init_power_opt"]}\r\n'
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
            response = json.loads(bytes(buffer).split(b'\n', 1)[0].strip())
            if 'error' in response:
                return 'ERROR: ' + str(response['error'])
            result = response.get('result')
            if not isinstance(result, list) or len(result) != 1:
                return 'ERROR: invalid result'
            value = result[0]
            if value is None or value == '' or value == 'null':
                return 'n/a'
            return str(value)
        except (OSError, ValueError) as exc:
            last_error = exc
            if attempt < 2:
                time.sleep(0.5)
    return f'ERROR: {last_error}'


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


def main():
    parser = argparse.ArgumentParser(
        description='Read init_power_opt on Yeelight lamps (no desk lamps; read-only)'
    )
    parser.add_argument('--ip', help='Read a single IP without discovery')
    parser.add_argument('--subnet', default='192.168.2.0/24',
                        help='IPv4 subnet to scan (default: 192.168.2.0/24)')
    args = parser.parse_args()
    if args.ip:
        ip = str(ipaddress.IPv4Address(args.ip))
        print(f'{ip}: init_power_opt={read_init_power_opt(ip)}')
        return

    network = ipaddress.IPv4Network(args.subnet, strict=False)
    if network.num_addresses > 1024:
        parser.error('Subnet must be /22 or smaller')

    # A desk lamp is intentionally excluded, including from parameter reads.
    excluded_ids = {0x102FEF6C}
    targets = {device_id: name for device_id, name in LAMPS.items()
               if device_id not in excluded_ids}
    print(f'Поиск Yeelight в {network} (TCP 55443)...', flush=True)
    reachable = scan_subnet(network)
    if not reachable:
        print('Доступных устройств нет; проверь сеть и LAN Control.')
        return

    discovered_by_ip = {ip: device_id for device_id, ip in discover(3).items()}
    neighbor_macs = arp_entries()
    results = {}
    other = 0

    for ip in reachable:
        device_id = discovered_by_ip.get(ip)
        if device_id not in LAMPS:
            device_id = ID_BY_MAC.get(neighbor_macs.get(ip, ''))
        if device_id in excluded_ids:
            continue
        if device_id not in targets:
            other += 1
            continue
        results[device_id] = (ip, read_init_power_opt(ip))

    print(f'\n{"Лампа":26} {"IP":16} {"init_power_opt":17} Результат')
    print('-' * 79)
    ok = enabled = errors = missing = 0
    for device_id, name in targets.items():
        entry = results.get(device_id)
        if entry is None:
            missing += 1
            print(f'{name:26} {"—":16} {"—":17} Не найдена')
            continue
        ip, value = entry
        if value == '2':
            ok += 1
            label = 'Запрет включения'
        elif value == '1':
            enabled += 1
            label = 'Автовключение!'
        else:
            errors += 1
            label = 'Не удалось прочитать' if value.startswith('ERROR') else 'Не поддерживается'
        print(f'{name:26} {ip:16} {value[:17]:17} {label}')

    print(f'\nЗапрет (2): {ok}; Автовключение (1): {enabled}; '
          f'нет значения: {errors}; не найдены: {missing}.')
    if other:
        print(f'Не сопоставлено с реестром: {other} устройство(а).')
    print('Для ceil29 подтверждено: 1 = автоматическое включение, 2 = запрет.')
    print('Настольные лампы исключены; отправляются только команды чтения.')


if __name__ == '__main__':
    main()
