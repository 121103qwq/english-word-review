# Codex instructions

- Keep changes minimal and focused on the requested task.
- Preserve the existing Vite + TypeScript, Tauri 2, and Capacitor 8 structure. Do not add frameworks or dependencies unless the task requires them.
- Do not put personal vocabulary, learning progress, MP3 files, credentials, tokens, or other user data in this public code repository. The separate data repository is not part of normal code changes.
- Do not refresh or replace dictionary source data unless the task explicitly asks for it.
- Prefer existing project patterns and scripts over introducing new tooling.

## Setup

```bash
npm ci
```

## Validation

For normal code changes, run:

```bash
npm run check
npm test
npm run build
```

Only run Windows, Android, or release packaging commands when the task specifically requires those outputs and the required toolchain is available.
