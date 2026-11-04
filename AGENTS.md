# Project working preferences

- Keep all changes local. Do not stage, commit, push, create pull requests, or publish changes unless the user explicitly requests that action again.
- Do not read or print real API keys from `.env`. Use local test fixtures for model integration checks unless the user explicitly authorizes a real model request.
- Preserve the user's active local development services and data. Coordinate service restarts and avoid switching to Docker implicitly.
