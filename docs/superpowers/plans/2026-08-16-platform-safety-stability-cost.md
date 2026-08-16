# Platform Safety, Stability, and Cost Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Protect the AI SSE endpoint, reject unsafe prompts, bound unstable model behavior, and route classification calls to a low-cost model.

**Architecture:** A Redisson-backed annotation/AOP layer runs before the controller, LangChain4j guardrails run around model calls, and SSE errors are normalized for the Vue client. A separately configured OpenAI-compatible routing model isolates low-cost classification from code generation.

**Tech Stack:** Java 21, Spring Boot 3.5.13, Redisson 3.50.0, LangChain4j 1.15.0, Reactor, Vue 3, TypeScript.

## Global Constraints

- AI chat limit: 5 requests per user per 60 seconds.
- Rate limiter idle lifetime: 1 hour.
- Prompt maximum length: 1000 characters.
- Model retry limit: 3.
- Vue tool round-trip limit: 20.
- Routing model: qwen-turbo with at most 100 output tokens.
- Preserve real-time streaming by not applying output guardrails to streaming methods.
- Never commit API credentials.

---

### Task 1: Redisson distributed rate limiting

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/com/xhl/aicodegenerate/exception/ErrorCode.java`
- Create: `src/main/java/com/xhl/aicodegenerate/ratelimit/config/RedissonConfig.java`
- Create: `src/main/java/com/xhl/aicodegenerate/ratelimit/enums/RateLimitType.java`
- Create: `src/main/java/com/xhl/aicodegenerate/ratelimit/annotation/RateLimit.java`
- Create: `src/main/java/com/xhl/aicodegenerate/ratelimit/aspect/RateLimitAspect.java`
- Test: `src/test/java/com/xhl/aicodegenerate/ratelimit/aspect/RateLimitAspectTest.java`

**Interfaces:**
- Produces: `@RateLimit`, `RateLimitType`, and a Spring-managed `RedissonClient`.

- [ ] Write aspect tests for allowed and rejected permits plus user/IP fallback key generation.
- [ ] Run the test and verify it fails because rate-limit production types do not exist.
- [ ] Add Redisson, configuration, error code, enum, annotation, and aspect with one-hour limiter cleanup.
- [ ] Run the test and verify it passes.

### Task 2: SSE error delivery

**Files:**
- Modify: `src/main/java/com/xhl/aicodegenerate/exception/GlobalExceptionHandler.java`
- Modify: `src/main/java/com/xhl/aicodegenerate/controller/AppController.java`
- Modify: `ai-code-frontend/src/pages/app/AppChatPage.vue`
- Test: `src/test/java/com/xhl/aicodegenerate/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `BusinessException` with error code 42900.
- Produces: SSE `business-error` JSON followed by `done`.

- [ ] Write a failing handler test asserting event type, JSON fields, UTF-8 headers, and done event.
- [ ] Implement SSE-aware exception output and annotate `/app/chat/gen/code` with 5-per-60-second user limiting.
- [ ] Add the Vue `business-error` listener that displays the message and closes the stream.
- [ ] Run backend handler tests and frontend type checking.

### Task 3: Prompt and output guardrails

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/ai/guardrail/PromptSafetyInputGuardrail.java`
- Create: `src/main/java/com/xhl/aicodegenerate/ai/guardrail/RetryOutputGuardrail.java`
- Modify: `src/main/java/com/xhl/aicodegenerate/ai/AiCodeGeneratorService.java`
- Modify: `src/main/java/com/xhl/aicodegenerate/ai/AiCodeGeneratorServiceFactory.java`
- Test: `src/test/java/com/xhl/aicodegenerate/ai/guardrail/PromptSafetyInputGuardrailTest.java`
- Test: `src/test/java/com/xhl/aicodegenerate/ai/guardrail/RetryOutputGuardrailTest.java`

**Interfaces:**
- Produces: LangChain4j `InputGuardrail` and `OutputGuardrail` implementations.

- [ ] Write failing tests for empty, long, sensitive, injected, valid, empty-output, short-output, credential-output, and valid-output cases.
- [ ] Implement both guardrails exactly at the model boundary.
- [ ] Register the input guardrail on all user-facing AI service builders.
- [ ] Annotate synchronous generation methods with the output guardrail and 3 retries.
- [ ] Run guardrail and AI factory tests.

### Task 4: Retry and tool-loop stability

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/xhl/aicodegenerate/config/ReasoningStreamingChatModelConfig.java`
- Modify: `src/main/java/com/xhl/aicodegenerate/ai/AiCodeGeneratorServiceFactory.java`
- Test: `src/test/java/com/xhl/aicodegenerate/config/ReasoningStreamingChatModelConfigTest.java`

**Interfaces:**
- Produces: models with 3 transport retries and Vue services with at most 20 tool round trips.

- [ ] Write a failing configuration test for the reasoning model retry property.
- [ ] Configure default models and the manual reasoning model for 3 retries.
- [ ] Replace deprecated sequential invocation configuration with `maxToolCallingRoundTrips(20)`.
- [ ] Run configuration and factory tests.

### Task 5: Low-cost routing model

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/config/RoutingChatModelConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/xhl/aicodegenerate/ai/AiCodeGeneratorServiceFactory.java`
- Test: `src/test/java/com/xhl/aicodegenerate/config/RoutingChatModelConfigTest.java`

**Interfaces:**
- Produces: Spring bean `routingChatModel` using qwen-turbo/100 tokens when configured, otherwise the primary `chatModel`.

- [ ] Write a failing context test for configured routing-model creation and missing-key fallback.
- [ ] Implement typed routing properties and the named model bean.
- [ ] Inject `routingChatModel` into `AiCodeGenTypeRoutingService` while leaving generation models unchanged.
- [ ] Run routing and workflow routing tests.

### Task 6: Full verification

**Files:**
- Verify all modified backend and frontend artifacts.

- [ ] Run all new tests plus affected regression tests.
- [ ] Run `./mvnw -DskipTests package`.
- [ ] Run `yarn type-check` and `yarn build` in `ai-code-frontend`.
- [ ] Start the Spring application against local Redis/MySQL and verify Redis connectivity where credentials permit.
- [ ] Audit every article requirement against code and test evidence.
