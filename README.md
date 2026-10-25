# Runway

Runway is a privacy-first native Android workspace built with Kotlin, Jetpack Compose (Material 3), Room, DataStore, Google Tink (Android Keystore), and OkHttp SSE streaming.

## Features

- **Multi-Provider Chat**: Real-time SSE streaming across OpenAI-compatible gateways, Anthropic (`/v1/messages`), and Google Gemini (`streamGenerateContent`), with per-message token and estimated cost footers plus conversation-level usage totals.
- **Compare Mode**: Run prompts concurrently across 2 or 3 models side-by-side with latency, token, and cost metrics.
- **Connectors & Remote MCP**: Discover and connect remote Model Context Protocol (MCP) servers over Streamable HTTP or SSE, including a GitHub MCP preset, with per-connector and per-tool approval controls (`Always`, `Ask`, `Never`) and hardware-backed Tink token encryption.
- **Sandboxed Code Execution**: Isolated JavaScript `run_code` tool with execution timeout, memory cap, 10 KB output truncation, and user approval controls.
- **Skills & Prompt Library**: Markdown `SKILL.md` instruction packs with frontmatter parsing, auto-selection, and reusable prompt templates.
- **Studios & Analytics**: Image and video generation studios and a local usage tracker.

## Build

- **Android Studio**: Open the project in Android Studio and select **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
- **GitHub Actions**: Push to the `main` or `master` branch (or trigger the **Build APK** workflow manually in the **Actions** tab) to compile the debug APK and download it from the workflow's build artifacts.
