# Owl — notes for Claude Code

Lifelogging pendant. Monorepo: `protocol/` (spec), `firmware/` (Zephyr/NCS), `android/` (Kotlin), `tools/` (Python), `hardware/`, `docs/`.

- The owner is a CS undergrad; explain embedded concepts when they come up. Docs are in Korean.
- `docs/prd.md` is the ground truth for what we build and why (features, priorities, non-goals, open questions). If code or other docs disagree with it, raise it and fix one side in the same commit; never silently drift.
- `protocol/ble-protocol.md` is the contract. Change it together with `tools/lpproto.py`, its tests, firmware and app in one commit.
- Firmware build: `west build -p -b xiao_nrf54l15/nrf54l15/cpuapp firmware -- -DEXTRA_CONF_FILE=uart.conf`, then `west flash`.
- Default `prj.conf` has UART off (battery boot issue). Debug with `uart.conf`.
- Pin assignments live in `firmware/boards/*.overlay` and must match `hardware/wiring.md`.
- Record design decisions in `docs/decisions.md`; update `docs/roadmap.md` when a milestone closes.
- Protocol tests: `python3 tools/tests/test_lpproto.py`.
