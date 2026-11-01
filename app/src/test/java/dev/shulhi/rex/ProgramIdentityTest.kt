package dev.shulhi.rex

import org.junit.Assert.*
import org.junit.Test

class ProgramIdentityTest {
    @Test fun recognizesAgentsInsideInterpretersAndNativeInstallations() {
        assertEquals(ProgramKind.CLAUDE,ProgramIdentity.resolve(listOf("claude-code","node")).kind)
        assertEquals(ProgramKind.CLAUDE,ProgramIdentity.resolve(listOf("/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js","node")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("/usr/bin/codex-aarch64-unknown-linux-musl")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("C:\\tools\\codex.exe")).kind)
        assertEquals(ProgramKind.CODEX,ProgramIdentity.resolve(listOf("/usr/lib/node_modules/@openai/codex/bin/codex.js","node")).kind)
    }
    @Test fun usesExecutableMetadataAndFallsBackWithoutGuessingFromTitles() {
        assertEquals(ProgramKind.NEOVIM,ProgramIdentity.resolve(listOf("/usr/local/bin/nvim")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("my-nvim-helper")).kind)
        assertEquals(ProgramKind.TERMINAL,ProgramIdentity.resolve(listOf("/projects/claude/custom-task")).kind)
        assertEquals(ProgramKind.PYTHON,ProgramIdentity.resolve(listOf("/tmp/custom.py","python3.12")).kind)
        assertEquals(ProgramIdentity.Terminal,ProgramIdentity.resolve(emptyList()))
        assertEquals("fish",ProgramIdentity.resolve(listOf("-fish")).label)
    }
    @Test fun headerDirectoryIsOptionalAndStripsInvisibleFormatting() {
        val state=AppState.parse("""{"directories":{"a":"~/code/project","b":"/tmp/\u202ehidden\n"}}""")
        assertEquals("~/code/project",state.directories["a"])
        assertEquals("/tmp/hidden",state.directories["b"])
        assertTrue(AppState.parse("{}").directories.isEmpty())
        assertEquals("claude",ProgramIdentity.resolve(listOf("claude-code")).title)
    }
    @Test fun programMetadataSurvivesAnOrdinaryStateUpdateWithoutInventingPrograms() {
        val state=AppState.parse("""{"programs":{"a":["nvim"],"b":["claude-code","node"]},"windows":[{"id":"w","label":"codex","blocks":[{"id":"a","label":"shell"},{"id":"c","label":"claude"}]}]}""")
        assertEquals(ProgramKind.NEOVIM,state.programs["a"]?.kind)
        assertEquals(ProgramKind.CLAUDE,state.programs["b"]?.kind)
        assertNull(state.programs["c"])
        assertTrue(AppState.parse("{}").programs.isEmpty())
    }
}
