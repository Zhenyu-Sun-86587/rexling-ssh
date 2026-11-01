package dev.shulhi.rex

import java.util.Locale

enum class ProgramKind { TERMINAL, CLAUDE, CODEX, NEOVIM, VIM, GIT, PYTHON, NODE, SSH, TMUX, MONITOR }
data class ProgramIdentity(val kind: ProgramKind, val label: String) {
    val title: String get() = when(kind) {
        ProgramKind.CLAUDE -> "claude"; ProgramKind.CODEX -> "codex"; ProgramKind.NEOVIM -> "nvim"
        ProgramKind.VIM -> "vim"; ProgramKind.GIT -> "git"; ProgramKind.PYTHON -> "python"
        ProgramKind.NODE -> "node"; ProgramKind.SSH -> "ssh"; ProgramKind.TMUX -> "tmux"
        ProgramKind.MONITOR -> "monitor"; ProgramKind.TERMINAL -> label
    }
    companion object {
        val Terminal = ProgramIdentity(ProgramKind.TERMINAL, "Terminal")
        fun resolve(candidates: List<String>): ProgramIdentity {
            for (candidate in candidates) {
                val path = candidate.replace('\\', '/').lowercase(Locale.ROOT)
                val name = path.substringAfterLast('/').trimStart('-').removeSuffix(".exe").removeSuffix(".cmd")
                val kind = when {
                    name in listOf("claude", "claude-code", "claude-code.js") || path.contains("/@anthropic-ai/claude-code/") -> ProgramKind.CLAUDE
                    name in listOf("codex", "codex.js", "@openai/codex") || path.contains("/@openai/codex/") || Regex("codex-(aarch64|x86_64)-.*").matches(name) -> ProgramKind.CODEX
                    name in listOf("nvim", "neovim") -> ProgramKind.NEOVIM
                    name in listOf("vim", "vi", "gvim") -> ProgramKind.VIM
                    name in listOf("git", "lazygit", "tig") -> ProgramKind.GIT
                    name == "python" || Regex("python[23](\\.\\d+)?").matches(name) || name == "ipython" -> ProgramKind.PYTHON
                    name in listOf("node", "nodejs", "node.js", "npm", "pnpm", "yarn", "bun", "deno") -> ProgramKind.NODE
                    name in listOf("ssh", "mosh", "mosh-client") -> ProgramKind.SSH
                    name in listOf("tmux", "zellij", "screen") -> ProgramKind.TMUX
                    name in listOf("top", "htop", "btop") -> ProgramKind.MONITOR
                    else -> null
                }
                if (kind != null) return ProgramIdentity(kind, when(kind) {
                    ProgramKind.CLAUDE -> "Claude Code"; ProgramKind.CODEX -> "Codex"
                    ProgramKind.NEOVIM -> "Neovim"; ProgramKind.VIM -> "Vim"; ProgramKind.GIT -> "Git"
                    ProgramKind.PYTHON -> "Python"; ProgramKind.NODE -> "JavaScript runtime"
                    ProgramKind.SSH -> "Remote shell"; ProgramKind.TMUX -> "Terminal multiplexer"
                    ProgramKind.MONITOR -> "System monitor"; ProgramKind.TERMINAL -> "Terminal"
                })
            }
            val name = candidates.firstOrNull()?.replace('\\', '/')?.substringAfterLast('/')?.trimStart('-')
                ?.filterNot { it.isISOControl() || Character.getType(it)==Character.FORMAT.toInt() }?.take(40).orEmpty()
            return ProgramIdentity(ProgramKind.TERMINAL, name.ifBlank { "Terminal" })
        }
    }
}
