# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

@AGENTS.md

## Claude Code only

- In the Claude Code sandbox, Gradle downloads fail with `UnknownHostException` because the JVM ignores the sandbox proxy. Run downloading Gradle commands with the sandbox disabled.
- For worktree isolation, Claude Code's built-in worktree support is fine. Follow the claims, device-lock and no-push rules in `AGENTS.md` exactly as Antigravity does.
