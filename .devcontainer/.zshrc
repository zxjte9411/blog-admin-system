export LANG='en_US.UTF-8'
export LANGUAGE='en_US:en'
export LC_ALL='en_US.UTF-8'
[ -z "" ] && export TERM=xterm

##### Zsh/Oh-my-Zsh Configuration
export ZSH="/root/.oh-my-zsh"

ZSH_THEME="powerlevel10k/powerlevel10k"
plugins=(git ssh-agent zsh-autosuggestions zsh-completions zsh-syntax-highlighting sdk docker docker-compose mvn brew )


source $ZSH/oh-my-zsh.sh
POWERLEVEL9K_SHORTEN_STRATEGY="truncate_to_last"
POWERLEVEL9K_LEFT_PROMPT_ELEMENTS=(user dir vcs status)
POWERLEVEL9K_RIGHT_PROMPT_ELEMENTS=()
POWERLEVEL9K_STATUS_OK=false
POWERLEVEL9K_STATUS_CROSS=true

eval "$(/home/linuxbrew/.linuxbrew/bin/brew shellenv)"

# opencode 
export EDITOR="code --wait"

setopt APPEND_HISTORY          # 追加寫入，不覆蓋
setopt INC_APPEND_HISTORY      # 每次執行後立即寫入
setopt HIST_IGNORE_DUPS        # 新指令若和前一筆相同就不記
setopt HIST_IGNORE_ALL_DUPS    # 移除舊重複，保留最新
setopt HIST_REDUCE_BLANKS      # 壓縮多餘空白
setopt HIST_SAVE_NO_DUPS       # 寫入檔案時去重複
setopt HIST_FIND_NO_DUPS       # 搜尋歷史時略過重複

zshaddhistory() {
  emulate -L zsh
  setopt extendedglob
  local cmd="${1%%$'\n'}"
  cmd="${cmd##[[:space:]]#}"  # 去前導空白
  case "$cmd" in
    (cd|cd\ *|ls|ls\ *|l|l\ *) return 1 ;;  # 不寫入 history
  esac
  return 0
}

# >>> oh-my-opencode-slim background subagents >>>
export OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=true
# <<< oh-my-opencode-slim background subagents <<<