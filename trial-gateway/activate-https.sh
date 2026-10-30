#!/usr/bin/env bash
# Run as root after the upstream firewall permits public TCP 80 and 443.
# The deployed app and quota database remain in place; this script contains no keys.
set -euo pipefail
test "$(id -u)" = 0
install -d -m 755 /var/www/xfriend-acme/.well-known/acme-challenge
/opt/xfriend-trial/venv/bin/certbot certonly \
  --non-interactive --agree-tos --register-unsafely-without-email \
  --preferred-profile shortlived --webroot -w /var/www/xfriend-acme \
  --ip-address 113.219.170.192 --cert-name xfriend-trial-ip \
  --deploy-hook 'nginx -t && systemctl reload nginx'
install -m 644 /opt/xfriend-trial/nginx.conf /etc/nginx/sites-available/xfriend-trial
nginx -t
systemctl reload nginx
cat > /etc/systemd/system/xfriend-trial-cert-renew.service <<'UNIT'
[Unit]
Description=Renew Xfriend short-lived IP certificate
[Service]
Type=oneshot
ExecStart=/opt/xfriend-trial/venv/bin/certbot renew --quiet --cert-name xfriend-trial-ip
UNIT
cat > /etc/systemd/system/xfriend-trial-cert-renew.timer <<'UNIT'
[Unit]
Description=Check Xfriend IP certificate twice daily
[Timer]
OnCalendar=*-*-* 00,12:00:00
RandomizedDelaySec=1800
Persistent=true
[Install]
WantedBy=timers.target
UNIT
systemctl daemon-reload
systemctl enable --now xfriend-trial-cert-renew.timer
curl --fail --silent --show-error https://113.219.170.192/health
