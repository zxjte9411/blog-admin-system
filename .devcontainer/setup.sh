#!/usr/bin/env bash

set -euo pipefail

apt-get update
apt-get install -y build-essential

# Install Homebrew
if ! command -v brew >/dev/null 2>&1; then
  NONINTERACTIVE=1 /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

export HOMEBREW_NO_ASK=1
eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv bash)"
brew update --force --quiet
brew install gcc
brew bundle --file=".devcontainer/Brewfile"
brew update && brew upgrade -y && brew cleanup --prune="all"
