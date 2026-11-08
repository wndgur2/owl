# MCP spec security mechanisms: authorization, transports, Security Best Practices (as of Nov 2026)

Version timeline (read this first):
- **2024-11-05**: original spec. stdio plus the HTTP+SSE transport. No authorization framework.
- **2025-03-26**: first OAuth 2.1 authorization framework. Streamable HTTP replaces HTTP+SSE, which has been deprecated since this revision. Tool annotations were (to my knowledge) introduced here; not re-verified this session. Criticized because the MCP server acted as both resource server and authorization server ([Parecki](https://aaronparecki.com/2025/04/03/15/oauth-for-model-context-protocol)).
- **2025-06-18**: MCP server classified as an OAuth Resource Server. Adds RFC 9728 PRM discovery and makes RFC 8707 resource indicators mandatory for clients. Adds the Security Best Practices page, elicitation, structured tool output, and the `MCP-Protocol-Version` header. Removes JSON-RPC batching ([changelog](https://modelcontextprotocol.io/specification/2025-06-18/changelog)).
- **2025-11-25**: adds OIDC Discovery and CIMD (Client ID Metadata Documents) as the recommended registration method. Adds incremental scope via `WWW-Authenticate` (SEP-835), URL-mode elicitation (SEP-1036), experimental tasks, and the explicit 403 for an invalid Origin. The `WWW-Authenticate` header becomes optional, with a `.well-known` fallback (SEP-985) ([changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)).
- **2026-07-28 (current, released 2026-07-28, "generally available")**: makes the core stateless. Removes `initialize`, protocol sessions (`Mcp-Session-Id`), the GET stream and SSE resumability. Introduces MRTR (Multi Round-Trip Requests) in place of server-initiated requests, plus `server/discover`. Adds the required `Mcp-Method`/`Mcp-Name` headers and RFC 9207 `iss` validation. Deprecates DCR, Roots, Sampling and Logging. Tasks becomes an extension ([changelog](https://modelcontextprotocol.io/specification/latest/changelog), [release blog](https://blog.modelcontextprotocol.io/posts/2026-07-28/)). `modelcontextprotocol.io/specification/latest/...` now resolves to 2026-07-28 pages. I found no later revision.

## 1. Authorization: how it works, what is mandatory, stdio vs HTTP

### Takeaway
Authorization is OPTIONAL in MCP. If an HTTP server implements it, the server is an OAuth 2.1 resource server and MUST publish RFC 9728 Protected Resource Metadata (PRM) and validate token audience. Clients MUST use PKCE S256, RFC 8707 `resource`, and (since 2026-07-28) RFC 9207 `iss` validation. stdio servers SHOULD NOT use OAuth; they take credentials from the environment. CIMD is the preferred registration method and DCR is deprecated as of 2026-07-28.

### Cited Findings
**Applicability**
- "Authorization is **OPTIONAL** for MCP implementations." HTTP-based transports SHOULD conform. STDIO implementations "**SHOULD NOT** follow this specification, and instead retrieve credentials from the environment." Alternative transports "**MUST** follow established security best practices for their protocol." — [Authorization (2026-07-28)](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Standards it builds on: OAuth 2.1 draft-13, RFC 6750, RFC 8414, RFC 7591, RFC 8707, RFC 9728, RFC 9207, draft-ietf-oauth-client-id-metadata-document-00, OIDC Discovery 1.0 and OIDC Dynamic Registration. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)

**Roles and discovery**
- The MCP server is an OAuth 2.1 resource server and the client is an OAuth 2.1 client. The authorization server (AS) "may be hosted with the resource server or a separate entity". — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- MCP servers MUST implement RFC 9728 PRM, and clients MUST use it to discover the AS. The AS MUST provide RFC 8414 metadata or OIDC Discovery, and clients MUST support both. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Flow: the client calls with no token and gets `401` with `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource", scope="…"`. It then fetches PRM, then AS metadata, then registers or identifies itself (CIMD, DCR or pre-registered). It then runs PKCE + `resource` + scope selection, records the expected issuer, validates `iss` on the callback, and exchanges the code with `code_verifier` + `resource`. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- In 2025-11-25 the `WWW-Authenticate` header became optional, with fallback to the `.well-known` endpoint, to align with RFC 9728 (SEP-985). OIDC Discovery support was also added in that revision. — [2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- History: 2025-03-26 had clients fetch AS metadata from the MCP server's own base URL, which forced the MCP server to also be an AS. Parecki proposed splitting the roles and using RFC 9728 PRM. The fix landed in 2025-06-18. — [Aaron Parecki, 2025-04-03](https://aaronparecki.com/2025/04/03/15/oauth-for-model-context-protocol); [2025-06-18 changelog](https://modelcontextprotocol.io/specification/2025-06-18/changelog)

**PKCE**
- Clients MUST implement PKCE and MUST use `S256` when technically capable. They MUST verify PKCE support from AS metadata: if `code_challenge_methods_supported` is absent, clients "**MUST** refuse to proceed". The same check applies to OIDC metadata. — [Authorization Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)

**Resource indicators and audience binding (RFC 8707)**
- Clients MUST include `resource` in both authorization and token requests. It must be the server's canonical URI, e.g. `https://mcp.example.com/mcp`, with no fragment and preferably no trailing slash. Clients "**MUST** send this parameter regardless of whether authorization servers support it." — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Servers MUST validate tokens per OAuth 2.1 §5.2 and MUST check that tokens "were issued specifically for them as the intended audience". Invalid or expired tokens MUST get 401. "MCP servers **MUST NOT** accept or transit any other tokens." — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- RFC 8707 became mandatory for clients in 2025-06-18 (PR #734) "to prevent malicious servers from obtaining access tokens". — [2025-06-18 changelog](https://modelcontextprotocol.io/specification/2025-06-18/changelog)

**Token usage**
- Tokens go only in `Authorization: Bearer`, on every HTTP request, and MUST NOT appear in the query string. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Refresh tokens MUST be kept confidential. Clients MAY add `offline_access`. Servers SHOULD NOT list `offline_access` in their scopes. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Token theft: clients and servers MUST store tokens securely. The AS SHOULD issue short-lived access tokens and MUST rotate refresh tokens for public clients. — [Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)
- Communication security: all AS endpoints MUST use HTTPS. Redirect URIs MUST be `localhost` or HTTPS. The AS MUST match redirect URIs exactly. Clients SHOULD use and verify `state`. — [Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)

**Mix-up defense (new in 2026-07-28)**
- The client MUST record the AS `issuer` alongside the PKCE verifier before redirecting. The AS SHOULD return `iss` (RFC 9207). The client MUST validate `iss` before redeeming the code, by plain string comparison with no normalization, and MUST reject a response missing `iss` when metadata advertises `authorization_response_iss_parameter_supported: true`. A future revision is expected to make the AS-side requirement a MUST (SEP-2468). — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- PKCE alone does not stop mix-up, because the verifier is sent to the attacker's token endpoint. — [Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices)

**Client registration**
- Clients SHOULD use this priority: (1) pre-registered credentials, (2) CIMD if the AS advertises `client_id_metadata_document_supported`, (3) DCR if a `registration_endpoint` exists, (4) prompt the user. — [Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration)
- CIMD: the `client_id` is an HTTPS URL with a path that serves JSON containing at least `client_id`, `client_name` and `redirect_uris`. The AS MUST check that `client_id` equals the URL and MUST validate redirect URIs against the document. CIMD was added in 2025-11-25 (SEP-991). — [Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration); [2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- DCR (RFC 7591) is "deprecated and retained for backwards compatibility" (PR #2858, 2026-07-28). Clients using DCR MUST send `application_type` (`native` for desktop, mobile, CLI and localhost apps; SEP-837). — [Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration); [changelog](https://modelcontextprotocol.io/specification/latest/changelog)
- AS binding (SEP-2352): persisted or pre-registered client credentials MUST be keyed by issuer and MUST NOT be reused with another AS. CIMD IDs are portable across authorization servers. — [Client Registration](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration)
- CIMD risks:
  - The AS SHOULD guard against SSRF when it fetches metadata documents.
  - CIMD "cannot prevent `localhost` URL impersonation". The AS SHOULD warn on localhost-only redirect URIs and MUST clearly display the redirect URI hostname.
  - The AS MAY apply domain trust policies (allowlists, etc.).

  — [Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)

**Scopes and step-up**
- Servers SHOULD put `scope` in the 401 `WWW-Authenticate` header. For the initial request, clients SHOULD use that `scope`, or else all of `scopes_supported`. `scopes_supported` "is intended to represent the minimal set of scopes necessary for basic functionality". — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Runtime insufficient scope: the server SHOULD return `403` with `WWW-Authenticate: Bearer error="insufficient_scope", scope="…", resource_metadata="…"` and include all required scopes in one challenge. Clients SHOULD step up using the union of previously requested and newly challenged scopes, with retry limits. Servers MUST account for scope hierarchies. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Status codes: 401 for missing or invalid token, 403 for insufficient scope, 400 for a malformed request. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)
- Extensions are optional and versioned separately ([ext-auth repo](https://github.com/modelcontextprotocol/ext-auth)). The 2026-07-28 blog names Enterprise Managed Authorization (EMA) as an official extension but gives no details. — [Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization); [release blog](https://blog.modelcontextprotocol.io/posts/2026-07-28/)

### Inferences
- A single-user Owl server is still a full OAuth resource server if it is exposed over HTTP with auth. It must at minimum serve PRM at `/.well-known/oauth-protected-resource`, return 401 with `WWW-Authenticate`, and validate `aud`/resource on every request. It also needs an AS that has PKCE metadata and ideally CIMD support, since Claude offers "Use Claude's published identity", which looks like CIMD. The spec does not exempt small or personal servers.
- A server with a separate AS (e.g. a hosted IdP) is spec-compliant and avoids hand-rolling OAuth. An AS co-located with the server is also allowed.
- Under stdio, none of the OAuth machinery applies, which drastically reduces the attack surface. The trust boundary becomes "whoever can spawn the process".

### Gaps
- I did not fetch the separate "authorization-server-discovery" subpage (the exact `.well-known` path-insertion order for RFC 8414 vs OIDC).
- I did not verify the 2025-03-26 auth details (default `/authorize`, `/token` and `/register` fallback endpoints) against the primary page this session.

## 2. Transports: stdio vs Streamable HTTP (vs deprecated HTTP+SSE); Origin, DNS rebinding, localhost, sessions, TLS

### Takeaway
For Streamable HTTP, every revision from 2025-03-26 to 2026-07-28 requires Origin validation (MUST, with 403 on invalid Origin since 2025-11-25). Servers SHOULD bind to 127.0.0.1 when local and SHOULD authenticate every connection. In 2025-03-26 through 2025-11-25, sessions used `Mcp-Session-Id` (SHOULD be cryptographically secure, MUST be visible ASCII). 2026-07-28 removes sessions entirely, replaces them with app-level "state handles", and adds header/body consistency checks. HTTP+SSE is formally Deprecated.

### Cited Findings
**Streamable HTTP security (2026-07-28, same text as in 2025-11-25)**
- Servers "**MUST** validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks". An invalid Origin that is present MUST get 403. "When running locally, servers **SHOULD** bind only to localhost (127.0.0.1) rather than all network interfaces (0.0.0.0)". Servers "**SHOULD** implement proper authentication for all connections." — [Streamable HTTP (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http); [Transports (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- The explicit 403 for an invalid Origin was a 2025-11-25 clarification (PR #1439). — [2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)

**Request metadata headers (2026-07-28)**
- Every POST MUST carry `MCP-Protocol-Version`, which must match `_meta` `io.modelcontextprotocol/protocolVersion`. `Mcp-Method` (all requests) and `Mcp-Name` (tools/call, resources/read, prompts/get) are REQUIRED. Servers MUST reject header/body mismatches with `400` and `-32020 HeaderMismatch`, "when different components in the network rely on different sources of truth". — [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)
- With `x-mcp-header`, tool parameters can be mirrored as `Mcp-Param-*` headers, Base64 encoded when not plain ASCII. Server developers "**SHOULD NOT** mark sensitive parameters (passwords, API keys, tokens, PII) with `x-mcp-header`, as header values are visible to network intermediaries." — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)

**Sessions**
- 2025-11-25 and earlier: the server MAY assign `MCP-Session-Id` at initialization. The ID "**SHOULD** be globally unique and cryptographically secure (e.g., a securely generated UUID, a JWT, or a cryptographic hash)" and "**MUST** only contain visible ASCII characters (0x21 to 0x7E)". Clients MUST handle it securely. A terminated session returns 404. Clients SHOULD send DELETE to end a session. — [Transports (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- 2026-07-28 removes protocol-level sessions and `Mcp-Session-Id` (SEP-2567), the GET stream, and `Last-Event-ID` resumability (SEP-2575). New servers SHOULD answer GET or DELETE with 405 and SHOULD ignore `Mcp-Session-Id`, neither minting nor echoing it. — [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http); [changelog](https://modelcontextprotocol.io/specification/latest/changelog)
- The `tools/list` result "**MUST NOT** vary per-connection" but "**MAY** vary by the authorization presented on the request", e.g. returning only the tools the caller's scopes permit. — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- Stateful tools (non-normative): "a handle is a name, not a capability". Servers should validate authorization against the handle on each call. On unauthenticated servers, handles need high entropy (e.g. UUIDv4) and a bounded lifetime. — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)

**stdio**
- The client launches the server as a subprocess, with newline-delimited JSON-RPC on stdin/stdout and logs on stderr. In 2025-11-25, clients "SHOULD support stdio whenever possible". — [Transports (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- Custom transports over byte streams (Unix sockets, TCP) SHOULD reuse stdio framing. — [Transports overview (2026-07-28)](https://modelcontextprotocol.io/specification/latest/basic/transports)

**HTTP+SSE (2024-11-05)**
- Deprecated since 2025-03-26 and reclassified as "Deprecated" under the lifecycle policy (SEP-2596). New implementations SHOULD NOT adopt it, and it is eligible for removal. The minimum deprecation window is 12 months. — [Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http); [release blog](https://blog.modelcontextprotocol.io/posts/2026-07-28/)

**TLS**
- The transport page has no explicit TLS MUST for the MCP endpoint itself. HTTPS is mandated through auth: AS endpoints MUST use HTTPS and redirect URIs MUST be localhost or HTTPS. The Best Practices page says clients SHOULD require HTTPS for all OAuth URLs except loopback. — [Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations); [Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices)

**Real-world failure of these rules**
- CVE-2025-49596 (CVSS 9.4) in MCP Inspector before 0.14.1: the local proxy accepted unauthenticated connections without Origin validation, allowing CSRF/DNS-rebinding style RCE from a malicious website. — [GovInfoSecurity](https://www.govinfosecurity.com/serious-flaws-patched-in-model-context-protocol-tools-a-28924) (trade press, not the primary advisory)

### Inferences
- An HTTP MCP server inside the Owl Android app, on localhost, is spec-compliant only if it validates Origin, binds 127.0.0.1 and requires a token. Other Android apps on the same phone can still reach a loopback port. Per the Best Practices, a local HTTP server should require an authorization token or use restricted IPC.
- Targeting 2026-07-28 removes session-hijacking concerns, but cross-call state (e.g. search pagination cursors) becomes "state handles" that must be bound to the authenticated principal.
- Old clients may still speak 2025-06-18 or 2025-11-25. Supporting them brings back `Mcp-Session-Id` and its security rules.

### Gaps
- I did not fetch the 2026-07-28 stdio binding page separately, so its exact process-lifecycle rules are unverified.
- The spec gives no guidance for mobile OS local servers (Android loopback exposure to other apps).

## 3. The official Security Best Practices document: attacks and mitigations

### Takeaway
The 2026-07-28 Best Practices page covers ten topics: confused deputy, token passthrough, SSRF, state handle hijacking (renamed from session hijacking), local server compromise, OAuth URL validation (XSS/RCE), stdio proxy escalation, mix-up, localhost redirect impersonation with CIMD trust policies, and scope minimization. Most normative weight falls on proxy servers and clients. For a server like Owl, the key rules are: never accept or pass through foreign tokens, bind state to the verified user, use least-privilege scopes, and harden local servers.

### Cited Findings
All from [Security Best Practices (2026-07-28)](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices) unless noted.

- **Confused deputy.** Applies to an MCP proxy server that uses a static client ID at a third-party AS while also letting clients register dynamically. The third-party AS's consent cookie lets an attacker-registered client (with an attacker `redirect_uri`) skip consent and steal the MCP authorization code.
  - Mitigation: proxy servers MUST implement per-client consent before forwarding: keep a per-user registry of approved `client_id`s and show an MCP-owned consent page. That page MUST name the client, show scopes and `redirect_uri`, include CSRF protection, and block framing (`frame-ancestors` or `X-Frame-Options: DENY`).
  - Consent cookies MUST use the `__Host-` prefix, be `Secure`, `HttpOnly` and `SameSite=Lax`, be signed, and be bound to the `client_id`.
  - `redirect_uri` MUST match exactly. `state` MUST be cryptographically random, single-use and short-lived (e.g. 10 minutes), and set only after consent.
- **Token passthrough.** The anti-pattern of accepting tokens not issued to the MCP server and forwarding them downstream. Risks: bypassed security controls, a broken audit trail, trust-boundary violations, and use of the server as an exfiltration proxy with stolen tokens. Mitigation: "MCP servers **MUST NOT** accept any tokens that were not explicitly issued for the MCP server." Upstream calls must use a separate token, and the server "**MUST NOT** pass through the token it received from the MCP client" ([Security Considerations](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/security-considerations)).
- **SSRF.** A malicious server places internal URLs (192.168.x, 169.254.169.254, localhost:6379, DNS rebinding, redirect chains) in `resource_metadata`, `authorization_servers` or AS metadata endpoints.
  - Clients deployed on servers MUST consider SSRF. Clients SHOULD enforce HTTPS (http only for loopback in development), block private, loopback, link-local and IPv6 ULA ranges, validate each redirect hop, use egress proxies (e.g. Smokescreen), and watch for DNS TOCTOU.
  - The same applies to an AS fetching CIMD documents.
- **State handle hijacking** (2026-07-28; replaces "Session Hijacking" in 2025-11-25). An attacker guesses or obtains a server-minted handle and operates on another user's state. Servers with auth MUST verify all inbound requests and "MUST NOT treat possession of a state handle as authentication". Servers SHOULD use secure random handles and expiry, and SHOULD bind handles server-side as `<user_id>:<handle>` with the user ID taken from the verified token.
  - The 2025-11-25 session ID guidance is kept at the [2025-11-25 Best Practices](https://modelcontextprotocol.io/docs/2025-11-25/tutorials/security/security_best_practices.md#session-hijacking) page.
  - Unverified: from my memory of the 2025-11-25 page, the old session rules had the same shape (MUST NOT use sessions for authentication, plus the `<user_id>:<session_id>` binding).
- **Local MCP server compromise.** Attack vectors: malicious startup commands in client config (e.g. `npx … && curl -d @~/.ssh/id_rsa`), malicious server payloads, and DNS rebinding against a server left running on localhost.
  - A client with one-click local install MUST show the exact untruncated command, flag it as dangerous, and require explicit approval with an option to cancel. Clients SHOULD sandbox servers and highlight `sudo`, `rm -rf` and similar.
  - Servers meant to run locally SHOULD "Use the `stdio` transport to limit access to just the MCP client", or restrict HTTP with an authorization token, unix domain sockets or other restricted IPC.
- **OAuth authorization URL validation.** A malicious server supplies `javascript:` URLs or shell-injection payloads. Clients MUST allow only http/https (http only for loopback), MUST reject `javascript:`, `data:`, `file:`, `vbscript:` and similar, MUST NOT open URLs via a shell, and SHOULD use CSP.
  - Real case: CVE-2025-6514 (CVSS 9.6), a command injection in `mcp-remote` 0.0.5–0.1.15 when connecting to a malicious server, fixed in 0.1.16 — [GovInfoSecurity](https://www.govinfosecurity.com/serious-flaws-patched-in-model-context-protocol-tools-a-28924)
- **stdio in proxy scenarios.** XSS steals the proxy auth token, and the attacker then uses the proxy to spawn arbitrary stdio commands for RCE. Proxies SHOULD sandbox spawned processes, log stdio use and require extra authorization for dangerous commands.
- **Mix-up attacks.** Mitigated by RFC 9207 `iss` validation. PKCE and resource indicators do not help here.
- **Localhost redirect impersonation.** With CIMD, an attacker can present a legitimate client's metadata URL plus its own localhost port. Mitigations on the AS: warnings and showing the redirect hostname. CIMD trust policies: domain allowlists, reputation or age checks.
- **Scope minimization.** A stolen omnibus token (`files:*`, `admin:*`) gives a large blast radius.
  - Mitigation: a minimal initial scope (e.g. `mcp:tools-basic`), incremental elevation via `WWW-Authenticate scope=` challenges, tolerance for down-scoped tokens, and logging of elevation events.
  - Common mistakes: publishing every scope in `scopes_supported`, wildcard scopes, bundling privileges, and "Treating claimed scopes in token as sufficient without server-side authorization logic".

### Inferences
- For Owl as a direct server with no upstream API, confused deputy and token passthrough mostly do not apply unless Owl later proxies to a cloud service. Scope minimization, state handle binding and local server hardening do apply.
- Natural Owl scopes: `transcripts:read`, `locations:read`, `bookmarks:read`, and a separate write scope if write tools are ever added. Location is the most sensitive and would be a step-up scope. This is a design suggestion, not spec text.

### Gaps
- The Best Practices page does not cover prompt injection or tool poisoning at all. Those come from external research (see section 6).

## 4. Spec-level user-control features: annotations, elicitation, sampling, roots, human-in-the-loop, logging

### Takeaway
Human-in-the-loop is only a SHOULD on clients. Tool annotations are explicitly untrusted hints. Elicitation (form and URL mode) is the active user-interaction primitive, carried by MRTR since 2026-07-28. Roots, Sampling and Logging are deprecated in 2026-07-28, so a new Owl server should not rely on them.

### Cited Findings
- **Human in the loop.** "For trust & safety and security, there **SHOULD** always be a human in the loop with the ability to deny tool invocations." Applications SHOULD show which tools are exposed, give clear visual indicators when tools are invoked, and present confirmation prompts. — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- **Tool security considerations.** Servers MUST "Validate all tool inputs; Implement proper access controls; Rate limit tool invocations; Sanitize tool outputs". Clients SHOULD prompt for confirmation on sensitive operations, "Show tool inputs to the user before calling the server, to avoid malicious or accidental data exfiltration", validate results before passing them to the LLM, use timeouts, and log tool usage for audit. — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- **Tool annotations.**
  - `readOnlyHint` (default false): "does not modify its environment".
  - `destructiveHint` (default true; meaningful only when not read-only).
  - `idempotentHint` (default false).
  - `openWorldHint` (default true): "may interact with an 'open world' of external entities… a memory tool is not".
  - "NOTE: all properties in ToolAnnotations are **hints**. They are not guaranteed to provide a faithful description of tool behavior… Clients should never make tool use decisions based on ToolAnnotations received from untrusted servers."

  — [Schema (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/schema). The 2026-07-28 tools page states it normatively: "clients **MUST** consider tool annotations to be untrusted unless they come from trusted servers" — [Tools (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- **Elicitation: modes and sensitive data.** Servers MUST NOT use form mode for "passwords, API keys, access tokens, or payment credentials" and MUST use URL mode for those. Clients MUST show which server is asking, offer decline and cancel, let users review form answers, and for URL mode show the target domain and get consent before navigating. Responses are accept, decline or cancel. — [Elicitation (2026-07-28)](https://modelcontextprotocol.io/specification/2026-07-28/client/elicitation)
- **Elicitation: URL mode rules.** Clients MUST NOT prefetch the URL, MUST show the full URL, and MUST open it in a context the client and LLM cannot inspect (e.g. SFSafariViewController, not WKWebView). Servers MUST NOT put PII or credentials in the URL and MUST NOT use pre-authenticated URLs. Servers MUST check that the user who completes the URL flow is the user who started it (phishing / account-takeover defense). URL mode was added in 2025-11-25 (SEP-1036). — [Elicitation](https://modelcontextprotocol.io/specification/2026-07-28/client/elicitation); [2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog)
- **Elicitation: identity and transport.** Servers MUST bind elicitation to the client and user identity, and MUST NOT trust user identity the client asserts ("I am joe@…"). Under 2026-07-28 MRTR, elicitation is delivered inside `InputRequiredResult.inputRequests`. The client retries the original request with `inputResponses` and `requestState`, and the retry MUST use a new JSON-RPC id. — [Elicitation](https://modelcontextprotocol.io/specification/2026-07-28/client/elicitation); [Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)
- **Sampling.** The schema says "The client should also inform the user before beginning sampling… (human in the loop)" and should let the user inspect the response before the server sees it. `includeContext` values `thisServer` and `allServers` were soft-deprecated in 2025-11-25 and are Deprecated in 2026-07-28. — [Schema (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/schema); [changelog](https://modelcontextprotocol.io/specification/latest/changelog)
- **Deprecations.** "Deprecate the Roots, Sampling, and Logging features (SEP-2577)… new implementations should not add support for them". Suggested replacements: tool parameters instead of Roots, direct LLM APIs instead of Sampling, stderr or OpenTelemetry instead of Logging. `logging/setLevel` is removed. The log level is now set per request in `_meta` (`io.modelcontextprotocol/logLevel`), and servers MUST NOT emit `notifications/message` for requests that lack that field. — [changelog](https://modelcontextprotocol.io/specification/latest/changelog)
- **Client support.** ChatGPT reportedly does not consume MCP prompts, roots, sampling or elicitation from remote servers. — [mcp.directory blog / search summary](https://mcp.directory/blog/chatgpt-developer-mode-full-access-to-mcp-connectors) (third-party, unverified)

### Inferences
- For Owl, annotations (`readOnlyHint: true`, `openWorldHint: false`) are worth setting so clients may skip or relax confirmation prompts. Owl's own security must not depend on them: enforce read-only on the server side.
- Elicitation could gate sensitive queries, e.g. "Allow Claude to read location history for 2026-10-03?". This only works if the client supports elicitation, which is uneven (see section 5). A safer pattern is enforcing consent in the Owl app itself, e.g. per-request approval in the Android app, independent of client UI.
- The spec gives the server no way to force the client to show confirmations. Human-in-the-loop is client-side and only a SHOULD.

### Gaps
- I could not confirm current elicitation and URL-mode support in Claude (web, mobile, Desktop) or ChatGPT from primary sources.

## 5. What real clients support (remote vs local, auth, mobile)

### Takeaway
Claude (web, Desktop, Cowork) custom connectors are remote-only and connect from Anthropic's cloud, so the server must be publicly reachable. Mobile apps use connectors added elsewhere; installing on mobile is described as beta. Local stdio servers work only in Claude Desktop (and Claude Code), not on claude.ai, Cowork or mobile. ChatGPT also requires remote servers (Developer Mode). No mainstream mobile LLM app connects to an MCP server running on the phone.

### Cited Findings
- "Claude connects to your remote MCP server from Anthropic's cloud infrastructure", not from the device. This applies to all clients including mobile. Servers behind a VPN, private network or firewall won't connect unless Anthropic's IP ranges are allowlisted. — [Claude Help: Getting started with custom connectors](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp)
- Custom connectors are available on Claude, Cowork and Claude Desktop across Free (one connector), Pro, Max, Ultra, Team and Enterprise. Auth options: "Sign in now / Sign in when needed / No sign in". OAuth client options: "Use Claude's published identity (Recommended)", "Register automatically", "Use your own OAuth client". Fixed API key or bearer headers are also possible. — [Claude Help](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp)
- Local servers are configured in Claude Desktop via `claude_desktop_config.json`. They are "not available in Cowork or claude.ai", and Advanced Research can't call local tools. — [Claude Help](https://support.claude.com/en/articles/11175166-getting-started-with-custom-connectors-using-remote-mcp)
- Mobile: remote connectors are usable on Claude for iOS/Android. Connectors set up on web or Desktop sync to mobile. Installing connectors on mobile is "currently in beta". — [Claude Help: pre-built connectors](https://support.claude.com/en/articles/11176164-pre-built-connectors-using-remote-mcp) (via search summary; page not fully fetched)
- ChatGPT: Developer Mode (Settings → Apps & Connectors → Advanced) connects only to remote public HTTPS servers (SSE or Streamable HTTP) with OAuth or no auth. A local stdio server "will not work". Plan eligibility differs between sources. One source mentions an OpenAI "Secure MCP Tunnel" for private servers. — [mcp.directory](https://mcp.directory/blog/chatgpt-developer-mode-full-access-to-mcp-connectors), [Zuplo docs](https://zuplo.com/docs/mcp-gateway/connect-clients/chatgpt), [Auth0 blog](https://auth0.com/blog/add-remote-mcp-server-chatgpt/) (third-party; OpenAI's own help page not retrieved)
- SDKs: TypeScript, Python, Go and C# support 2026-07-28 at release; Rust is in beta. — [release blog](https://blog.modelcontextprotocol.io/posts/2026-07-28/)
- Observed in this session's own environment: several connectors failed with "Incompatible auth server: does not support dynamic client registration". This suggests at least some clients (here Claude Code's plugin connector path) still relied on DCR in late 2026. It is anecdotal and environment-specific.

### Inferences
- **Fundamental conflict with Owl's privacy principle.** To use Claude on web or mobile, the Owl data server must be publicly reachable, e.g. a phone with a tunnel or a cloud relay. Anthropic's cloud calls it, and transcripts and locations then go into the LLM context on Anthropic's servers. Any MCP integration with a cloud LLM means "external transmission" of whatever the tools return, whatever the transport security.
- **Lowest-exposure option.** A stdio server on the owner's PC, run by Claude Desktop or Claude Code, reading an exported Room DB snapshot. No network listener, no OAuth. Data still reaches the LLM provider through the model context.
- **Phone-local option.** A Streamable HTTP server on Android loopback has no consumer client today. Claude and ChatGPT mobile cannot reach the phone's localhost.
- This should be raised against `docs/prd.md`'s "no external transmission" principle and recorded in `docs/decisions.md`.

### Gaps
- I did not find an official Anthropic page listing exactly which spec version and auth features (CIMD vs DCR) Claude connectors implement as of Nov 2026. The developer docs URL I tried returned 404.
- OpenAI's primary help article on Developer Mode, MCP and Secure MCP Tunnel was not retrieved, so plan eligibility and tunnel details are unverified.
- I did not confirm whether Claude mobile can add a custom connector itself (beta) or only use synced ones.

## 6. Known spec-level weaknesses and criticisms

### Takeaway
Main critiques: (a) the early 2025-03-26 auth design conflated the resource server and authorization server, fixed in 2025-06-18; (b) DCR was impractical and open-ended, replaced by CIMD and deprecated in 2026-07-28; (c) tool descriptions and annotations are untrusted channels into the model (tool poisoning, rug pulls, cross-server shadowing), and the spec addresses this only with SHOULD-level client UI and "untrusted annotations" caveats; (d) many key protections are SHOULD-only (human-in-the-loop, AS `iss`, localhost binding); (e) implementation CVEs in reference tooling.

### Cited Findings
- 2025-03-26 forced MCP servers to be authorization servers because discovery used the server's base URL. Parecki proposed RFC 9728, audience-restricted tokens, limiting DCR, and client ID metadata documents (Bluesky-style). The fixes went into 2025-06-18. — [Aaron Parecki, 2025-04-03](https://aaronparecki.com/2025/04/03/15/oauth-for-model-context-protocol)
- Tool Poisoning Attacks (Invariant Labs, 2025-04-01): hidden instructions in tool descriptions, visible to the model but not the user. Demonstrated in Cursor by exfiltrating `~/.cursor/mcp.json` and `~/.ssh/id_rsa` via a hidden parameter that the confirmation dialog didn't show in full.
  - Rug pulls: a description changes after approval.
  - Shadowing: a malicious server's description changes how a trusted server's tool is used, e.g. redirecting all `send_email` recipients.
  - Mitigations proposed: show full descriptions, pin and hash tool definitions, enforce cross-server data-flow boundaries.

  — [Invariant Labs](https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks)
- CVE-2025-49596 (MCP Inspector, CVSS 9.4: no auth and no Origin check on the local proxy) and CVE-2025-6514 (`mcp-remote` command injection from a malicious server's OAuth URL, CVSS 9.6). — [GovInfoSecurity](https://www.govinfosecurity.com/serious-flaws-patched-in-model-context-protocol-tools-a-28924) (trade press)
- The spec itself admits limits:
  - CIMD "cannot prevent `localhost` URL impersonation".
  - `iss` protection "provides no protection against an honest server that does not" emit `iss`.
  - The fallback of requesting all `scopes_supported` when no scope is challenged is a deliberate least-privilege trade-off.

  — [Security Best Practices](https://modelcontextprotocol.io/docs/2026-07-28/tutorials/security/security_best_practices)
- The 2026-07-28 migration cost falls especially on developers who relied on session identifiers. — [release blog](https://blog.modelcontextprotocol.io/posts/2026-07-28/)

### Inferences
- For Owl, the bigger spec-level risk is not OAuth. It is that an LLM client with Owl tools plus other tools (web fetch, email, other connectors) can be prompt-injected into exfiltrating transcripts. Injected text could even come from Owl's own transcripts, since they record whatever people say near the pendant. MCP has no data-flow or label mechanism to stop that. Mitigations have to be server-side: narrow queries, result-size limits, redaction, audit logs, per-request user approval in the Owl app. Treating transcript content as untrusted data is also prudent.

### Gaps
- I did not retrieve primary write-ups from Trail of Bits, Cloudflare or Auth0 on MCP auth. Cloudflare's "The next generation of MCP" ([blog](https://blog.cloudflare.com/mcp-v2/)) appeared in search but was not read.
- The "~84% tool-poisoning success rate" figure cited by secondary sources could not be verified against the original research, so I excluded it.
- I did not verify SEP-level discussion of tool-definition signing or pinning in the spec.
