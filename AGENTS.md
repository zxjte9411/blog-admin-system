# 互動與範圍
- 使用繁體中文台灣用語回覆；需要使用者回覆時，列出建議選項並透過提問工具詢問。
- 本專案在開發容器內執行，請注意 IP 使用與互動。

## Agent skills

### Issue tracker

議題與規格存放於 GitHub Issues。見 `docs/agents/issue-tracker.md`。

### Triage labels

採用五個預設 triage 標籤。見 `docs/agents/triage-labels.md`。

### Domain docs

採單一領域上下文。見 `docs/agents/domain.md`。

<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->
