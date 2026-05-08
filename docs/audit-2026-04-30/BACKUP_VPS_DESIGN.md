# BACKUP_VPS_DESIGN — дизайн второго VPS как «слепого моста» до Main VPS

**Дата:** 2026-04-30 · **Назначение:** запасной маршрут для App когда Main VPS (45.12.239.5) недоступен

> Терминология: «backup VPS» / «relay» вместо «Yandex VPS», т.к. провайдер выберется по результатам тестов скорости. Оригинальное ТЗ называет узел Yandex Relay — в этом доке используется generic.

## 1. Принципы (требования из ТЗ)

| Правило | Реализация |
|---|---|
| Backup VPS НЕ является backend | Только TCP/TLS forward на Main VPS |
| НЕ ходит в Cloudflare напрямую | Firewall outbound = только Main VPS:443 |
| НЕ знает Cloudflare URL | Нет упоминаний CF в configs / hostnames / scripts |
| НЕ знает Cloudflare tokens / D1 / R2 | Никаких CF API credentials на узле |
| Слепой к содержимому | TCP pass-through (предпочтительно) или TLS termination с E2E enforcement |
| Используется только если Main VPS недоступен | RouteManager на клиенте |
| Минимизация логов | Только status, bytes, request_time. Никаких URL/Authorization/body |
| Remote kill switch | systemd unit можно остановить через SSH или cron-flag |

## 2. Выбор провайдера (рекомендация)

| Провайдер | Цена | РФ-IP | Ограничения | Рекомендация |
|---|---|---|---|---|
| **Cloud.ru Smart Free** | 0₽ × 12 мес | да | 1 VM лимит, 2 vCPU/2GB | **первый выбор** — РФ Сбер, должен пройти DPI |
| Selectel cloud | ~150₽/мес | да | минимальный VPS | альтернатива если Cloud.ru не подойдёт |
| Timeweb cloud | ~120₽/мес | да | минимальный VPS | альтернатива |
| Yandex Cloud trial | $50/60 дней | да | потом платно (нужна РФ-карта) | НЕ рекомендую — не вечно free |
| Oracle Cloud Free | 0₽ навсегда | нет (Frankfurt/Phoenix) | 4 vCPU ARM | альтернатива если РФ блокирует Cloud.ru |

**Финальный выбор**: Cloud.ru Smart Free → fallback на Selectel 150₽ если регистрация Cloud.ru не пройдёт.

## 3. Архитектурный вариант — TCP pass-through (Mode A) ВЫБРАН

```
[App]  ──TLS handshake to api.otlhelper.com──▶  [Backup VPS]  ──pass-through bytes──▶  [Main VPS 45.12.239.5:443]
                                                  nginx stream                            (terminates TLS)
                                                  no log of body
```

### Что видит Backup VPS
- TCP bytes (зашифрованный TLS поток)
- Размер пакетов
- Source IP клиента
- Timing
- Destination IP/port: 45.12.239.5:443 (hard-coded в nginx config)

### Что НЕ видит
- TLS payload (нет термирования)
- HTTP method / URL / headers / body
- Action / login / token / payload
- Cloudflare URL (не упоминается нигде в конфиге)

### Минусы Mode A
- **DPI fingerprint**: backup VPS пересылает CF cert в TLS handshake (т.к. Main VPS отдаёт self-signed cert). DPI на участке `App → Backup VPS` видит **non-CF cert** (self-signed VPS cert) — это OK, не CF троттлинг. На участке `Backup VPS → Main VPS` — внутренний РФ-РФ трафик, не пересекает граничные DPI.
- **TLS handshake удваивается** (App→Backup→Main) — добавляет ~100ms RTT, но это всё ещё лучше блокировки.

### Почему НЕ Mode B (HTTPS reverse-proxy с двойной TLS termination)
- Backup VPS должен был бы иметь свой TLS cert (extra config).
- Backup VPS видел бы plaintext headers (X-OTL-Crypto, X-Request-Sig, sizes) — больше чем нужно.
- Сложнее firewall (DNS resolve to Main VPS, etc).

### Почему НЕ Mode C (WebSocket tunnel)
- Сложность реализации (custom proto, не nginx один config).
- Battery / connection management Android — больше overhead.
- Если нужен — отдельный долгий проект, не «чистый старт».

## 4. nginx config (TCP pass-through)

`/etc/nginx/nginx.conf` (минимальный):

```nginx
user www-data;
worker_processes auto;
pid /run/nginx.pid;
events { worker_connections 1024; }

# stream module — TCP-level forwarding
stream {
    log_format minimal '$remote_addr $status $bytes_sent $bytes_received $session_time';
    access_log /var/log/nginx/stream.log minimal;
    error_log /var/log/nginx/stream-error.log warn;

    upstream main_vps {
        server 45.12.239.5:443;  # ТОЛЬКО IP, никаких hostname/Cloudflare
    }

    server {
        listen 443;
        proxy_pass main_vps;
        proxy_timeout 90s;
        proxy_connect_timeout 5s;
        # Не resolve, не DNS, не TLS termination
    }
}

# Health endpoint на 80 (для RouteManager pre-flight)
http {
    access_log off;  # Health endpoint никогда не логируется
    server {
        listen 80;
        location = /health {
            return 200 "ok\n";
            add_header Content-Type text/plain;
        }
        location / { return 404; }
    }
}
```

## 5. Firewall (`ufw` или `iptables`)

```sh
# Default deny all
ufw default deny incoming
ufw default deny outgoing

# Inbound: только 22 (SSH), 80 (health), 443 (relay)
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp

# Outbound: только Main VPS:443 + DNS (для apt updates) + NTP
ufw allow out to 45.12.239.5 port 443
ufw allow out 53        # DNS
ufw allow out 123       # NTP
ufw allow out 80,443/tcp from any to 1.1.1.1   # Cloudflare DNS-over-HTTPS если нужен

ufw enable
```

После этого backup VPS **физически не может** обратиться к Cloudflare API / D1 / R2 / любым CF endpoints — outbound запрещён. Если кто-то взломает VPS — некуда податься.

## 6. SSH hardening

```sh
# /etc/ssh/sshd_config
PermitRootLogin prohibit-password   # только ssh-key
PasswordAuthentication no
PubkeyAuthentication yes
ChallengeResponseAuthentication no
UsePAM yes
X11Forwarding no
PrintMotd no
ClientAliveInterval 300
ClientAliveCountMax 2

# fail2ban
apt install -y fail2ban
# /etc/fail2ban/jail.local
[sshd]
enabled = true
maxretry = 3
findtime = 600
bantime = 1800
```

## 7. Health check endpoint

`GET http://<backup-ip>:80/health` → `200 OK ok\n`

Pre-flight RouteManager на клиенте делает короткий ping (3s timeout):
- Если 200 → готов к использованию.
- Иначе → не использовать.

Health endpoint специально на :80 (не :443) — не trigger TLS handshake, простой ping.

## 8. Remote kill switch

Если Backup VPS скомпрометирован / нужно отключить:

**Опция A — клиент-side flag** (предпочтительно):
- Worker D1 `feature_flags` table: `name='backup_vps_enabled'`, `enabled=0/1`.
- Клиент при `app_status` запросе получает флаг.
- Если выключен — `RouteManager` игнорирует backup IP в DNS lookup.
- Активация занимает 1 минуту (heartbeat tick).

**Опция B — server-side**:
- SSH на backup VPS → `systemctl stop nginx` → клиенты получают connection refused → автоматически на primary.

## 9. Deployment script (one-liner)

```sh
#!/bin/sh
# /Users/jon/Documents/HELPERS/deploy-backup-vps.sh
# Запускать ОДНОКРАТНО на свежем VPS с правами root

set -e
apt update && apt install -y nginx fail2ban ufw

cat > /etc/nginx/nginx.conf <<'NGINX'
# (содержимое из § 4 выше)
NGINX

ufw default deny incoming
ufw default deny outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow out to 45.12.239.5 port 443
ufw allow out 53
ufw allow out 123
ufw --force enable

cat > /etc/fail2ban/jail.local <<'F2B'
[sshd]
enabled = true
maxretry = 3
F2B

systemctl restart nginx fail2ban
echo "Backup VPS ready. Health: curl http://$(hostname -I | awk '{print $1}'):80/health"
```

## 10. Что должно быть на клиенте (RouteManager-связь)

См. `ROUTE_MANAGER_DESIGN.md`. Кратко:
- В `Secrets.kt` (Desktop) и `HttpClientFactory.kt` (Android) добавить `BACKUP_VPS_IP` (XOR-обфусц.).
- DNS lookup → `[primary, backup]`.
- Pre-flight: parallel ping primary `:443` (TLS) и backup `:80/health`.
- Healthier ставится первым.

## 11. Чего DOES NOT have backup VPS

❌ Никакого app кода — это просто nginx + ufw + fail2ban.
❌ Никаких credentials Cloudflare / GitHub / Worker.
❌ Никакого `dig api.otlhelper.com` (no DNS lookup в config — только IP literal).
❌ Никаких logs с user data — только bytes/status/timing.
❌ Никакой роли как «accelerator» — используется только при недоступности Main VPS.

## 12. Verification после deploy

```sh
# С Mac (read-only test):
curl --resolve api.otlhelper.com:443:<BACKUP_IP> -X POST https://api.otlhelper.com/api \
    -H 'Content-Type: application/json' \
    --data '{"action":"app_status","scope":"main"}' \
    --cacert ~/StudioProjects/OTLHelper2/desktop/src/main/resources/otl_vps_cert.pem

# Должен вернуть 200 + JSON с ok=true
# Если вернул timeout / connection refused — backup VPS неправильно настроен
```

```sh
# Проверить, что на backup VPS нет упоминаний Cloudflare:
ssh root@<BACKUP_IP> "grep -ri cloudflare /etc /opt /usr/local 2>/dev/null | head -5"
# Должно быть пусто (Cloudflare DNS опционально, но без credentials)

ssh root@<BACKUP_IP> "grep -ri otlhelper /etc 2>/dev/null"
# Допустимо: только IP literal в nginx.conf
# НЕ должно быть: api.otlhelper.com, workers.dev, токены
```

## 13. Откатная процедура (если backup VPS не работает)

1. На клиенте: feature_flag `backup_vps_enabled=0` в D1 → клиенты перестают добавлять fallback IP.
2. На VPS: `systemctl stop nginx` (если нужно срочно).
3. Клиенты молча работают только через Primary (как сейчас).
4. Никакой перерывной операции — у Primary не меняется ничего.
