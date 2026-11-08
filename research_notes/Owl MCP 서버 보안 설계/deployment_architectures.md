# Deployment architectures for serving Owl's on-phone data over MCP, and precedents from lifelogging products

Scope: where the MCP server runs (phone, PC or a public endpoint), what each placement exposes, and how comparable products did it. Researched 2026-11-08. Tool budget was limited (about 18 calls), so some vendor claims rest on secondary sources. These are marked.

## Option A: MCP server inside the Android app (localhost or LAN)

### Takeaway
The Kotlin SDK can act as an MCP server, and the Streamable HTTP and Ktor helpers make an in-app server technically possible. On Android, though, any other app on the phone, and in practice web pages opened in the phone's browser, can reach a 127.0.0.1 port. The new local-network permission does not cover loopback. An in-app HTTP server therefore needs its own authentication, Origin checks and a user-visible on/off switch. Binding to 0.0.0.0 (all interfaces, so the whole Wi-Fi network can reach it) would expose the data to every device on the network. Claude's own mobile and web apps cannot connect to a server on the phone, because claude.ai connectors are brokered through Anthropic's servers.

### Cited Findings
- **Kotlin SDK server mode:** the official `modelcontextprotocol/kotlin-sdk` ships a `kotlin-sdk-server` artifact with a `Server` class for tools, resources and prompts. Transports are stdio, Streamable HTTP (recommended for remote use), WebSocket and the deprecated SSE. — [kotlin-sdk README](https://github.com/modelcontextprotocol/kotlin-sdk)
- **Ktor integration:** the SDK provides `mcpStreamableHttp()` and `mcpStatelessStreamableHttp()`; you bring your own Ktor server engine. It is Kotlin Multiplatform (JVM, Native, JS, Wasm) and needs JVM 11+ and Kotlin 2.2+. The README does not mention Android, and I found no Android sample. — [kotlin-sdk README](https://github.com/modelcontextprotocol/kotlin-sdk)
- **MCP spec security rules for Streamable HTTP:**
  - Servers "MUST validate the `Origin` header on all incoming connections to prevent DNS rebinding attacks".
  - "When running locally, servers SHOULD bind only to localhost (127.0.0.1) rather than all network interfaces (0.0.0.0)".
  - Servers "SHOULD implement proper authentication for all connections".
  - "Without these protections, attackers could use DNS rebinding to interact with local MCP servers from remote websites."
  — [MCP spec 2025-06-18, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- **stdio transport:** the client launches the server as a subprocess, and clients "SHOULD support stdio whenever possible". — [MCP spec, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- **Loopback is shared on Android:** the CMU SEI Android secure coding rule DRD23-J says loopback "should not be used when handling sensitive data. The localhost ports are accessible by other applications on the device". — [SEI CERT DRD23-J](https://wiki.sei.cmu.edu/confluence/display/android/DRD23-J.+Do+not+use+loopback+when+handling+sensitive+data)
- **Real-world abuse ("LocalMess", 2025):** Meta (Facebook, Instagram) and Yandex apps listened on fixed localhost ports. Web pages in the phone browser sent identifiers to them, bypassing incognito mode and consent. This shows that web pages can reach app-local ports on Android. — [Hackaday, This Week in Security, 2025-06-13](https://hackaday.com/2025/06/13/this-week-in-security-the-localhost-bypass-reflections-and-x)
- **Android 17 local-network permission:** `ACCESS_LOCAL_NETWORK` covers LAN discovery and access, not loopback. Profiles share a network namespace, so apps in different profiles can reach each other over 127.0.0.1. This is a community/GrapheneOS discussion, not an official Google doc. — [GrapheneOS forum thread 33471](https://discuss.grapheneos.org/d/33471-android-17-access-local-network)
- **Foreground service limits:**
  - On Android 15+, `dataSync` foreground services are capped at 6 hours in a rolling 24-hour window. The system calls `onTimeout()`; if the app does not stop, it crashes with `RemoteServiceException`. Once the budget is used, new starts throw `ForegroundServiceStartNotAllowedException`.
  - Bringing the app to the foreground resets the timer.
  - Google recommends alternatives such as WorkManager.
  — [Android developers: foreground service timeout](https://developer.android.com/develop/background-work/services/fg-service-timeout)
- **Claude apps cannot reach a phone-local server:**
  - claude.ai (web and mobile) and desktop remote connectors are "configured and brokered through your Claude account. The connection to your MCP server originates from Anthropic's servers, not from your machine's network interface."
  - The server "must be reachable over the public internet from Anthropic's IP ranges"; servers behind a VPN or firewall won't connect.
  - Local servers in `claude_desktop_config.json` use the local network but "aren't available in Cowork or claude.ai".
  — [Claude Help Center: About custom connectors using remote MCP](https://support.claude.com/en/articles/11175166-about-custom-connectors-using-remote-mcp)

### Inferences
- **Who can actually use a phone-hosted server:**
  - Claude Desktop or Claude Code on a PC on the same Wi-Fi network. This requires binding to a LAN interface, which loses the localhost protection.
  - A local LLM app on the phone that speaks MCP.
  - An adb port-forward over USB, which keeps the phone bound to 127.0.0.1 while the PC reaches it through `adb forward tcp:X tcp:X`. adb is standard Android tooling, but this is my inference; no source above covers it.
  - Claude mobile and web cannot connect without Option C (a public endpoint).
- **Minimum hardening if Option A is chosen:**
  - Bind to 127.0.0.1 only.
  - Require a high-entropy bearer token shown in the app (QR or copy).
  - Reject any request whose `Origin` is present and not allowlisted, per the spec.
  - Enable the server only on an explicit user toggle, ideally with auto-off.
  - Run it as a foreground service with a persistent notification.
  - Default to read-only tools.
- **Foreground service type:** the 6-hour cap documented above applies to `dataSync`. Whether `specialUse` or another type avoids the cap, and what Google Play policy says about it, was not verified.
- **Network security config:** Android's network security config mainly governs the app's outbound TLS and cleartext policy, not inbound listening sockets. This is from background knowledge, not a source fetched here. It would not stop other apps from connecting.
- **Battery:** keeping a Ktor server alive costs battery mainly through the wakelock and foreground service. An on-demand toggle limits this. No measured figures were found.

### Gaps
- No existing production Android app hosting an MCP server was found in the searches. Whether Kotlin SDK server artifacts run cleanly on Android (Ktor CIO/Netty engine on ART) was not confirmed by any sample.
- No official Google doc on loopback reachability between apps was retrieved; the evidence is SEI CERT, Hackaday and GrapheneOS.

## Option B: Export or sync to a PC and run a local stdio MCP server

### Takeaway
A stdio server is spawned by the client as a subprocess. It has no network listener, which removes the "other apps or websites can connect" problem entirely. It is the transport the spec says clients should prefer. Precedents (Screenpipe, Bee CLI) use exactly this pattern for lifelog data. The remaining risks are the exported file at rest on the PC, the transfer path, and whatever the LLM client does with tool results.

### Cited Findings
- **stdio in the spec:** the client launches the server as a subprocess and talks over stdin/stdout; clients "SHOULD support stdio whenever possible". — [MCP spec, Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- **Desktop config is local:** local servers in `claude_desktop_config.json` run on the local machine but are not available in claude.ai or Cowork. — [Claude Help Center](https://support.claude.com/en/articles/11175166-about-custom-connectors-using-remote-mcp)
- **Screenpipe precedent:**
  - The MCP server is a local bridge to the Screenpipe daemon on `localhost:3030`. It runs over stdio by default, launched from the Claude Desktop JSON config via npx.
  - An optional HTTP mode (port 3031 in one guide) exists for remote clients, and its health check is unauthenticated.
  - These are vendor docs and READMEs; no independent audit was found.
  — [Screenpipe docs: MCP server](https://docs.screenpi.pe/mcp-server); [screenpipe-mcp README](https://cdn.jsdelivr.net/npm/screenpipe-mcp@0.18.14/README.md)
- **Bee precedent:** Bee CLI reportedly exports conversations, facts and tasks to local Markdown files and doubles as an MCP server for Claude and Codex. This is single-source and not verified against Bee's docs. — [ai.engineer org page for Bee/Amazon](https://ai.engineer/orgs/bee-amazon)

### Inferences
- **Transfer paths, ranked by attack surface:**
  1. USB or adb pull of an export file. No network at all.
  2. An Android share-sheet export to a local file.
  3. LAN sync, which needs a pairing secret and TLS.
  Any cloud-drive route breaks the "no external transmission" principle.
- **Encryption at rest:**
  - The export could be an encrypted SQLite file (for example SQLCipher) or an age-encrypted archive. The stdio server would decrypt it with a key from the OS keychain at launch.
  - Plain export files inherit whatever disk encryption the PC has (BitLocker/FileVault).
  - These are design suggestions, not sourced findings.
- **Python fit:** a Python stdio server fits the repo's existing `tools/` Python stack.
- **Where the data goes next:** a local stdio server does not change where data flows after the tool returns. Results go into the cloud LLM's context unless Option D (local LLM) is used.

### Gaps
- No source compared Room DB export formats or encryption libraries for this flow.

## Option C: Remote MCP server reachable by cloud clients (relay or tunnel)

### Takeaway
Claude.ai web and mobile custom connectors require a public HTTPS endpoint reachable from Anthropic's IP ranges. Using them means running a publicly addressable server that holds or proxies the lifelog. That directly conflicts with Owl's "no cloud, no external transmission" principle. Tunnels differ in who sees plaintext:
- **Cloudflare Tunnel** terminates TLS at Cloudflare's edge, so Cloudflare can read the traffic.
- **Tailscale Funnel** relays do not decrypt; TLS terminates on your device.

End-to-end encryption cannot hide content from the LLM vendor, because the model needs plaintext in its context.

### Cited Findings
- **Network requirement:** claude.ai connectors need a server reachable over the public internet from Anthropic's IP ranges. Private networks must allowlist Anthropic's IPs, and the connection originates from Anthropic, not the user's device. — [Claude Help Center: custom connectors](https://support.claude.com/en/articles/11175166-about-custom-connectors-using-remote-mcp)
- **Tailscale Funnel:**
  - "Funnel relay servers do not decrypt the traffic"; the device's Tailscale process terminates TLS.
  - Allowed ports are 443, 8443 and 10000. The URL is `*.ts.net`, and DNS resolves to the relay, which hides the device's IP.
  - It is publicly reachable without Tailscale, and Tailscale adds no visitor authentication, so the app must authenticate.
  - Bandwidth limits apply, and the feature is marked beta.
  - Tailscale Serve shares only within the tailnet. That would not be reachable by Anthropic's servers; this last point is an inference from the connector network requirement above.
  — [Tailscale KB: Funnel](https://tailscale.com/kb/1223/funnel)
- **Cloudflare Tunnel:** Cloudflare's docs say TLS termination happens in Cloudflare data centers by default; Regional Services can limit which regions do it. Community sources say Tunnel offers no TLS passthrough for HTTP hostnames, so Cloudflare sees plaintext. That passthrough claim was not verified in official docs. — [Cloudflare docs: Default HTTP privacy](https://developers.cloudflare.com/data-localization/regional-services/http-requests/); [Nextcloud forum discussion](https://help.nextcloud.com/t/is-cloudflare-tunnel-safe-privacy-focused/150268)
- **Malicious remote MCP servers:** OpenAI's remote MCP docs warn that a malicious server can exfiltrate sensitive data from anything in the model's context and advise vetting servers. — [OpenAI developers: remote MCP](https://developers.openai.com/docs/guides/tools-remote-mcp)

### Inferences
- **A phone is a poor public origin.** Behind NAT, CGNAT on mobile networks, and Doze all work against it. Realistic Option C designs are:
  - (a) a home PC or mini-server holding a synced copy, exposed via Funnel, or
  - (b) a small VPS relay.
  Both add an always-on internet-facing component and an auth system: claude.ai custom connectors expect OAuth, though details were not fetched here.
- **E2EE helps only part of the path.** It can protect the device-to-relay hop and data at rest on the relay. The relay or the endpoint must decrypt before returning tool results, and the LLM vendor receives plaintext tool output in every case.
- **"Minimal exposure" variant:** expose only narrowly scoped, read-only, rate-limited, time-boxed tools, for example search over the last N days. Return snippets rather than whole transcripts or audio, and let the user switch exposure off by default.

### Gaps
- I did not verify the current claude.ai custom-connector auth requirements (OAuth 2.1 with dynamic client registration vs. static tokens) or the exact Anthropic IP range page.
- I did not check ngrok's security model.

## Option D: Fully local LLM as the MCP client

### Takeaway
Running the model locally is the only option where transcript content never reaches an LLM vendor. On Android, Gemini Nano via ML Kit GenAI Prompt API now supports function calling, but only with Gemini Nano 4 on supported devices, and the API is in preview or beta. On a PC, local models with an MCP-capable client avoid vendor retention entirely. Quality on long Korean transcripts is an open question.

### Cited Findings
- **ML Kit GenAI Prompt API function calling:**
  - Supports single-turn and multi-turn tool calls with tools defined via the `genai-schema` library.
  - Requires Gemini Nano 4 (the "Fast" preview model visible in the AICore app); without it, function calling won't work on that device.
  - Kotlin 2.3.0+, minSdk 26. Samples use `genai-prompt 1.0.0-beta5` and `genai-schema 1.0.0-alpha2`.
  — [Google ML Kit: Use function calling](https://developers.google.com/ml-kit/genai/prompt/android/function-calling)

### Inferences
- **On-phone shape:** Gemini Nano function calling is not MCP. On-phone, the app would call its own Room DB directly through Nano tool definitions with no network server at all, which has the smallest attack surface. MCP adds value mainly when the client is a separate program.
- **PC shape:** a stdio server combined with a local model runner such as Ollama or llama.cpp and an MCP-capable client keeps everything offline. I did not retrieve sources on which local clients support MCP as of Nov 2026.
- **Hybrid:** a local LLM pre-filters or summarizes, and only the minimal text goes to a cloud LLM. This reduces, but does not eliminate, disclosure.

### Gaps
- Device coverage of Gemini Nano 4 (which Galaxy and Pixel models) and its Korean-language quality were not researched.
- No sources were fetched for Ollama or llama.cpp MCP client support.

## Precedents: how lifelogging and memory products exposed data (MCP, APIs, auth, incidents)

### Takeaway
Every major wearable precedent was cloud-first:
- **Limitless and Omi** used vendor-cloud APIs authenticated with user-generated API keys sent in a header or environment variable, with MCP added as a thin layer over that cloud API.
- **Plaud** has no official MCP; community servers reverse-engineer web tokens.
- **Screenpipe** is the main local-first precedent, using a stdio bridge to a localhost daemon.

Acquisitions (Meta–Limitless, Amazon–Bee) shut down or region-locked services, including reportedly in South Korea. That is an argument for Owl's local-only stance.

### Cited Findings
- **Limitless:**
  - The developer API uses an API key from the account's Developer settings, sent in the `X-API-Key` header.
  - MCP servers for it are community-built, set up with `claude mcp add` and `LIMITLESS_API_KEY`, and the API was in beta.
  — [Limitless Developer Platform](https://limitless.ai/developers); [playbooks.com: Limitless Lifelog MCP](https://playbooks.com/mcp/limitless-lifelog); [Glama: limitless-ai-mcp-server](https://glama.ai/mcp/servers/ericbuess/limitless-ai-mcp-server)
- **Meta acquired Limitless (formerly Rewind) on 2025-12-05.**
  - Hardware sales stopped, and Pendant owners get about one year of support.
  - Rewind Mac app screen and audio capture was disabled on 2025-12-19.
  - Users had to accept Meta's terms; export and deletion tools were offered.
  - Service was discontinued in the EU and UK.
  — [9to5Mac, 2025-12-05](https://9to5mac.com/2025/12/05/rewind-limitless-meta-acquisition/); [BetaNews, 2025-12-08](https://betanews.com/2025/12/08/meta-has-acquired-limitless-a-startup-that-produces-ai-powered-wearables/)
- **Regions dropped by Limitless:** one lower-quality blog lists Brazil, China, the EU, Israel, South Korea, Turkey and the UK. South Korea is not corroborated by a primary source. — [mrktcorrect blog](https://mrktcorrect.pages.dev/blog/limitless-pendant)
- **Bee:**
  - Amazon agreed to acquire Bee in July 2025, and Bee employees received Amazon offers.
  - BeeMCP is an unofficial community MCP server that uses the user's Bee API token (`BEE_API_TOKEN`) and calls Bee's cloud API.
  — [Built In, 2025-07-23](https://builtin.com/articles/amazon-bee-acquisition-20250723); [mcp.so: BeeMCP](https://mcp.so/servers/beemcp)
- **Omi (Based Hardware, open source):**
  - The official MCP server needs an MCP key (`omi_mcp_...`) generated in the app under Settings > Developer > MCP. It is passed per tool call or via `OMI_API_KEY`.
  - Tools include `get_conversations`, `get_memories`, `create_memory`, `delete_memory` and `search_conversations`, so the server can write as well as read.
  - A separate developer REST key (`omi_dev_...`) covers `/v1/dev/user/...`, and keys are revocable in the app.
  - The server is also packaged as a Docker MCP image.
  — [Omi docs: MCP](https://docs.omi.me/doc/developer/MCP); [Omi docs: API overview](https://docs.omi.me/doc/developer/api/overview); [Docker Hub mcp/omi](https://hub.docker.com/r/mcp/omi)
- **Omi security disclosure (self-published, unverified):** a researcher reports 14, later 17, vulnerabilities in the Omi backend reported on 2026-04-15, with no response for 14 days. The four critical findings include an auth bypass via a development flag and SSRF in webhook URLs. Treat these as claims pending an advisory or CVE. — [kasparovabi security research, 2026-04](https://kasparovabi.github.io/security-research/2026-04-omi-disclosure.html)
- **Omi Android OAuth issue:** a GitHub issue (mirror domain) describes missing PKCE in the Android Google OAuth flow, with account takeover as the stated impact. — [Omi issue #7692 mirror](https://gh.r-mj.com/BasedHardware/omi/issues/7692)
- **Plaud:**
  - No official MCP server was found. Community servers use Plaud's unofficial web API with a bearer token extracted from web.plaud.ai; tokens expire.
  - One project explicitly says it was reverse-engineered.
  - A hosted third-party bundle (MCPBundles) offers an OAuth-based read-only connector.
  — [PyPI mcp-server-plaud](https://pypi.org/project/mcp-server-plaud/); [plaud-toolkit](https://github.com/sergivalverde/plaud-toolkit); [MCPBundles Plaud](https://www.mcpbundles.com/skills/plaud)
- **Screenpipe:** a local-first design with a local stdio MCP server bridged to a localhost daemon, and an optional HTTP mode. — [Screenpipe docs](https://docs.screenpi.pe/mcp-server)

### Inferences
- **The common pattern** is "long-lived bearer key generated in-app, pasted into the MCP client config". It is simple but leaves a plaintext secret on disk in client configs. Omi's separation of MCP keys from REST keys, plus in-app revocation, is a reasonable minimum to copy if Owl issues tokens.
- **Omi's write tools** (`create_memory`, `delete_memory`) show the risk of exposing mutating tools to an LLM that may be prompt-injected. Owl should consider read-only tools by default.
- **Owl's differentiator** is local-only storage. All cloud precedents suffered from vendor-side risks: backend vulnerabilities (Omi) and acquisition-driven shutdowns and terms changes (Limitless, Rewind, Bee).

### Gaps
- I did not research Friend (friend.com) or other memory products (e.g. Mem, Personal.ai) because of the call budget.
- I did not verify whether Limitless's API or MCP still works in Nov 2026, or whether South Korea was cut off.
- The Omi disclosure has not been confirmed by Based Hardware.
- I found no primary Rewind privacy-debate sources beyond the acquisition coverage.

## Where the data goes with a cloud LLM: retention and training policies

### Takeaway
MCP tool results enter the cloud LLM's conversation context, so the vendor's chat policy applies to them.

**Anthropic (consumer plans):**
- Since the 2025 consumer terms update, Free, Pro and Max (including Claude Code on those plans) train on chats only if the user opts into Model Improvement. Opting in means 5-year retention; otherwise 30 days.
- Anthropic's Privacy Center says raw connector content, including from remote and local MCP servers, is not used for training. It adds a caveat that content "directly copied into your conversation" may be.
- API and commercial plans are excluded from consumer training.

**OpenAI:**
- Consumer plans may train on connector data if "Improve the model for everyone" is on.
- Business, Enterprise and API data is not trained on by default.
- Zero Data Retention is approval-gated.

### Cited Findings
- **Anthropic consumer terms update (2025):**
  - It applies to Claude Free, Pro and Max, including Claude Code from those accounts.
  - It excludes Commercial Terms services: Claude for Work (Team/Enterprise), the API, Amazon Bedrock, Google Vertex AI, Claude Gov and Claude for Education.
  - With training allowed, retention is 5 years; otherwise 30 days.
  - It applies to new or resumed chats and coding sessions, and the deadline for existing users was 2025-10-08.
  - Press reported Sept 28, but Anthropic's page states Oct 8.
  — [Anthropic: Updates to our consumer terms](https://www.anthropic.com/news/updates-to-our-consumer-terms); [TechCrunch, 2025-08-28](https://www.techcrunch.com/2025/08/28/anthropic-users-face-a-new-choice-opt-out-or-share-your-data-for-ai-training/)
- **Anthropic connector training exclusion:**
  - Training data "does not include raw content from connectors (e.g. Google Drive), including remote and local MCP servers, though data may be included if it's directly copied into your conversation with Claude."
  - Incognito chats are not used even with Model Improvement on.
  — [Anthropic Privacy Center: Is my data used for model training?](https://privacy.claude.com/en/articles/10023580)
- **Anthropic connector policy page:** tool call parameters and responses are logged. Team and Enterprise data is excluded from training, and connector terms grant Anthropic no right to train on data accessed through the partner's server. — [Claude docs: connector policies](https://claude.com/docs/connectors/policies)
- **OpenAI:**
  - For Free, Plus and Pro, OpenAI may use information accessed from connectors to train if "Improve the model for everyone" is on.
  - Business, Enterprise and API inputs and outputs are not trained on by default.
  - ZDR accounts cannot opt into data sharing. API abuse-monitoring logs keep content for up to 30 days by default, and ZDR or modified abuse monitoring requires approval.
  — [OpenAI Help: How your data is used to improve model performance](https://help-lb.openai.com/en/articles/5722486-how-your-data-is-used-to-improve-model-performance); [OpenAI Help 10306912](https://help-lb.openai.com/en/articles/10306912)

### Inferences
- **Ambiguity in Anthropic's exclusion:** MCP tool results are rendered into the conversation transcript. It is unclear whether Anthropic treats them as "raw connector content" (excluded) or as conversation content (included when opted in). The conservative assumption for Owl is that tool output is chat content: retained 30 days with training off, or up to 5 years with training on.
- **Practical guidance for the owner:**
  - Keep Model Improvement off, or use Incognito chats for Owl queries.
  - Or use the API with a commercial key, for example from Claude Code under an API or Team plan, where consumer training terms do not apply.
  - Return minimal excerpts from tools.
- **Any cloud LLM use** is an "external transmission" under Owl's current principle. `docs/prd.md` will need an explicit carve-out ("user-initiated query results sent to an LLM the user chose"), or the MCP feature will drift from the PRD.

### Gaps
- Google Gemini (consumer Gemini Apps activity vs. Vertex/API) retention and training policies for tool results were not researched.
- I did not confirm whether OpenAI's ZDR covers remote MCP tool outputs.
- I did not fetch the Anthropic page on API default retention and ZDR availability.
