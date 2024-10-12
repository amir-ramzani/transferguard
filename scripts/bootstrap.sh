#!/usr/bin/env bash
# Installs everything TransferGuard needs to run locally, then checks it.
#   Ubuntu / Debian: Docker Engine + Compose plugin (official Docker apt repo), JDK 21, make, curl, jq
#   macOS:           prints the Homebrew commands (Docker Desktop can't be installed unattended)
#
# Usage:  ./scripts/bootstrap.sh          install what is missing, then check
#         ./scripts/bootstrap.sh --check  only check
# Safe to run more than once: anything already installed is skipped.
set -euo pipefail

ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
miss() { printf '  \033[31mmissing\033[0m %s\n' "$*"; }
info() { printf '\033[1m==> %s\033[0m\n' "$*"; }

check() {
  local failed=0
  info "Checking prerequisites"
  if command -v docker >/dev/null 2>&1; then ok "docker $(docker --version | awk '{print $3}' | tr -d ,)"; else miss "docker"; failed=1; fi
  if docker compose version >/dev/null 2>&1; then ok "docker compose $(docker compose version --short)"; else miss "docker compose plugin"; failed=1; fi
  if docker info >/dev/null 2>&1; then
    ok "docker daemon reachable without sudo"
    local mem_gb
    mem_gb=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 / 1024 ))
    if (( mem_gb >= 6 )); then ok "docker memory ${mem_gb} GB"; else miss "docker memory ${mem_gb} GB (6 GB or more recommended)"; fi
  else
    miss "docker daemon not reachable as $(whoami) (not running, or log out and back in after being added to the docker group)"
    failed=1
  fi
  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE 'version "(2[1-9]|[3-9][0-9])'; then
    ok "java $(java -version 2>&1 | head -1 | awk -F'"' '{print $2}')"
  else
    miss "JDK 21 or newer on PATH"; failed=1
  fi
  for tool in make curl jq; do
    if command -v "$tool" >/dev/null 2>&1; then ok "$tool"; else miss "$tool"; failed=1; fi
  done
  return $failed
}

# A broken third-party repository (a PPA without a release for this Ubuntu version) makes
# `apt-get update` fail even though the packages we need are fine; warn instead of aborting.
apt_update() {
  if ! sudo apt-get update -y; then
    printf '\033[33mwarning\033[0m apt-get update reported errors (often a stale PPA in /etc/apt/sources.list.d).\n'
    printf '        Continuing; if an install below fails, remove or fix that repository and re-run.\n'
  fi
}

install_debian() {
  local distro codename arch
  # shellcheck disable=SC1091
  . /etc/os-release
  distro=$ID
  [[ "$distro" == "ubuntu" || "$distro" == "debian" ]] || distro=${ID_LIKE%% *}
  codename=${UBUNTU_CODENAME:-$VERSION_CODENAME}
  arch=$(dpkg --print-architecture)

  info "Installing base tools (make, curl, jq, JDK 21)"
  apt_update
  sudo apt-get install -y ca-certificates curl gnupg make jq openjdk-21-jdk-headless

  if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    info "Installing Docker Engine and the Compose plugin from download.docker.com"
    # Remove distro packages that conflict with Docker's own.
    for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
      sudo apt-get remove -y "$pkg" >/dev/null 2>&1 || true
    done
    sudo install -m 0755 -d /etc/apt/keyrings
    sudo curl -fsSL "https://download.docker.com/linux/${distro}/gpg" -o /etc/apt/keyrings/docker.asc
    sudo chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=${arch} signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${distro} ${codename} stable" \
      | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
    apt_update
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  else
    info "Docker already installed"
  fi

  sudo systemctl enable --now docker
  if ! id -nG "$USER" | grep -qw docker; then
    info "Adding $USER to the docker group (so Jib and make can use Docker without sudo)"
    sudo usermod -aG docker "$USER"
    NEED_RELOGIN=1
  fi
}

install_macos() {
  info "macOS: install with Homebrew, then start Docker Desktop and give it 6 GB or more of memory"
  echo "  brew install --cask docker"
  echo "  brew install openjdk@21 jq"
  echo "  sudo ln -sfn \$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk"
}

NEED_RELOGIN=0
if [[ "${1:-}" != "--check" ]]; then
  case "$(uname -s)" in
    Linux)
      if command -v apt-get >/dev/null 2>&1; then install_debian
      else echo "Unsupported Linux distribution: install Docker Engine, the Compose plugin and JDK 21 by hand."; fi ;;
    Darwin) install_macos ;;
    *) echo "Unsupported OS $(uname -s)"; exit 1 ;;
  esac
fi

if check; then
  info "All set. Next: cp .env.example .env && make up && make demo"
else
  if (( NEED_RELOGIN )); then
    info "You were just added to the docker group. Log out and back in (or run: newgrp docker), then run: ./scripts/bootstrap.sh --check"
  fi
  exit 1
fi
