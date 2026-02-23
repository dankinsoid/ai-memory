#!/usr/bin/env bash
# One-time server provisioning for ai-memory on Ubuntu 22.04/24.04
# Run as root: ssh root@YOUR_IP 'bash -s' < scripts/server-init.sh
set -euo pipefail

echo "=== ai-memory server init ==="

# 1. System updates
apt-get update && apt-get upgrade -y

# 2. Create deploy user
if ! id -u deploy &>/dev/null; then
  adduser --disabled-password --gecos "" deploy
  usermod -aG sudo deploy
  echo "deploy ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/deploy
fi

# 3. SSH key setup — copy root's authorized_keys to deploy
mkdir -p /home/deploy/.ssh
cp /root/.ssh/authorized_keys /home/deploy/.ssh/
chown -R deploy:deploy /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys

# 4. Install Docker
if ! command -v docker &>/dev/null; then
  curl -fsSL https://get.docker.com | sh
  usermod -aG docker deploy
  systemctl enable docker
  systemctl start docker
fi

docker compose version

# 5. Firewall
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 8080/tcp
ufw allow 3000/tcp
ufw --force enable
echo "Firewall:"
ufw status verbose

# 6. Clone repository
APP_DIR="/opt/ai-memory"
if [ ! -d "$APP_DIR" ]; then
  git clone https://github.com/dankinsoid/ai-memory.git "$APP_DIR"
  chown -R deploy:deploy "$APP_DIR"
fi

# 7. Production .env
ENV_FILE="$APP_DIR/.env"
if [ ! -f "$ENV_FILE" ]; then
  cat > "$ENV_FILE" << 'EOF'
GF_ADMIN_PASSWORD=CHANGE_ME
API_TOKEN=CHANGE_ME
EOF
  chown deploy:deploy "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo ">>> Edit $ENV_FILE and set GF_ADMIN_PASSWORD and API_TOKEN"
fi

# 8. Swap (2GB safety net)
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
  sysctl vm.swappiness=10
  echo 'vm.swappiness=10' >> /etc/sysctl.conf
fi

# 9. First deploy
cd "$APP_DIR"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
$COMPOSE pull
$COMPOSE build --parallel
$COMPOSE up -d

echo ""
echo "=== Init complete ==="
echo "First boot takes ~3 min (embedding model download)."
echo "Check: ssh deploy@$(hostname -I | awk '{print $1}') 'cd /opt/ai-memory && docker compose ps'"
echo ""
echo "NEXT STEPS:"
echo "  1. Edit /opt/ai-memory/.env — set GF_ADMIN_PASSWORD and API_TOKEN"
echo "  2. GitHub secrets: DO_HOST, DO_USER=deploy, DO_SSH_KEY"
echo "  3. Push to main → auto deploy"
