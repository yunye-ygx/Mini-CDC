# Happy + Codex Remote Access Design

## Goal

Allow the user to leave the Windows PC powered on and logged in, then take over an active Codex CLI session from the Happy mobile app in a short time without requiring the PC to be physically nearby.

## Scope

This design covers:
- Official Happy relay mode only
- A Windows machine that is already powered on and logged in
- Codex CLI sessions launched from the PC and then controlled from the phone
- Power settings needed to keep the session reachable

This design does not cover:
- Self-hosted Happy relay
- Remote power-on, BIOS wake, or unattended Windows login
- Full desktop remote control tools

## Recommended Approach

Install the Happy CLI on the Windows machine, pair it once with the Happy mobile app using the official relay, verify that Happy can launch the locally installed Codex CLI, and disable Windows sleep so the active terminal session remains reachable while the machine is left unattended.

## Components

- `happy` CLI: broker for mobile-to-terminal control over the official relay
- `codex` CLI: local terminal workload that Happy will launch and hand off to the phone
- Happy mobile app: client used to pair with and control the running session
- Windows power configuration: prevents sleep from interrupting the session

## Flow

1. Verify `node`, `npm`, and `codex` are already available on the Windows machine.
2. Install the `happy` CLI globally with npm.
3. Start a Happy-managed Codex session from the PC.
4. Pair the phone to the PC through Happy's official relay flow.
5. From the phone, reconnect to the running Happy/Codex session.
6. Keep the PC awake by disabling sleep while allowing the machine to remain logged in.

## Error Handling

- If `happy` is not on `PATH` after installation, re-open the terminal or use the npm global bin path directly.
- If phone pairing fails, re-run the pairing flow from a fresh terminal session and confirm both devices have internet access.
- If the session drops after inactivity, inspect Windows sleep and hibernation settings before troubleshooting Happy itself.
- If `happy` cannot find `codex`, use the explicit path to the installed Codex CLI binary.

## Verification

Success requires all of the following:
- `happy --version` succeeds on the PC
- `codex --version` succeeds on the PC
- A Happy-managed Codex session starts successfully from the PC
- The phone can attach to that session and issue at least one command
- The session remains reachable after the PC is left idle for several minutes

## Implementation Notes

The implementation should prefer the smallest possible machine changes:
- Install only the Happy CLI
- Change only sleep-related power settings
- Avoid adding auto-start or background services in this phase
