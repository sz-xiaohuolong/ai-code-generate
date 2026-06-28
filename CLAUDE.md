# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

AI-driven code generation platform. Users register apps with a prompt, choose a generation type (single HTML or multi-file), and the AI generates complete frontend code. Generated code is saved to disk and servable as static resources. Chat history is persisted for conversation continuity.

- **Backend**: Java 21, Spring Boot 3.5, Maven, MyBatis-Flex ORM, LangChain4j for LLM integration
- **Frontend**: Vue 3, TypeScript, Vite, Ant Design Vue, Pinia
- **Database**: MySQL (`ai_code_generate`), Redis (session + AI chat memory)

## Build & Run

### Backend (from repo root)

```bash
# Start (requires MySQL + Redis running locally)
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Package as JAR
./mvnw package -DskipTests
```

The server runs on **port 8123** with context path `/api`. API docs (Knife4j/Swagger) at `http://localhost:8123/api/doc.html`.

Local overrides go in `src/main/resources/application-local.yml` (gitignored).

### Frontend (from `ai-code-frontend/`)

```bash
yarn install        # or npm install
yarn dev            # Vite dev server with HMR
yarn build          # type-check + production build
yarn lint           # ESLint with auto-fix
yarn format         # Prettier
yarn openapi2ts     # regenerate TypeScript API client from backend OpenAPI spec
```

## Architecture

### Backend Layers (`src/main/java/com/xhl/aicodegenerate/`)

```
controller/         REST endpoints
service/            Business logic interfaces
service/impl/       Service implementations
mapper/             MyBatis-Flex data access (DAO)
entity/             Database entities (User, App, ChatHistory)
model/dto/          Request bodies (user/, app/, chat/)
model/vo/           Response objects
model/enums/        CodeGenTypeEnum, UserRoleEnum, ChatHistoryMessageTypeEnum
common/             BaseResponse, ResultUtils, PageRequest, DeleteRequest
exception/          BusinessException, ErrorCode, GlobalExceptionHandler
config/             Spring config (CORS, JSON, Redis)
annotation/         @AuthCheck — role-based access control
aop/                AuthInterceptor — enforces @AuthCheck
ai/                 LLM integration (AiCodeGeneratorService + factory, chat memory)
core/               Code generation orchestration (parser + file saver pipelines)
constant/           Static constants
generator/          MyBatis-Flex code generator utility
```

### Code Generation Pipeline

The AI code generation flow follows a strategy/chain pattern:

1. `AiCodeGeneratorFacade` — entry point, orchestrates the full pipeline
2. `AiCodeGeneratorServiceFactory` — selects the LLM service by `CodeGenTypeEnum`
3. LLM call with system prompts from `src/main/resources/prompt/` (HTML vs multi-file)
4. `CodeParserExecutor` — delegates to `CodeParser` implementation based on `CodeGenTypeEnum`:
   - `HtmlCodeParser` — extracts single HTML from LLM response
   - `MultiFileCodeParser` — splits response into index.html, style.css, script.js
5. `CodeFileSaverExecutor` — delegates to `CodeFileSaverTemplate` subclass:
   - Saves to `tmp/code_output/<codeGenType>_<appId>/`
   - Deploys to `tmp/code_deploy/<deployKey>/`
6. `StaticResourceController` serves deployed files at `/api/static/**`

### Frontend Routing & Auth

- `router/index.ts` contains a navigation guard that checks login state and admin role
- `@AuthCheck` annotation on backend controllers enforces role-based access (user/admin)
- Session stored in Redis (30-day TTL)

### API Client Generation

The frontend's `src/api/` directory is auto-generated from the backend OpenAPI spec. After backend changes, run `yarn openapi2ts` in `ai-code-frontend/` to regenerate TypeScript clients.

### Key Dependencies

- **LangChain4j** — LLM abstraction layer (OpenAI-compatible API via `langchain4j-open-ai`)
- **MyBatis-Flex** — lightweight MyBatis ORM with code generation support
- **Knife4j** — Swagger UI for Chinese-localized API docs
- **Hutool** — general-purpose Java utility library

## Database

Tables: `user` (accounts, roles), `app` (registered AI apps with prompts and config), `chat_history` (conversation messages by app/user).

Schema in `sql/create_table.sql`. MySQL connection and Redis config in `application.yml`.
