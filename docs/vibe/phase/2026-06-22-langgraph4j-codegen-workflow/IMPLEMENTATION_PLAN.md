# LangGraph4j CodeGen Workflow Implementation Plan

> **历史归档说明：** 本文保留原技术方案与任务清单，不代表当前实施授权或完成状态。后续续作按根目录 [AGENTS.md](../../../../AGENTS.md) 与 [文档地图](../../../../DOCUMENT_MAP.md) 中的 Vibe 工作流执行；先核对当前需求、源码与验证证据，再决定哪些历史步骤仍适用。

**Goal:** Build an independent LangGraph4j-based AI code generation workflow adapted to this project, with no COS dependency and Kroki/local SVG assets as image substitutes.

**Architecture:** Keep the existing chat/code-generation path untouched. Add a standalone workflow service under `com.xhl.aicodegenerate.langgraph4j` that orchestrates image collection, prompt enhancement, routing, code generation, and project build through LangGraph4j nodes. Replace COS-backed images with local files served by the existing static resource controller.

**Tech Stack:** Spring Boot, LangGraph4j 1.6.0-rc2, LangChain4j AI service proxies, Reactor Flux, Hutool HTTP/file utilities, JUnit 5, Mockito.

---

### Task 1: Context And Asset Storage

**Files:**
- Modify: `src/main/java/com/xhl/aicodegenerate/langgraph4j/state/WorkflowContext.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/tools/LocalWorkflowAssetService.java`
- Test: `src/test/java/com/xhl/aicodegenerate/langgraph4j/tools/LocalWorkflowAssetServiceTest.java`

- [ ] Add `appId` and `userId` to `WorkflowContext`.
- [ ] Add a local asset storage helper that writes SVG files under `tmp/code_output/workflow_assets/app_{appId}`.
- [ ] Return browser-friendly paths under `/api/static/workflow_assets/app_{appId}/{file}`.
- [ ] Test that SVG content is written and the returned path matches the static controller path convention.

### Task 2: Image Tools Without COS

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/tools/KrokiMermaidDiagramTool.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/tools/PlaceholderLogoTool.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/tools/PlaceholderImageTool.java`
- Test: `src/test/java/com/xhl/aicodegenerate/langgraph4j/tools/WorkflowImageToolsTest.java`

- [ ] Implement Kroki Mermaid conversion with fallback to local Mermaid text SVG.
- [ ] Implement local SVG Logo generation.
- [ ] Implement deterministic placeholder content/illustration image resources.
- [ ] Test fallback behavior without network dependency.

### Task 3: Image Collection AI Service

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/ai/ImageCollectionService.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/ai/ImageCollectionServiceFactory.java`
- Create: `src/main/resources/prompt/image-collection-system-prompt.txt`

- [ ] Define a LangChain4j AI service returning a `String`.
- [ ] Register no-COS image tools.
- [ ] Keep output string-based to avoid structured output/model compatibility issues.

### Task 4: Workflow Nodes

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/node/ImageCollectorNode.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/node/PromptEnhancerNode.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/node/RouterNode.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/node/CodeGeneratorNode.java`
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/node/ProjectBuilderNode.java`
- Test: `src/test/java/com/xhl/aicodegenerate/langgraph4j/node/WorkflowNodeTest.java`

- [ ] Each node accepts dependencies through constructors, not static Spring lookups.
- [ ] Each node reads/writes `WorkflowContext`.
- [ ] Code generation uses `AiCodeGeneratorFacade.generateAndSaveCodeStream`.
- [ ] Vue project build uses `VueProjectBuilder.buildProject`; non-Vue types pass through.

### Task 5: Formal Workflow Service

**Files:**
- Create: `src/main/java/com/xhl/aicodegenerate/langgraph4j/CodeGenWorkflow.java`
- Test: `src/test/java/com/xhl/aicodegenerate/langgraph4j/CodeGenWorkflowTest.java`

- [ ] Compose nodes into `image_collector -> prompt_enhancer -> router -> code_generator -> project_builder`.
- [ ] Provide `executeWorkflow(prompt)` and `executeWorkflow(prompt, appId, userId)`.
- [ ] Provide `getMermaidGraph()` for review.
- [ ] Test workflow state flow with mocked dependencies.

### Task 6: Review Documentation

**Files:**
- Create: `docs/langgraph4j-codegen-workflow.md`

- [ ] Explain files created.
- [ ] Explain data flow.
- [ ] Include Mermaid workflow diagram.
- [ ] Explain Kroki/local image replacement for COS.
- [ ] Explain how to run tests and how to later integrate COS/image generation.
