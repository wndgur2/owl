# Designing Owl's third-party developer platform: an OAuth authorization server, REST API and remote MCP endpoint for highly sensitive lifelog data

Scope: platform and ecosystem design decisions for becoming an OAuth provider of sensitive personal data. The data is Korean ambient-speech transcripts, timestamps, GPS places, bookmarks and possibly audio. Consumers are ordinary third-party apps (REST + OAuth) and AI clients such as Claude and ChatGPT (remote MCP). The notes are modeled on Google (Gmail/Calendar/Health API), Apple HealthKit, Android Health Connect, and the Fitbit, Oura and WHOOP wearable APIs, plus the Atlassian, Notion and Linear MCP servers. Researched 2026-11-08. The MCP spec's own auth rules (OAuth 2.1, PRM, PKCE, resource indicators, CIMD vs DCR, the 2026-07-28 spec) are covered in `research_notes/Owl MCP 서버 보안 설계/mcp_spec_auth_transport.md` and are referenced here only where relevant.

Source legend:
- **[V]** fetched from the primary source in this session.
- **[S]** secondary source, search snippet or aggregator only. Verify before quoting.
- **[K]** researcher's prior knowledge of a stable standard. The primary URL is given but was not re-fetched this session.

---

## Q1. Scope design: sensitivity tiers, granular and incremental consent, per-data-type permissions, and proposed Owl scopes

### Takeaway
Every mature platform follows the same pattern:
1. Split access by data type and by read vs write.
2. Put the most invasive scopes (Gmail content; all of Google Health) in a "restricted" tier with heavier review.
3. Let users approve or decline each scope individually.
4. Have apps request scopes in context (incremental authorization) and degrade gracefully when a scope is declined.

Health platforms add two more dimensions that matter for Owl: **time depth** (Health Connect's 30-day default history window) and **background access**. Both are separate permissions. Google adds **time-limited grants**, after which the refresh token dies. For Owl, raw audio and verbatim transcripts belong in the top tier. Derived and aggregate data, time windows and "live vs history" access should be separate grants.

### Cited Findings

**Google's sensitivity tiers**
- [V] Restricted scopes "provide wide access to Google user data". They need scope verification before any Google Account can grant them, and there are fewer of them than sensitive scopes. — [Google: Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
- [S] Example classification:
  - `gmail.readonly` is **restricted** and needs verification plus a security assessment.
  - `calendar.readonly` and `calendar.events` are **sensitive** and need verification but no security assessment.
  - Mail, calendar and contacts scopes are independent, so a calendar-only app avoids restricted-scope review entirely.
  - Google's Calendar auth page lists finer scopes without labelling their sensitivity: `calendar.app.created` ("Make secondary Google calendars, and see, create, change, and delete events on them"), `calendar.freebusy` ("View your availability"), `calendar.events.readonly` and `calendar.events.owned`.
  - Sources: [Nylas: Google verification and security assessment guide](https://developer.nylas.com/docs/dev-guide/provider-guides/google/google-verification-security-assessment-guide); [Nylas: Gmail API OAuth scopes reference](https://developer.nylas.com/docs/cookbook/use-cases/build/google-oauth-scopes/); [Google Calendar API auth](https://developers.google.com/workspace/calendar/api/auth) [V]
- [V] Google may raise the risk level of a scope later. An app that was verified can then become "unverified", with a grace period before the unverified-app screen and user cap apply. So tiers are a living classification, not fixed at launch. — [Google Cloud Help: Unverified apps](https://support.google.com/cloud/answer/7454865)
- [V] **Google Health API (Fitbit's successor):** "All Google Health API scopes are categorized as Restricted, necessitating a privacy and security review for access."
  - Data is organized into "simplified data type bundles".
  - Intraday data and webhooks are available for all relevant data types.
  - The Fitbit Web API and Fitbit OAuth endpoints were turned off on **2026-11-02** and now return `410 Gone`. End of support was 2026-09-30.
  - Source: [Google Health API: About](https://developers.google.com/health/about)
  - [S] Scope names look like `googlehealth.activity_and_fitness.readonly`, split read/write per category. Existing Fitbit tokens do not transfer, so every user must consent again. — [Terra blog](https://tryterra.co/blog/fitbit-api-shutdown-september-2026-migration); [Validic](https://trust.validic.com/incidents/8lzmtg20hp90); [Sahha](https://sahha.ai/blog/fitbit-api-sunset-migration/)
  - Note the conflict: Google's page says the turn-off date is 2026-11-02, while some vendor posts say "September 2026" or 2026-10-30. Google's page is authoritative.

**Granular consent and incremental authorization (Google)**
- [V] Users choose which permissions to share, e.g. Calendar but not Gmail.
  - Checkboxes appear only when an app requests several non-Sign-In scopes. With a single scope, the user approves or denies the whole request.
  - Apps "can't assume users granted every requested scope". They must check the granted scopes and disable the related features.
  - Apps should ask for a denied scope again only after the user clearly tries to use the feature that needs it.
  - Apps should request scopes when the task needs them, using `include_granted_scopes` (incremental authorization), and should "avoid bundling many scopes at sign-in".
  - Server-side web apps check `tokens.scope` after the exchange. `enable_granular_consent` is a testing switch.
  - Source: [Google: How to handle granular permissions](https://developers.google.com/identity/protocols/oauth2/resources/granular-permissions)
- [V] Google's general OAuth guide gives the example of requesting Calendar access only when the user clicks "Add to Calendar". It says to compare granted scopes with required ones. — [Google: Using OAuth 2.0](https://developers.google.com/identity/protocols/oauth2)
- [V] Google's User Data Policy says to request only the permissions needed, and to request them in context where possible using incremental authorization. — [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)

**Time-limited grants (Google)**
- [V] One documented refresh-token expiry cause: "If a user granted time-limited access and it expires, the refresh token stops working." This is precedent for user-chosen grant durations at the consent screen. — [Google: Using OAuth 2.0](https://developers.google.com/identity/protocols/oauth2)

**Android Health Connect**
- [V] There is a separate READ and WRITE permission per data type, e.g. `android.permission.health.READ_HEART_RATE` and `WRITE_HEART_RATE`. Exercise has a separate `READ_EXERCISE_ROUTE` permission, so GPS route data is split from the workout itself.
  - Two extra read permissions are declared separately from the data-type permissions:
    - `READ_HEALTH_DATA_IN_BACKGROUND`
    - `READ_HEALTH_DATA_HISTORY` ("Read data older than 30 days")
  - Apps must declare data use and data-type access in the Play Console. Otherwise users may see an error because some types "require special approval".
  - Source: [Health Connect data types](https://developer.android.com/health-and-fitness/guides/health-connect/plan/data-types)
- [V] Google Play's health-permissions policy:
  - Access "must be directly tied to providing a clear benefit to the user".
  - Apps request "the minimum data types needed", with a justification per permission.
  - Reproductive health, substance use, clinical vitals and self-reported symptoms get heightened scrutiny.
  - Common denial reasons include "requesting unnecessary bulk permissions".
  - Source: [Play Console Help: Health apps / health permissions](https://support.google.com/googleplay/android-developer/answer/12991134)

**Apple HealthKit**
- [S, from Apple docs via search] Authorization is per data type: the system sheet lists each requested type.
  - Apps cannot tell whether read access was denied. A denied read returns only the app's own samples. Apple does this on purpose so that a denial does not itself reveal health information.
  - After a denial, the app cannot prompt again. The user must change the setting in the Health app.
  - Source: [Apple: Authorizing access to health data](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data)

**Wearable vendor APIs**
- [V] Fitbit Web API (legacy, now shut down):
  - Scopes: activity, blood_glucose, cardio_fitness, electrocardiogram, heartrate, irregular_rhythm_notifications, **location**, nutrition, oxygen_saturation, profile, respiratory_rate, settings, sleep, social, temperature, weight.
  - "It is ultimately up to the Fitbit user whether or not all of the scopes are enabled." Apps must not pre-enable all scopes or force users to enable them, and must keep working when a scope is not granted.
  - **Intraday** (high-resolution) data needs a separate request form for client and server apps, granted "case-by-case". "Personal" apps can see only the developer's own data, including intraday data.
  - Source: [Fitbit Web API: Application design](https://dev.fitbit.com/build/reference/web-api/developer-guide/application-design/)
- [S] Oura API v2 scopes: `email`, `personal`, `daily`, `heartrate`, `workout`, `tag`, `session`, `spo2`. The token, authorize and revoke endpoints are separate (`/oauth/revoke` exists). I could not render Oura's official docs page. — [ExOura docs](https://hexdocs.pm/ex_oura/ExOura.OAuth2.html); [Oura v2 docs](https://cloud.ouraring.com/v2/docs) (did not render)
- [V] WHOOP: you must request the `offline` scope to receive a refresh token. Other scopes are listed in the API reference, which I did not fetch. — [WHOOP OAuth](https://developer.whoop.com/docs/developing/oauth)

**Rich Authorization Requests (fine-grained, structured grants)**
- [V] RFC 9396 `authorization_details` is a JSON array of objects.
  - Each object has a required `type` and optional common fields: `locations`, `actions`, `datatypes`, `identifier`, `privileges`.
  - Fields inside one object combine as a cartesian product. Separate objects give finer control (§2.2).
  - The user may grant a subset. The AS returns the details actually granted in the token response and may enrich them, e.g. with the accounts the user picked (§7).
  - Unknown types are rejected with `invalid_authorization_details` (§5).
  - When combined with `scope`, the AS "MUST present the merged set of requirements" (§3.1).
  - Details are exposed to resource servers as a top-level JWT claim filtered to the audience, or in introspection responses (§9).
  - PAR is recommended for large requests (§11.4).
  - Source: [RFC 9396](https://datatracker.ietf.org/doc/html/rfc9396)

### Inferences
- **Proposed Owl scope tiers**, using Google's three tiers and Health Connect's dimensions (data type × read/write × history depth × background):
  - *Basic, no review*: `openid`, `profile`, `owl.device.status` (battery and sync state, no content).
  - *Sensitive (verified apps only)*:
    - `owl.bookmarks.read` (bookmark timestamps and labels)
    - `owl.timeline.read` (segment metadata: start/end, duration, speech/no-speech; no text)
    - `owl.places.coarse.read` (city or district level, or named-place labels)
    - `owl.summaries.read` (daily or derived aggregates: talk time, places-visited count)
  - *Restricted (verified app plus security assessment plus Limited Use)*:
    - `owl.transcripts.read` (verbatim Korean text)
    - `owl.places.precise.read` (raw GPS, like Health Connect's separate `READ_EXERCISE_ROUTE`)
    - `owl.audio.read` (raw audio; possibly first-party only, or never granted to third parties)
  - *Write scopes* (`owl.bookmarks.write`) kept separate. No delete scope for third parties.
- **History depth as its own grant**, like Health Connect's 30-day default plus `READ_HEALTH_DATA_HISTORY`:
  - The default grant covers only data recorded *after* consent, plus an optional short backfill (e.g. 7 days).
  - Older history needs an explicit `owl.history` grant or a RAR `time_range`.
- **Time-window and expiring grants via RAR.** Example:
  `authorization_details=[{"type":"owl_transcripts","actions":["read"],"time_range":{"from":"2026-11-01","to":"2026-11-07"},"places_precision":"coarse"}]`.
  Put a grant `expires_at` on the consent screen (e.g. 1 hour / 7 days / 30 days / until revoked), like Google's time-based access. Scopes stay coarse for discovery and MCP `scopes_supported`. RAR carries the fine constraints.
  - Caveat: Claude and ChatGPT connectors do not send RAR. So for MCP clients, the time window has to be chosen by the user on Owl's own consent screen and stored server-side as grant metadata.
- **Background vs on-demand.** "Interactive only" (the token works only while the user is actively using the client) is hard to enforce in OAuth. A practical proxy is: no `offline_access` / refresh token for restricted scopes, or a short maximum grant lifetime.
- **Don't copy Apple's "silent denial" for an API.** For an AS, an explicit granted-scope list in the token response (RFC 6749 §5.1 `scope`, RFC 9396 §7) is more useful. Health Connect and Google both return what was granted.
- The consent UI must support unchecking individual scopes (Google, Fitbit). Third-party apps must then handle partial grants, which belongs in Owl's developer terms.

### Gaps
- I could not fetch Google's authoritative sensitive/restricted scope list (OAuth API Verification FAQ). The Gmail and Calendar classifications come from Nylas.
- I did not fetch Oura's official scope descriptions or WHOOP's scope list (`read:recovery`, `read:sleep` and so on are from memory and are not cited).
- I found no public detail on how Google's consent screen presents time-based access options or what durations it offers.
- I found no wearable vendor that offers per-time-window grants. This appears to be a gap Owl could fill, not an established pattern.

---

## Q2. App review and verification, Limited Use policies, and what a small company can realistically run

### Takeaway
Google's model has four layers:
1. Brand verification (days).
2. Sensitive-scope verification.
3. Restricted-scope verification plus an annual third-party CASA security assessment against OWASP ASVS. Tier 2 costs about USD 540 at Google's preferred lab, Tier 3 about USD 3.6–4.5k (pricing from secondary sources).
4. A Limited Use contract: no ads, no selling, no credit decisions, no human reading without consent, and, under the Workspace policy, no training of AI/ML models beyond that user's personalized model.

Unverified apps are capped at 100 users. Apple and Google Play impose similar purpose limits on health data. A small company like Owl cannot run Google-scale review, but it can copy the *structure*:
- A cap on unverified apps.
- Manual review for restricted scopes.
- Self-attestation plus an evidence questionnaire, with a third-party assessment (or accepting CASA LOVs, SOC 2 or ISO 27001 from developers) required only for transcript, audio or precise-location scopes.
- A Limited Use clause copied almost verbatim.

### Cited Findings

**Google verification mechanics**
- [V] Unverified apps:
  - Apps showing the unverified-app screen are limited to **100 new users in total**. Google may adjust this limit by app history, reputation and risk.
  - The screen appears when the app requests sensitive or restricted scopes that are not configured on the consent screen, or while verification is pending.
  - Exceptions: development or test builds, internal Workspace apps, and some platform plugins.
  - Verification "can take several months" depending on the sensitivity of the data.
  - Changing redirect URLs, origins or the product name may require re-verification.
  - Source: [Google Cloud Help: Unverified apps](https://support.google.com/cloud/answer/7454865)
- [V] Restricted-scope security assessment:
  - Required when the app accesses restricted data and "has the ability to access data from or through a third-party server".
  - Done by a Google-approved independent assessor using the App Defense Alliance CASA framework.
  - Repeated "at least every 12 months after your assessor's Letter of Assessment (LOA) approval date".
  - Adding a new restricted scope may trigger reassessment.
  - Restricted verification "can potentially take several weeks". Brand verification takes 2–3 business days.
  - Exceptions: personal use (a few users all personally known to the developer), internal-only use, dev/test/staging, and service-owned data.
  - Source: [Google: Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
- [V] CASA tiering:
  - Apps are assigned assurance level AL1 or AL2 by a risk-based evaluation using user count, requested scopes and other signals.
  - Requirements are based on **OWASP ASVS**. Passing yields a "Letter of Validation" (LOV).
  - Revalidation is annual. The level can go up, and an app validated at the higher level stays there.
  - A "CASA accelerator" maps existing certifications to CASA requirements to reduce checks.
  - Source: [Google Cloud Help: Security assessment](https://support.google.com/cloud/answer/13465431)
  - [S] Pricing: Tier 2 at about USD 540 per app through TAC Security, Google's preferred partner at a negotiated rate. Tier 3 at about USD 3,600–4,500. Turnaround is 1–3 weeks for Tier 2 and 2–4 weeks for Tier 3, per vendor claims. Sources conflict on whether Tier 3 pricing recurs yearly. — [Switch Labs CASA pricing](https://www.switchlabs.dev/post/casa-tier-2-tier-3-security-review-providers-pricing-and-the-cheapest-option); [TAC Security](https://tacsecurity.com/?p=16767)

**Google Limited Use**
- [V] General API Services User Data Policy:
  - "Limit your use of data to providing or improving user-facing features." This applies to raw *and* aggregated, anonymized or derived data.
  - Transfers are allowed only for user-facing features with consent, for security, for legal compliance, or in a merger with prior explicit consent.
  - "Don't allow humans to read the data, unless" there is the user's affirmative agreement for specific data, a security investigation, legal compliance, or aggregated internal operations.
  - Prohibited: transfer or sale to ad platforms and data brokers; use for serving ads including retargeting; use to determine creditworthiness or for lending.
  - Sensitive and restricted scope apps "must pass an annual security assessment and obtain a Letter of Assessment from a Google-designated third party".
  - Source: [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
- [V] The **Google Workspace API User Data and Developer Policy** adds AI and agent-era rules:
  - It prohibits "Transferring, selling, or using user data to create, train, or improve a machine learning or artificial intelligence model … beyond that specific user's personalized model for the appropriate use case or user-facing feature".
  - It prohibits "building databases (including databases for model training purposes)".
  - It requires that features be "visible and prominent in the requesting application's user interface".
  - It requires affirmative consent before collection, user help documentation on managing data, and encryption of tokens and credentials.
  - It requires "Protecting against prompt injection techniques".
  - Source: [Google Workspace API User Data and Developer Policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)

**Apple and Google Play health policies**
- [V] Apple App Review Guideline **5.1.3(i)**:
  - Health, fitness and medical data (HealthKit, Motion and Fitness, etc.) may not be used or disclosed to third parties "for advertising, marketing, or other use-based data mining purposes other than improving health management, or for the purpose of health research, and then only with permission".
  - Apps "must disclose the specific health data that you are collecting".
  - 5.1.3(ii): no personal health information in iCloud.
  - Source: [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [V] Apple **5.1.2(i)**: "You must clearly disclose where personal data will be shared with third parties, including with third-party AI, and obtain explicit permission before doing so." 5.1.2(vi) bars HealthKit data from marketing, advertising or use-based data mining "including by third parties". 5.1.2(iii) bars re-identification of "anonymized" or "aggregated" data. — [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [V] Google Play health permissions:
  - Approved use cases include fitness, rewards, corporate wellness, medical care, research and health games.
  - Prohibited: ads (including personalized ads); transferring or selling to third parties; credit, insurance, employment or lending decisions; sharing without "explicit, informed user consent"; public display without consent.
  - Required: a privacy policy and encryption at rest and in transit.
  - Review denials can be resubmitted.
  - Source: [Play Console Help](https://support.google.com/googleplay/android-developer/answer/12991134)

**Lighter-weight vendor gating**
- [V] Fitbit gated high-resolution (intraday) data behind a case-by-case request form, with "thorough review" for commercial apps. "Personal" app types could read only the developer's own account. — [Fitbit application design](https://dev.fitbit.com/build/reference/web-api/developer-guide/application-design/)

**Anthropic's directory policy (relevant if Owl lists its MCP connector)**
- [V] Anthropic Software Directory Policy:
  - Remote servers that need auth "must use secure OAuth 2.0".
  - Servers must provide a privacy policy.
  - "Software must only collect data from the user's context that is necessary"; "must not collect extraneous conversation data, even for logging purposes"; and must not query Claude's memory or chat history.
  - Developers must prove ownership of their endpoints and domains.
  - "MCP servers must provide all applicable annotations" (readOnlyHint, destructiveHint, title). Tool names are at most 64 characters.
  - Listing requires a test account with sample data, at least 3 example prompts, and initial plus ongoing review. Material changes trigger re-review.
  - Healthcare, insurance and finance are "high-risk use cases" that require human-in-the-loop measures and disclosure.
  - Source: [Anthropic Software Directory Policy](https://support.claude.com/en/articles/13145358-anthropic-software-directory-policy)

### Inferences
- **Realistic Owl review pipeline** (small team):
  - (a) *Development mode*: any developer, a cap of about 10–25 connected users (Google uses 100, but Owl's data is more sensitive), a red "unverified" banner on consent, and refresh tokens that expire in 7 days (as in Google's Testing mode).
  - (b) *Sensitive-tier verification*: domain and ownership check, privacy policy, a short justification per scope (as in Play's health declaration), and a demo video.
  - (c) *Restricted tier* (transcripts, precise GPS): everything in (b) plus a security questionnaire based on OWASP ASVS L2. Accept an existing CASA LOV, SOC 2 Type II or ISO 27001 instead of commissioning one, with annual re-attestation.
  - (d) *Audio*: first-party only at launch.
- **Limited Use clause for Owl's developer terms.** Combine Google's general and Workspace policies with Apple 5.1.3:
  - Use only for user-facing features visible in the app.
  - No ads, sale or data brokers.
  - No credit, insurance or employment decisions.
  - No human reading without per-item consent, except for security or legal reasons.
  - No training of general or shared AI/ML models; only a per-user personalized model is allowed.
  - No re-identification of aggregates.
  - Must defend against prompt injection.
  - Delete data on revocation.
  - These are cheap to adopt and set a clear standard for enforcement.
- **AI clients raise a specific question.** When a user connects Claude or ChatGPT, transcripts flow into that vendor's systems under its own terms. Owl cannot bind Anthropic or OpenAI with its developer terms the way it binds a small app. Owl's consent screen should therefore name the receiving AI vendor (as Apple 5.1.2(i) requires for "third-party AI") and link that vendor's data-use terms. This connects to the legal notes in `legal_privacy_korea.md`.
- Because ambient transcripts include **third-party speech**, Owl's data is arguably *more* sensitive than Gmail. This argues for at least Google-restricted-equivalent treatment of `owl.transcripts.read`.

### Gaps
- I found no primary Google page with CASA tier prices or a pass/fail consequence policy. Pricing comes from vendor and secondary sources.
- I found no public information on how smaller OAuth providers (Oura, WHOOP) review third-party apps. WHOOP's "App Approval" section was linked but not fetched.
- I did not fetch the OpenAI Apps SDK app submission and data policy guidelines.

---

## Q3. Token lifecycle: lifetimes, rotation and reuse detection, sender-constraining, revocation, introspection, connected-apps UX, and inactivity expiry

### Takeaway
The working consensus is:
- Short-lived access tokens: minutes to 1 hour (WHOOP's examples show 1 h; Fitbit used 8 h).
- Refresh tokens for public clients must be either sender-constrained (DPoP or mTLS) or rotated with reuse detection. On reuse, the AS revokes the whole active grant (RFC 9700 §4.14).
- Refresh tokens bound to the consented scope and resource.
- Expiry after inactivity: Google uses 6 months; RFC 9700 says SHOULD.
- Revocation endpoints and push revocation signals: Google RISC/SSF, WHOOP's stop-webhooks-on-revoke.

FAPI 2.0 differs on one point: it *prefers sender-constraining over rotation* and discourages rotation. For Owl, DPoP plus rotation with a grace window is the practical middle ground. AI-client connectors (ChatGPT) use PKCE public clients or `private_key_jwt`, and ChatGPT presents an mTLS client certificate.

### Cited Findings

**RFC 9700 (OAuth 2.0 Security Best Current Practice)**
- [V] Refresh tokens:
  - "Refresh tokens for public clients MUST be sender-constrained or use refresh token rotation" (§2.2.2).
  - With rotation, on detecting reuse the AS "will revoke the active refresh token", forcing the legitimate client to get a new grant (§4.14.2).
  - Refresh tokens "MUST be bound to the scope and resource servers as consented by the resource owner".
  - Refresh tokens "SHOULD expire if the client has been inactive for some time".
  - The AS MAY revoke tokens after security events such as a password change or logout.
  - Source: [RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700)
- [V] Access tokens:
  - Access tokens "SHOULD be audience-restricted to a specific resource server". The RS "MUST refuse to serve" a token not meant for it (§2.3).
  - "Authorization and resource servers SHOULD use mechanisms for sender-constraining access tokens" such as mTLS (RFC 8705) or DPoP (RFC 9449) (§2.2.1).
  - Source: [RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700)
- [V] Other RFC 9700 rules:
  - PKCE is required for public clients and RECOMMENDED for confidential clients (§2.1.1).
  - Redirect URIs use exact string matching, except for the localhost port (§2.1, §4.1.3).
  - No implicit grant (§2.1.2). ROPC "MUST NOT be used" (§2.4).
  - Prevent clickjacking of the consent page with CSP `frame-ancestors` (§4.16).
  - Don't redirect to the client after a *declined* consent from an untrusted client (§4.11.2, open-redirect phishing).
  - Don't let clients choose a `client_id` that could collide with a user `sub` (§4.15).
  - Source: [RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700)

**FAPI 2.0 Security Profile on tokens**
- [V] The AS "shall only issue sender-constrained access tokens" using mTLS or DPoP. It "shall not use refresh token rotation except in extraordinary circumstances". Note 1 permits rotation only if clients get a time-limited retry with the old token. For long-lived grants, §6.1 suggests short-lived (minutes or hours) access tokens plus refresh tokens. Authorization codes last at most 60 s and are single-use. — [FAPI 2.0 Security Profile (Final)](https://openid.net/specs/fapi-security-profile-2_0-final.html)

**Vendor practice**
- [V] Google:
  - A refresh token dies after 6 months unused, after 7 days if the consent screen is in "Testing" mode, when a time-limited grant expires, and possibly on a password change if the token has Gmail scopes.
  - There is a limit of **100 refresh tokens per Google Account per client ID**. The oldest is invalidated silently.
  - Source: [Google: Using OAuth 2.0](https://developers.google.com/identity/protocols/oauth2)
- [V] Google **Cross-Account Protection (RISC)** pushes signed Security Event Tokens to the app's HTTPS endpoint. Events include `tokens-revoked`, `token-revoked` (a single refresh token), `sessions-revoked` and `account-disabled` (reason `hijacking`).
  - The receiver must validate signature, audience and issuer, return 202, and de-duplicate by `jti`.
  - Use is limited to security, anti-fraud and session management. It is optional, not mandated.
  - Source: [Google: Cross-Account Protection (RISC)](https://developers.google.com/identity/protocols/risc)
- [V] WHOOP:
  - Refresh tokens rotate, and using one invalidates existing access tokens.
  - With concurrent refreshes, "the first refresh request that reaches WHOOP will succeed" and the others fail.
  - The `offline` scope is needed to get a refresh token at all.
  - Revoking stops webhooks for that user.
  - Source: [WHOOP OAuth](https://developer.whoop.com/docs/developing/oauth)
- [V] Fitbit: access tokens last 8 hours by default. Refresh tokens "can only be used once, but do not expire". — [Fitbit application design](https://dev.fitbit.com/build/reference/web-api/developer-guide/application-design/)
- [S] Oura exposes a dedicated `/oauth/revoke` endpoint. — [ExOura docs](https://hexdocs.pm/ex_oura/ExOura.OAuth2.html)

**Standards** (not re-fetched)
- [K] RFC 9449 **DPoP**:
  - The client proves possession of a key with a per-request signed JWT (`DPoP` header containing `htm`, `htu`, `iat`, `jti`, and `ath` for the token hash).
  - The AS binds the token to the key thumbprint (`cnf.jkt`). For public clients, the refresh token is also bound to the key.
  - It supports a server-provided `DPoP-Nonce` to limit pre-generated proofs.
  - Source: [RFC 9449](https://datatracker.ietf.org/doc/html/rfc9449)
- [K] RFC 7009 **Token Revocation**: a `/revoke` endpoint that takes `token` and `token_type_hint`. Revoking a refresh token SHOULD also invalidate the access tokens from the same grant. — [RFC 7009](https://datatracker.ietf.org/doc/html/rfc7009)
- [K] RFC 7662 **Token Introspection**: the RS asks the AS whether a token is `active` and gets back scope, client_id, sub, exp and aud. This is useful for instant revocation with opaque tokens. — [RFC 7662](https://datatracker.ietf.org/doc/html/rfc7662)
- [K] RFC 9068 defines the JWT access token profile (`typ: at+jwt`) for self-contained tokens validated by the RS. — [RFC 9068](https://datatracker.ietf.org/doc/html/rfc9068)

**AI-client compatibility**
- [V] ChatGPT:
  - Uses auth code + PKCE S256 and sends `resource`, which the server should copy into `aud`.
  - With CIMD it authenticates as `none` (public client) or `private_key_jwt`, with JWKS at `/oauth/jwks.json` on its metadata origin.
  - With DCR it "registers once per MCP connection".
  - It presents an OpenAI-managed mTLS client certificate (SAN `mtls.prod.connectors.openai.com`). Validate the chain, not a leaf fingerprint.
  - It does *not* support client credentials, JWT bearer assertions or customer mTLS certificates.
  - Source: [OpenAI Apps SDK: Authentication](https://developers.openai.com/apps-sdk/build/auth)
- [V] Notion: tools removed from the approved list may still show as connected "because existing tokens can't be revoked", though their calls are blocked. This shows the value of server-side enforcement on every call, not just at token issuance. — [Notion Help: Notion MCP](https://www.notion.com/help/notion-mcp)

### Inferences
- **Proposed Owl defaults:**
  - Access tokens: 10–15 minutes for restricted scopes, up to 60 minutes for sensitive scopes, with audience bound to `https://api.owl…` or `https://mcp.owl…` separately (RFC 8707).
  - Authorization codes: at most 60 s, single use.
  - Refresh tokens: rotated on every use, with reuse detection that revokes the entire grant family. Allow a short grace window of about 30 s for the concurrent-refresh race that WHOOP documents, as FAPI's Note 1 permits.
  - Refresh token sliding inactivity expiry: 30–90 days for restricted scopes (shorter than Google's 6 months, given the data). Absolute expiry equals the user-chosen grant duration.
  - DPoP: support and advertise it (`dpop_signing_alg_values_supported`) for first-party and serious third-party apps. Do not *require* it for MCP clients, because neither Claude nor ChatGPT documents DPoP support. For ChatGPT, optionally validate its mTLS client certificate as extra client identification.
- **Opaque vs JWT access tokens.** With short-lived JWTs, revocation takes effect within the token lifetime. With opaque tokens plus introspection (or a revocation-list check), revocation is instant. Given the data's sensitivity, an RS-side check of a "grant revoked" cache on every call is cheap insurance. It is the lesson from Notion's "existing tokens can't be revoked" note.
- **Connected-apps page** (in the Owl app and on the web), modeled on Google Account → Third-party access:
  - Per app: name, verification status, scopes, time window, last used, and a per-day access log (which tools or endpoints, how many records).
  - A one-tap revoke.
  - "Revoke all" for a lost phone or a compromised account.
  - Auto-expiry of unused grants with a notification before expiry.
- **Revocation signals to developers.** Provide RFC 7009 `/revoke` for clients. Push revocation events to developers as signed webhooks (SET-style, de-duplicated by `jti`, modeled on Google RISC). Make "delete the user's Owl data within N days of a `grant.revoked` event" a contractual obligation (see Q6).
- **Per-user token cap** (Google keeps 100 per client): cap active grants per (user, client), e.g. 5, so DCR or CIMD reconnect loops from AI clients don't pile up live refresh tokens.

### Gaps
- I did not re-fetch RFC 9449, 7009, 7662 or 9068 this session; their content is from prior knowledge.
- I found no Claude documentation stating whether Claude's connector client supports DPoP, rotates refresh tokens correctly, or what access-token lifetime it tolerates.
- I found no public figure for Google's access-token lifetime on the page I fetched. Elsewhere it is widely documented as 3600 s, but I did not cite that here.
- I found no published data on Google's 6-month inactivity expiry for restricted versus sensitive scopes, or whether it differs.

---

## Q4. Serving MCP clients from the same AS: Claude and ChatGPT connector requirements, and how Atlassian, Notion and Linear built remote MCP on existing OAuth

### Takeaway
The leading vendors put MCP on their **existing** authorization server, not a separate one:
- **Atlassian:** auth.atlassian.com supports both DCR and CIMD plus PKCE S256.
- **Linear:** OAuth 2.1 with DCR, and also accepts existing OAuth tokens or API keys as Bearer.
- **Notion:** per-user OAuth that inherits the user's permissions.

All three then add **per-client policy controls** on top: an approved-client or domain allowlist, read/write toggles, a read-only endpoint, admin-off destructive tools, IP allowlists, audit logs, and enterprise IdP delegation (Okta Cross App Access). For Claude and ChatGPT specifically:
- Support CIMD, which ChatGPT prefers, and keep DCR for older clients.
- Allowlist the exact redirect URIs.
- Return `iss` (RFC 9207).
- Optionally restrict by Anthropic and OpenAI egress IPs or by ChatGPT's mTLS certificate.

### Cited Findings

**Claude**
- [S] Claude's OAuth callback is `https://claude.ai/api/mcp/auth_callback` and the OAuth client name is "Claude". Anthropic says it may move to a claude.com callback and advises allowlisting that too.
  - Server developers can block non-Claude clients by allowlisting Anthropic IPs, the callback URL or the client name.
  - All remote MCP connections originate from Anthropic's cloud, whichever Claude client the user runs.
  - Claude supports DCR, and users can enter a custom client ID and secret for servers without DCR.
  - Source: [Claude Help: Building custom connectors via remote MCP servers](https://support.claude.com/en/articles/11503834-building-custom-connectors-via-remote-mcp-servers) (404 to WebFetch this session; content from search snippets); [Boomi guide](https://help.boomi.com/docs/Atomsphere/Platform/Connect_claude_setup)
- [V] Anthropic outbound IPs "for outbound requests (for example, when making MCP tool calls to external servers)": IPv4 `160.79.104.0/21` and `153.61.208.0/20`. Five old `34.162.x.x/32` addresses are phased out and should be removed. "These addresses will not change without notice." — [Claude Platform: IP addresses](https://platform.claude.com/docs/en/api/ip-addresses)
- [S] A user-reported issue describes claude.ai sometimes never POSTing to `/token` after the redirect. This is anecdotal. — [GitHub anthropics/claude-ai-mcp#215](https://github.com/anthropics/claude-ai-mcp/issues/215)

**ChatGPT**
- [V] Requirements for ChatGPT apps and connectors:
  - PRM at `/.well-known/oauth-protected-resource` with `resource`, `authorization_servers` and optionally `scopes_supported`.
  - AS metadata (RFC 8414 or OIDC) whose `code_challenge_methods_supported` must include `S256`.
  - `client_id_metadata_document_supported: true` enables CIMD, which is **preferred**; `client_id` looks like `https://chatgpt.com/oauth/client.json`. A `registration_endpoint` enables DCR.
  - Redirect URI: `https://chatgpt.com/connector_platform_oauth_redirect` when RFC 9207 issuer identification qualifies; otherwise `https://chatgpt.com/connector/oauth/{callback_id}`. `iss` must exactly match the metadata `issuer` "with no normalization".
  - ChatGPT requests advertised OIDC scopes (`openid`, `email`, `profile`) by default.
  - Per-tool `securitySchemes` (`noauth` or `oauth2` with scopes), and the server "must still verify tokens on every call".
  - Linking UI is triggered by a tool error carrying `_meta["mcp/www_authenticate"]`.
  - An optional `_meta["openai/profile"]` tool supports multi-account labeling.
  - Egress IP ranges are published separately.
  - Source: [OpenAI Apps SDK: Authentication](https://developers.openai.com/apps-sdk/build/auth)

**Atlassian (Rovo MCP server)**
- [V] Admin controls:
  - Atlassian-supported AI-tool domains are allowed by default. Admins can add domains, or block the supported list as a whole. "You can only block domains for AI tools that use OAuth 2.1."
  - API-token auth is a separate toggle. OAuth 2.1 is the default and recommended method.
  - Org IP allowlists apply to MCP calls regardless of auth method. The consent screen may still appear from a blocked IP, but tool calls fail.
  - A call succeeds only if the domain is allowed (OAuth), the IP is allowed, and the auth method is enabled.
  - Source: [Atlassian: Control Rovo MCP server settings](https://support.atlassian.com/security-and-access-policies/docs/control-atlassian-rovo-mcp-server-settings/)
- [S, third-party write-up checked Oct 2026]
  - auth.atlassian.com "supports dynamic client registration and Client ID Metadata Documents", with PKCE S256.
  - Tokens are consented per site and checked against the site's `cloudId`.
  - Read/Write/Search toggles per app take "precedence over Connected Apps settings".
  - Destructive tools (`delete_jira`, `manage_jira`) are off until an admin enables them.
  - Atlassian Guard data-security policies can block AI agents from reading covered items, for OAuth connections only.
  - Tool calls are logged under "Rovo MCP User Actions".
  - Claude MCP authorization can be delegated to Okta via Cross App Access.
  - Moving to v2 is a separate OAuth resource, so users must re-authenticate. `/v1/sse` ended 2026-06-30.
  - Source: [Scalekit: Atlassian MCP server](https://www.scalekit.com/blog/atlassian-mcp-server)

**Notion**
- [V] "MCP tools act with your full Notion permissions—they can access everything you can access", and MCP "does not bypass Notion permissions".
  - Enterprise admins can restrict to "Approved MCP clients only". Notion "blocks every call from any AI app or MCP client that is not on the approved list".
  - To approve a new client, an admin must first connect successfully from it.
  - "Disconnect All Users" forces everyone to re-authenticate.
  - Enterprise-managed connections via the IdP mean members don't approve individually.
  - The audit log is searchable by connection URL. Per-user usage visibility is "not yet available".
  - Source: [Notion Help: Notion MCP](https://www.notion.com/help/notion-mcp); [Notion Developers: MCP](https://developers.notion.com/docs/mcp)

**Linear**
- [V] Endpoint `https://mcp.linear.app/mcp` (Streamable HTTP) uses OAuth 2.1 with dynamic client registration. An existing OAuth token or API key can be sent directly as Bearer.
  - Read-only is offered two ways: a separate `https://mcp.linear.app/mcp/readonly` endpoint that "only ever exposes read tools", or the `read` OAuth scope so the token "can't reach write APIs".
  - Enterprise options: Okta-managed MCP auth, and "Claude-issued authentication for MCP" (beta), where admins enter the Claude issuer URL and tenant ID and members' emails must match.
  - The `/sse` endpoint is deprecated.
  - Source: [Linear Docs: MCP](https://linear.app/docs/mcp)

**MCP spec context** (from the prior notes)
- The 2026-07-28 spec makes CIMD the preferred registration method, deprecates DCR, and requires RFC 9207 `iss` validation. `tools/list` "MAY vary by the authorization presented", e.g. returning only the tools the caller's scopes permit. — [mcp_spec_auth_transport.md](../Owl%20MCP%20서버%20보안%20설계/mcp_spec_auth_transport.md); [MCP Authorization](https://modelcontextprotocol.io/specification/latest/basic/authorization)

### Inferences
- **One AS, two resource servers.** Run one Owl AS (`auth.owl…`) that issues audience-bound tokens for `api.owl…` (REST) and `mcp.owl…` (MCP). Each has its own PRM. The same scopes apply to both, and the MCP server's `tools/list` is filtered by granted scopes. MCP becomes a client of the same grants model, so the connected-apps page, revocation and audit apply uniformly.
- **Client registration strategy:**
  - Pre-registered confidential clients for vetted REST apps.
  - CIMD for AI clients, which is the preferred path for ChatGPT and the spec.
  - DCR kept only for compatibility.
  - Treat *DCR- and CIMD-registered clients as "unverified" by default*, with the 100-user-style cap and a warning banner. Maintain a small **trusted-client allowlist** keyed by CIMD URL host or exact redirect URI: `chatgpt.com/oauth/client.json` and Claude's `claude.ai/api/mcp/auth_callback` (and the future claude.com callback). This mirrors Atlassian's "supported domains allowed by default" and Notion's approved list.
- **Restricted scopes only to allowlisted AI clients.** An unknown CIMD or DCR client can obtain basic or sensitive scopes only. `owl.transcripts.read` requires an allowlisted client ID. This is Owl's analog of Google's restricted-scope review.
- **Defense in depth for AI clients** (optional, brittle):
  - Check the source IP against Anthropic `160.79.104.0/21` and `153.61.208.0/20`, or OpenAI's published egress ranges, for tokens issued to those clients.
  - Validate ChatGPT's mTLS certificate (SAN `mtls.prod.connectors.openai.com`).
  - Treat both as signals, not authentication. Ranges change "with notice".
- **Read-only by default**, following Linear's `/mcp/readonly` and Atlassian's destructive tools off by default. Owl's MCP should expose no write or delete tools at all at launch.
- **Per-client policy controls the user can set** (Owl is consumer-facing, so the "admin" is the user):
  - Allowed data types.
  - Time window, e.g. "only the last 7 days" or "only from 09:00–18:00".
  - Precise vs coarse location.
  - Maximum records per call or per day.
  - "Require confirmation in the Owl app for each transcript read". This could use MCP URL-mode elicitation or a push approval, and is stronger than any vendor here offers.

### Gaps
- I could not fetch the Claude help article itself (404 to the fetcher). Claude's support for CIMD, exact refresh-token handling and any token-lifetime constraints are unverified. The prior notes mention a "Use Claude's published identity" option that resembles CIMD.
- I did not fetch OpenAI's egress IP list.
- I did not fetch Atlassian's "Configure Atlassian MCP server permissions" page directly. Those details come from Scalekit's write-up.
- I found no public description of Google's own remote MCP servers' (Gmail, Calendar, Drive) auth or policy controls. Google lists them as MCP servers but I did not research their docs.

---

## Q5. Security standards for high-risk data: RFC 9700, FAPI 2.0, PAR, RAR

### Takeaway
RFC 9700 is the baseline every OAuth provider should meet now:
- PKCE.
- Exact redirect matching.
- No implicit grant or ROPC.
- Audience-restricted tokens.
- Sender-constrained or rotated refresh tokens.
- Clickjacking protection.

FAPI 2.0 is the "high-risk data" profile (open banking, health): PAR mandatory, PKCE S256, sender-constrained tokens (DPoP or mTLS), confidential clients only (`private_key_jwt` or mTLS), 60-second codes, `iss` in responses, and RAR recommended. Owl can apply FAPI 2.0 to *vetted third-party REST apps* that request restricted scopes. It cannot impose it on Claude or ChatGPT, which are public or `private_key_jwt` clients without PAR or DPoP in their documented flows. So the policy should be tiered by client type.

### Cited Findings
- [V] RFC 9700 baseline requirements are listed in Q3, from [RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700).
- [V] FAPI 2.0 Security Profile (Final), authorization server requirements:
  - PAR: the AS "shall support client-authenticated pushed authorization requests according to [RFC9126]" and "shall reject authorization requests sent without" PAR (§5.3.2.2(2–3)).
  - PKCE S256 required (§5.3.2.2(5)).
  - Only sender-constrained access tokens, by mTLS or DPoP (§5.3.2.1(4–5)).
  - Confidential clients only, authenticated with mTLS or `private_key_jwt` (§5.3.2.1(3,6)).
  - Codes last at most 60 s, and a reused code is rejected (§5.3.2.1(11), §5.3.2.2(9)).
  - `redirect_uri` required in PAR. No `http` scheme except native loopback. No open redirectors (§5.3.2.2(6,8), §5.3.2.1(7)).
  - `iss` in the authorization response per RFC 9207 (§5.3.2.2(7)).
  - `request_uri` `expires_in` under 600 s (§5.3.2.2(12)).
  - RAR "is recommended when the `scope` parameter is not expressive enough" (Note 5).
  - Refresh token rotation discouraged (§5.3.2.1(9)).
  - Source: [FAPI 2.0 Security Profile](https://openid.net/specs/fapi-security-profile-2_0-final.html)
- [K] RFC 9126 **PAR**: the client POSTs the authorization request parameters to the AS's `pushed_authorization_request_endpoint` (client-authenticated) and receives a short-lived `request_uri`. The front-channel URL then carries only `client_id` and `request_uri`, so parameters (scopes, RAR details) can't be tampered with or leaked in browser history. — [RFC 9126](https://datatracker.ietf.org/doc/html/rfc9126)
- [V] RFC 9396 **RAR**: structure and consent rules are in Q1. PAR is recommended for large RAR payloads (§11.4). — [RFC 9396](https://datatracker.ietf.org/doc/html/rfc9396)
- [K] RFC 8705 **mTLS** client authentication and certificate-bound tokens (`cnf.x5t#S256`). — [RFC 8705](https://datatracker.ietf.org/doc/html/rfc8705)
- [K] RFC 9207 **`iss` authorization response parameter** defends against mix-up attacks. ChatGPT requires exact `iss` matching (Q4), and the MCP 2026-07-28 spec requires clients to validate it. — [RFC 9207](https://datatracker.ietf.org/doc/html/rfc9207)
- [V] Google's Workspace policy explicitly requires developers to protect against prompt injection. This brings an AI-era control into a data-access policy. — [Google Workspace API User Data and Developer Policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)

### Inferences
- **Tiered security profile for Owl's AS:**

  | Client class | Registration | Client auth | PAR | Token binding | Restricted scopes? |
  |---|---|---|---|---|---|
  | First-party Owl app (Android) | pre-registered | public + PKCE (or attestation) | optional | DPoP required | yes |
  | Vetted third-party server app | pre-registered after review | `private_key_jwt` | **required** | DPoP or mTLS **required** | yes, FAPI 2.0-style |
  | Allowlisted AI client (Claude, ChatGPT) | CIMD (DCR fallback) | `none`+PKCE or `private_key_jwt` | not required (not supported) | not required; short access-token lifetime + rotation + server-side grant checks | yes, but only with user-set limits (time window, caps) |
  | Unknown CIMD/DCR client | CIMD/DCR | `none`+PKCE | no | none | **no** (sensitive tier max, user cap) |

- Always on, whatever the tier: PKCE S256, exact redirect matching, RFC 9207 `iss`, `frame-ancestors 'none'` on consent, 60 s codes, RFC 8707 audience binding, and no implicit grant or ROPC.
- **Owl authorization server metadata** to publish: `pushed_authorization_request_endpoint`, `require_pushed_authorization_requests` (per client), `dpop_signing_alg_values_supported`, `authorization_details_types_supported: ["owl_transcripts","owl_places",…]`, `client_id_metadata_document_supported: true`, `authorization_response_iss_parameter_supported: true`, `code_challenge_methods_supported: ["S256"]`, `revocation_endpoint`, `introspection_endpoint`.
- **Build vs buy.** FAPI 2.0, PAR, DPoP, RAR and CIMD together are a lot to hand-roll for a small team. The practical path is an off-the-shelf AS with FAPI 2.0 certification and CIMD support (open-source Keycloak, node-oidc-provider and Ory Hydra/Ory Network are candidates). I did not verify their CIMD or RAR support this session.

### Gaps
- I did not verify which open-source or hosted authorization servers currently support CIMD, RAR and FAPI 2.0 together (Keycloak, node-oidc-provider, Ory, Auth0, Okta).
- I found no evidence of whether Claude's or ChatGPT's connector clients support PAR, DPoP or RAR. OpenAI's auth page lists no PAR or DPoP support, but absence from the docs is not proof.
- I did not re-fetch RFC 9126, 8705 or 9207 this session.

---

## Q6. Developer-side obligations: terms, deletion on revoke, revocation webhooks, audit and attestation

### Takeaway
The obligations that platforms enforce on developers converge on a short list:
- A published privacy policy matching actual use (Google, Apple, Play, Anthropic).
- Limited Use with no ads, sale, credit decisions or general AI training (Google, Apple, Play).
- Encryption in transit and at rest, including stored tokens (Google Workspace, Play).
- Annual third-party security reassessment for restricted data (Google CASA).
- Re-review on material changes (Anthropic directory, Google redirect/name changes).
- Responding to revocation signals (Google RISC; WHOOP stops webhooks on revoke).

"Delete user data when the user revokes" is a common expectation, but I did not find it stated as an explicit rule in the Google pages I fetched. Owl should write it into its own terms explicitly and back it with a signed revocation webhook and a periodic attestation.

### Cited Findings
- [V] Google requires that "You must limit your use of Google user data to the practices that your published privacy policy discloses". Restricted-scope apps must reassess at least every 12 months, and adding a restricted scope may trigger reassessment. — [Google: Restricted scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification)
- [V] The Google Workspace policy requires:
  - A privacy policy that "fully documents how your application interacts with user data".
  - "You must not begin collection prior to obtaining affirmative consent."
  - User help documentation on how users can manage their data.
  - Encryption of "user data and credentials, specifically tokens such as OAuth access" tokens.
  - Prompt-injection protection.
  - Source: [Google Workspace API User Data and Developer Policy](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)
- [V] The general Google User Data Policy page I fetched does **not** address data deletion. — [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy)
- [V] Google RISC defines `token-revoked`. For a single revoked refresh token, if you store it, "delete it and ask the user to re-consent". For other Google API tokens, deleting stored OAuth tokens is suggested. The receiver validates the SET and returns 202. — [Google: RISC](https://developers.google.com/identity/protocols/risc)
- [V] WHOOP: revoking an access token stops webhooks for that user. — [WHOOP OAuth](https://developer.whoop.com/docs/developing/oauth)
- [V] Google Play health apps need a privacy policy covering collection, use, sharing, **retention** and security, and encryption at rest and in transit. Developers are "solely responsible" for complying with health-data laws. — [Play Console Help](https://support.google.com/googleplay/android-developer/answer/12991134)
- [V] Apple 5.1.3(i) requires disclosing "the specific health data that you are collecting". 5.1.2(i) requires explicit permission before sharing with third-party AI. — [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [V] Anthropic's directory requires reporting material changes to functionality, tool descriptions, data practices or endpoints, which may trigger re-review. It also requires verified contact and support channels, proof of endpoint ownership, and fixing issues "within reasonable timeframes". — [Anthropic Software Directory Policy](https://support.claude.com/en/articles/13145358-anthropic-software-directory-policy)
- [V] Atlassian logs tool calls in the audit log ("Rovo MCP User Actions", per Scalekit). Notion's audit log is searchable by connection URL, but per-user usage visibility is "not yet available". This shows that even large vendors' audit coverage of MCP is incomplete. — [Notion Help](https://www.notion.com/help/notion-mcp); [Scalekit](https://www.scalekit.com/blog/atlassian-mcp-server)
- [K] The OpenID **Shared Signals Framework** (SSF, including the CAEP and RISC profiles) standardizes push or poll delivery of security events such as session-revoked and credential-change as Security Event Tokens (RFC 8417). Google RISC is an implementation. — [OpenID SSF](https://openid.net/specs/openid-sharedsignals-framework-1_0.html); [RFC 8417](https://datatracker.ietf.org/doc/html/rfc8417)

### Inferences
- **Owl developer terms checklist** (one page plus a policy page):
  1. Limited Use (see Q2), including "no training of shared or general AI models" and "no human reading without per-item user consent".
  2. Data minimization: request only the scopes and time windows a visible feature needs. Handle partial grants.
  3. **Retention and deletion:**
     - Cached Owl data may be kept only as long as needed for the feature, with a maximum (e.g. 30 days) unless the user explicitly saves it in the third-party app.
     - On `grant.revoked` or `user.deleted`, delete all Owl-derived data within, e.g., 7 days (raw *and* derived, matching Google's "raw and derived" language) and confirm via an API call (`POST /v1/deletion-confirmations`).
  4. Security: encryption in transit and at rest, tokens encrypted at rest, prompt-injection defenses for apps that pass Owl data to LLMs, and breach notification to Owl within 72 h (mirroring GDPR Art. 33 timing; the Korean PIPA timing is in `legal_privacy_korea.md`).
  5. Annual re-attestation for restricted scopes. Re-review on new scopes, a redirect or domain change, or a material change in data practices.
  6. Audit cooperation: Owl may audit, and may suspend clients that violate terms. Suspension revokes all grants immediately.
- **Signals Owl sends to developers:** signed webhooks (SET format, `jti` de-dup, retry with backoff) for `grant.revoked`, `grant.expired`, `grant.scope_reduced`, `user.deleted` and `data.deleted` (when the user deletes a recording in Owl, downstream copies must be deleted too). This last event matters for lifelogging: users will delete embarrassing segments and expect them gone everywhere.
- **Audit Owl keeps and shows to the user:** a per-grant access log (client, tool or endpoint, time range queried, record count, IP or ASN), visible in the connected-apps page. This goes further than Notion's admin-only, URL-level audit and fits a consumer product.
- **AI-client caveat:** Owl cannot get deletion confirmations from Claude or ChatGPT conversation stores. The consent screen for AI clients should state this, and the per-call record caps and time windows (Q4) become the main control.

### Gaps
- I found no explicit Google policy text requiring deletion of user data on token revocation in the pages fetched. Google's verification FAQ or CASA requirements may contain it, but I did not fetch them.
- I did not fetch the OpenAI Apps SDK developer terms or the Anthropic Software Directory Terms (only the Policy). Their data-retention and deletion clauses are unknown.
- I found no wearable vendor (Oura, WHOOP) public developer-terms text on deletion-on-revoke in this session.
